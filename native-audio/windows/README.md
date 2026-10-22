# Windows process audio helper

This is an **optional, separately built** helper for 1× game-audio recording. Starting with WaCamera 1.0.3, mod JARs do not contain its executable, and the mod does not extract, download or automatically update it. Building the Java mod requires no native helper or C++ tools.

Requires Windows build 20348 or newer (supported recording target: Windows 11 x64).

## Build and configure

To build the helper from this source, install Visual Studio C++ tools and Windows SDK 10.0.20348+, then run from the repository root:

```powershell
./native-audio/windows/build.ps1
```

This script compiles and runs the C++ timeline checks, then writes `native-audio/windows/build/wacamera-audio.exe`. It is separate from `scripts/build.ps1` and its `-SkipTests` option.

1. Keep the executable in a stable location of your choice.
2. In Minecraft, open **K → Recording** and enter its **absolute path** under **Windows audio helper executable**.
3. Choose **Game audio: ON** and **Playback speed: 1** for game audio. New settings default to **Game audio: OFF**.

Existing configured paths and helpers installed by older versions remain usable; updating the mod does not replace or delete those files. If game audio is enabled but the helper is missing or invalid, recording reports an error instead of silently recording without sound. Above 1×, recording remains silent and retains the saved audio preference.

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
