# External components

WaCamera's camera, path, recorder, UI and Windows audio integration are implemented in this repository and published under the [MIT License](LICENSE). External components have their own license information, described below.

## Runtime and development dependencies

- Minecraft, Forge and NeoForge are development/runtime dependencies, not bundled game distributions. Refer to their respective licenses and mapping notices.
- Gson is supplied by the Minecraft runtime. [Gson license](https://github.com/google/gson/blob/main/LICENSE) (Apache-2.0).
- Mixin is supplied by the loader. [Mixin license](https://github.com/SpongePowered/Mixin/blob/master/LICENSE.txt) (MIT).
- LWJGL/OpenGL are supplied by the runtime. [LWJGL license](https://www.lwjgl.org/license).
- JNA is supplied by Minecraft: version **5.12.1** for Minecraft 1.20.1 and **5.14.0** for Minecraft 1.21.1. WaCamera uses the existing JNA runtime to call Windows audio APIs; it does not bundle another JNA JAR or native library. JNA is dual-licensed under LGPL-2.1-or-later or Apache-2.0. [JNA 5.12.1 license](https://github.com/java-native-access/jna/blob/5.12.1/LICENSE), [JNA 5.14.0 license](https://github.com/java-native-access/jna/blob/5.14.0/LICENSE).
- Gradle Wrapper files originate from the official Forge and NeoForge MDKs and retain their Apache-2.0 notices.

## FFmpeg and ffprobe: separately obtained tools

WaCamera starts FFmpeg and ffprobe as separate processes to encode and inspect video. **Their executables are not embedded in the WaCamera mod JAR.** Users can select an existing FFmpeg installation, with ffprobe beside it or available on PATH.

On Windows x64, the optional **Prepare FFmpeg** button downloads the fixed **Gyan FFmpeg 9.0.2 essentials build**:

- [Distributor and build information](https://www.gyan.dev/ffmpeg/builds/)
- [Pinned ZIP archive](https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip)
- [Distributor's archive checksum](https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip.sha256)

The installer compares the downloaded ZIP with a SHA-256 value pinned in its source before extraction. It installs `bin/ffmpeg.exe`, `bin/ffprobe.exe`, and the archive's original `LICENSE.txt` and `README.txt` under the game folder's `wacamera/tools/ffmpeg-9.0.2-gyan-essentials/`. It also writes `ORIGIN.txt` with the version, download URL, checksum, distributor, and source/build information links.

The selected distribution identifies its build as **GPLv3**, and its included LICENSE contains the GNU GPL version 3. The installer preserves that document; it does not replace it with WaCamera's MIT License. Refer to the downloaded LICENSE/README, [Gyan's build information](https://www.gyan.dev/ffmpeg/builds/), and [FFmpeg's license information](https://ffmpeg.org/legal.html) for the external build.

`ORIGIN.txt` records the distributor's [FFmpeg source revision](https://github.com/FFmpeg/FFmpeg/commit/946fcce07b) and [build archive/discussion](https://github.com/GyanD/codexffmpeg). WaCamera's installer does not download a corresponding-source archive or claim that the FFmpeg revision alone contains every library used by that build. This document describes the integration and retained notices; it does not establish that every possible redistribution of these tools satisfies its applicable licenses.

## Windows audio through the existing JNA runtime

Starting with **1.0.4**, WaCamera calls the Windows **WASAPI process-loopback** APIs from Java through Minecraft's existing JNA runtime. The supported audio environment is **Windows 11 x64**. No audio-helper executable or executable-path setting is needed, and WaCamera does not bundle or download an additional EXE or DLL for this audio integration. JNA still uses native code already supplied by Minecraft; this is not a claim that the complete recording system is pure Java.

New Windows x64 settings enable game audio by default; an existing user's on/off choice is preserved. Audio above 1× playback speed remains disabled without changing that preference. When audio is requested on an unsupported environment or initialization fails, recording reports an error instead of silently falling back to whole-system audio or silent recording. FFmpeg and ffprobe remain separate tools as described above.

The former helper's MIT-licensed source remains in [`native-audio/windows/`](native-audio/windows/README.md) as historical reference. Current WaCamera does not use the helper, its old path setting, or automatically delete existing helper files. Previously saved audio recovery metadata remains supported by the recovery format.

API reference: [Microsoft application loopback](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/applicationloopbackaudio-sample/). The 1.0.4 integration has not been verified by running Minecraft or making a real audio recording in this update.
