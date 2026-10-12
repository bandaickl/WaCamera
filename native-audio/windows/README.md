# Windows process audio helper

Requires Windows build 20348 or newer (initial target: Windows 11 x64).
Build with Visual Studio C++ tools and Windows SDK 10.0.20348+:

```powershell
./native-audio/windows/build.ps1
```

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
