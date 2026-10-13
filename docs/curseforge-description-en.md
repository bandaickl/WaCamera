# WaCamera — play normally, record from another camera

WaCamera records your Minecraft world directly to MP4 from an independent camera while you keep playing in first person. Build, move, and interact normally: your hands and HUD stay on your screen, while the recording can include your own character.

WaCamera focuses on placing cameras and recording, without a complex timeline. **The 1.0.2 source update lets you delete selected interrupted-recording recovery data after confirmation on both loaders.** It also includes the NeoForge shader-recording implementation added in 1.0.1. The published GitHub release is still 1.0; a 1.0.2 release has not been published.

## Requirements

Install the matching **client-only** JAR in your instance’s `mods` folder. No server installation is required. Replace any older WaCamera JAR; install only one version.

| Minecraft | Required loader | Java |
| --- | --- | --- |
| 1.20.1 | Forge 47.4.10 or newer within 47.x | 17 |
| 1.21.1 | NeoForge 21.1.251 or newer within 21.1.x | 21 |

The supported recording environment is **Windows 11 x64**. MP4 recording needs FFmpeg and ffprobe. **Prepare FFmpeg** downloads and verifies the Windows tools on first use, requiring an internet connection. Alternatively, enter an existing FFmpeg executable; keep ffprobe beside it.

## Quick start

1. Enter a world and press **K** to open the camera panel.
2. In **Points**, use **Use current view** to set the selected point, or enter lens XYZ, yaw, and pitch. Close the panel, move, and use **Add view** for more points. One point gives a fixed camera; multiple points make a moving shot.
3. In **Shot**, set the duration and countdown. In **Look**, choose **Look: point directions**, **Look: at me**, or **Look: at coordinates**.
4. In **Recording**, choose quality and audio. Start with **720p30**; prepare FFmpeg if needed.
5. Use **Preview** to check the shot, then **Record camera** to record while playing. Press **F8** to stop early. Both keys are rebindable. Preview temporarily replaces your view; recording keeps your normal view.

Use up to 64 points, straight or smooth movement, adjustable FOV, and 1–3,600 seconds per shot. Outputs are 720p24, 720p30, 1080p24, 1080p30, and 1080p60.

## Reuse shots and make time-lapses

In **Patterns**, choose one of ten slots, **A–J**, and press **Save to slot**. To restore it, select the slot and press **Load slot**: selecting alone does not load anything. Confirm replacement when prompted. Loading restores camera settings without moving your player or starting recording. Quality, audio, and FFmpeg settings remain separate.

Set **Playback speed** from **1–120×**, including decimals. At 24fps and 10×, the camera captures **2.4 frames per real second**; 60 seconds of shooting produces about 6 seconds of video. Game speed stays unchanged. Above 1×, recordings are silent; your audio preference is retained. At 1×, optional game audio captures Minecraft’s playback, not your microphone.

- `/wac cam` appends the current view using the next number.
- `/wac cam N` sets or replaces point N; numbers must be consecutive.
- `/wac goto N` requests teleportation to that point and orientation; cheats/server teleport permission are required.

Files live under your game folder: videos in `wacamera/recordings/`, slots in `wacamera/patterns/`, and **Shot → Save shot** presets in `wacamera/shots/`.

## Manage interrupted recordings

Press **K → Recovery** to select an interrupted recording and try recovering it. In 1.0.2, you can select unwanted recovery data, press **Delete this recovery data**, then confirm with **Permanently delete**. This removes the selected recording's video fragments, audio, metadata, logs, and temporary files from failed recovery attempts. Deletion cannot be undone, and the deleted data can no longer be used for recovery.

Completed MP4 files, other recovery entries, camera patterns, and saved shots are kept. Data in use cannot be deleted. If deletion fails, the error is shown so you can resolve the cause and retry. There is no automatic or bulk deletion.

## Compatibility and limits

Camera markers appear only in your play view, never in the video. Recording continues when switching windows. An actual game pause or leaving the world ends recording; non-pausing chat and inventory screens do not.

Forge shader recording was tested in earlier releases with **Embeddium 0.3.31, Oculus 1.8.0, and Complementary Unbound r5.6**. Other combinations are not guaranteed.

**1.0.1 ports independent shader recording to NeoForge**, targeting **Iris 1.8.12 and Sodium 0.6.13 for Minecraft 1.21.1**. This scope is unchanged in 1.0.2. The actual JAR APIs were checked against source code. At the user's request, no Minecraft launch, automated tests, or actual recording were performed for 1.0.1 or this 1.0.2 update. NeoForge rendering results and performance remain unverified. **Active shaders together with Distant Horizons are unsupported on NeoForge and recording is refused with an explanation.** Iris and Sodium are needed only when using this shader integration; neither is required for ordinary recording without shaders.

Only loaded terrain can be filmed. Rendering two views increases GPU load, including in the background. WaCamera records finished video; it does not provide replays or let you change the viewpoint afterward.
