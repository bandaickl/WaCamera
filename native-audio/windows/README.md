# Historical Windows process audio helper

This source is retained as **historical reference**. **WaCamera 1.0.4 does not use this helper.** Current Windows 11 x64 audio capture calls WASAPI process-loopback from Java through Minecraft's existing JNA runtime. Users do not need to build or install an audio helper, and there is no helper-path field in the recording UI. See the current [recorder documentation](../../recorder/README.md).

Version 1.0.3 removed this executable from mod JARs. Version 1.0.4 also removes its use at runtime; it neither downloads nor runs the helper. Existing helper files and legacy path values are not automatically deleted. FFmpeg and ffprobe remain required separately for video encoding and validation.

Requires Windows build 20348 or newer (supported recording target: Windows 11 x64).

## Historical build procedure

For maintenance of the old helper source only, the original build requires Visual Studio C++ tools and Windows SDK 10.0.20348+. Its command, from the repository root, is:

```powershell
./native-audio/windows/build.ps1
```

This script compiles and runs the C++ timeline checks, then writes `native-audio/windows/build/wacamera-audio.exe`. It is separate from `scripts/build.ps1` and its `-SkipTests` option.

This procedure is not part of the current mod build or installation. No C++ build, timeline test, game launch, or audio capture was performed for the 1.0.4 update.

## Capture protocol

The helper captures only the specified PID and its child processes. It never falls back to whole-device audio. It requests PCM16 stereo at 48 kHz from Windows. Microsoft process-loopback APIs are used directly; no third-party native libraries are linked.

```text
wacamera-audio.exe --pid 1234 --output audio.wav --metadata audio.json
stdout: READY
stdin:  PING id
stdout: PONG id <QPC nanoseconds>
stdin:  START
stdout: STARTED <QPC nanoseconds>
stdin:  STOP
stdout: STOPPED
```

EOF also stops capture. Errors produce `ERROR <description>` and a nonzero exit code. File names must be unique; WAV is created with CREATE_NEW. Metadata reports sample rate, channels, sample frames and first/last capture timestamps in the same QPC nanosecond domain. Java must correlate this clock with its own monotonic clock, not assume identical epochs.

Reference: [Microsoft application loopback documentation](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/applicationloopbackaudio-sample/).
