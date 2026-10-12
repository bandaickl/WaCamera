# External components

WaCamera's camera, path, recorder, UI and process audio helper are implemented in this repository and published under the [MIT License](LICENSE). Flashback and Camera Studio code are not bundled. External components have their own license information, described below.

## Runtime and development dependencies

- Minecraft, Forge and NeoForge are development/runtime dependencies, not bundled game distributions. Refer to their respective licenses and mapping notices.
- Gson is supplied by the Minecraft runtime. [Gson license](https://github.com/google/gson/blob/main/LICENSE) (Apache-2.0).
- Mixin is supplied by the loader. [Mixin license](https://github.com/SpongePowered/Mixin/blob/master/LICENSE.txt) (MIT).
- LWJGL/OpenGL are supplied by the runtime. [LWJGL license](https://www.lwjgl.org/license).
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

## Windows audio helper

WaCamera's own process-audio helper is built from `native-audio/windows/` and included in the mod JAR as `native/windows-x86_64/wacamera-audio.exe`. It uses documented process-loopback APIs and the Microsoft C++ runtime linked by the build toolchain. Reference: [Microsoft application loopback](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/applicationloopbackaudio-sample/).
