# Recorder contract

This Java 17 module has no Minecraft or OpenGL dependencies. Call `FfmpegRecorder.start()` on a preparation thread. It validates FFmpeg/ffprobe, probes the requested encoder with a real short encode (falling back to libx264), and starts the optional audio helper. No video time elapses until the first frame arrives.

`submitFrame(ByteBuffer, captureNanos)` accepts tightly packed **top-down RGBA8**, exactly `width * height * 4` remaining bytes, and copies them without changing the caller's buffer position. The caller may immediately reuse its buffer. Timestamp GPU capture submission, not delayed readback completion. The first capture timestamp becomes `startNanos()`; before then the value and `elapsedSeconds()` are zero. The camera path should use this same epoch.

`RecordingOptions` accepts an optional final `playbackSpeed` argument (finite, 1–120, including fractional values); the original nine-argument constructor means 1×. Duration, `elapsedSeconds()`, and submitted timestamps remain real time, including after an early stop. Output keeps the requested fps (1–60); capture cadence is `fps / playbackSpeed`, without rounding it to an integer. For example, 10 seconds at 10× and 24fps produces 24 output frames, captured at 2.4fps. The frame count rounds up, so very short recordings still contain one frame. The caller should reduce GPU rendering/readback to this cadence while polling pending readbacks every display frame. Any speed above 1× forces `recordAudio=false` before checking the helper, without changing the client's saved audio preference.

The module preallocates five image arrays (39.6 MiB at 1080p), retains at most three pending image frames, and drops superseded captures without blocking on encoding. CFR output repeats the last available frame for missing slots. A small settling delay absorbs asynchronous readback. Above 1×, that allowance also covers two real capture intervals, capped at one second unless the original allowance is already longer; the 1× allowance stays unchanged. Sustained encoding delay above two real seconds is an error. `finish()` returns the same future on every call and does not wait; call it when recording ends or the world becomes unavailable. Automatic duration completion also sets `isRecording()` false. `failure()` is null until an error.

During capture, FFmpeg writes fragmented MP4 under `.wacamera-recovery/<session>`. After orderly stop, the module aligns PCM audio, copies H.264 into a standard MP4, encodes AAC if requested, verifies streams/duration/frame count with ffprobe, and commits the final name. Errors retain media and logs, never report a recovered partial recording as a completed shot, and expose `recoveryDirectory()`. A successful session retains small diagnostics but removes temporary media. `close()` requests finalization; applications must let its future finish before terminating the JVM.

`RecordingRecovery.listRecoverySessions(outputDirectory)` lists unfinished sessions, including their audio-file availability and active OS-lock state. `recoverFromSession(session, ffmpeg)` preserves the original audio choice and fails if requested audio is unavailable or invalid. `recoverSilentFromSession(session, ffmpeg)` explicitly creates a video-only `-silent.mp4`. Both return a `CompletableFuture<Path>`, validate the result, choose a new UUID filename, and retain all source media. Recovery counts packets from the actual video fragment rather than trusting stale frame counts in session metadata. Active sessions cannot be recovered. The game UI must distinguish a recovered partial shot from a successfully completed original shot.

Session metadata records the playback speed, requested/elapsed real duration, and requested/written video duration separately. Old sessions without `playbackSpeed` mean 1×. Timelapse fragments already contain the shortened video timeline: recovery preserves their existing frame rate/timestamps and remains silent; it never applies the speed a second time.

## Windows helper protocol, version 1

Invocation (each path is a single ProcessBuilder argument):

```text
helper.exe --pid <Java process id> --output <session>/audio.wav --metadata <session>/audio.json
```

UTF-8 line protocol, flushed after every line. Standard error is diagnostics only.

| Direction | Line | Meaning |
| --- | --- | --- |
| Helper → Java | `READY` | Process-specific application loopback initialized |
| Java → helper | `PING <id>` | Clock correspondence query |
| Helper → Java | `PONG <id> <nativeNanos>` | Monotonic QPC converted to nanoseconds |
| Java → helper | `START` | Begin PCM capture |
| Helper → Java | `STARTED <nativeNanos>` | Capture has started |
| Java → helper | `STOP` | Stop, flush WAV, write metadata |
| Helper → Java | `STOPPED` | Files closed; exit with status 0 |
| Helper → Java | `ERROR <message>` | Fatal error; exit nonzero |

EOF on command input should also stop capture. Java measures five clock-query round trips and keeps the midpoint offset of the fastest. It does not assume that QPC nanoseconds and `System.nanoTime()` share an origin. Native audio can start before the first video frame; the finalizer trims that lead. Audio that starts later is preceded by silence.

The WAV must contain PCM16, 48 kHz, stereo. The helper fills capture gaps with silence and records timestamps corresponding to sample boundaries, using the same monotonic clock as PONG. `audio.json`:

```json
{
  "sampleRate": 48000,
  "channels": 2,
  "totalFrames": 480000,
  "firstSampleNanos": 10000000000,
  "lastSampleEndNanos": 20000000000
}
```

`totalFrames` counts stereo sample frames, not individual channel samples. A process producing no sound must still yield a timed silent track. The finalizer compares the sample count with the measured capture span, applies a small `atempo` correction, then trims/delays/pads to video duration. A mismatch above 2% fails with retained diagnostics, rather than hiding lost samples. Hardware timestamp errors should be reported in metadata diagnostics. Only endpoint/process isolation and a real pulse test can establish game-audio behavior and measured A/V synchronization.

## Tests

Unit tests run normally. Set `WACAMERA_TEST_FFMPEG` to run real encoder and recovery tests. Set `WACAMERA_TEST_AUDIO_HELPER` for silent process-capture/AAC-track integration. All normal tests avoid playback. The historical audible tone test additionally requires `WACAMERA_ALLOW_AUDIBLE_TEST=true`; do not set it when silent testing is requested. Synthetic pulse tests write WAV files directly and decode the resulting AAC without using a playback device.

Set `WACAMERA_LONG_TEST_SECONDS=600` for an opt-in 720p30 ten-minute soak, optionally with a silent process-scoped AAC track. It changes source pixels every frame, checks output frame count and heap growth, and retains the MP4 plus `build/long-recording-test/latest-report.json`. It never opens a playback device. Silent audio cannot establish real audible A/V synchronization or unrelated-process exclusion; those remain separate acceptance tests.
