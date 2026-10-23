# WaCamera — play normally, record from another camera

WaCamera records your Minecraft world directly to MP4 from an independent camera while you keep playing in first person. Build, move, and interact normally: your hands and HUD stay on your screen, while the recording can include your own character.

WaCamera focuses on placing cameras and recording, without a complex timeline. **Version 1.0.4 records game audio through Minecraft's existing JNA runtime and Windows audio APIs, without preparing an audio-helper EXE.** It retains recovery-data deletion and the NeoForge shader integration from earlier updates. The published GitHub release is still 1.0, which includes the older bundled helper; a 1.0.4 release has not been published.

## Requirements

Install the matching **client-only** JAR in your instance’s `mods` folder. No server installation is required. Replace any older WaCamera JAR; install only one version.

| Minecraft | Required loader | Java |
| --- | --- | --- |
| 1.20.1 | Forge 47.4.10 or newer within 47.x | 17 |
| 1.21.1 | NeoForge 21.1.251 or newer within 21.1.x | 21 |

The supported recording environment is **Windows 11 x64**. MP4 recording needs FFmpeg and ffprobe, which are not bundled in the JAR. **Prepare FFmpeg** downloads and verifies the Windows tools on first use, requiring an internet connection. Alternatively, enter an existing FFmpeg executable; keep ffprobe beside it.

## Quick start

1. Enter a world and press **K** to open the camera panel.
2. In **Points**, use **Use current view** to set the selected point, or enter lens XYZ, yaw, and pitch. Close the panel, move, and use **Add view** for more points. One point gives a fixed camera; multiple points make a moving shot.
3. In **Shot**, set the duration and countdown. In **Look**, choose **Look: point directions**, **Look: at me**, or **Look: at coordinates**.
4. In **Recording**, choose quality. Start with **720p30**; new Windows x64 settings use **Game audio: ON**, while existing preferences are preserved. Prepare FFmpeg if needed.
5. Use **Preview** to check the shot, then **Record camera** to record while playing. Press **F8** to stop early. Both keys are rebindable. Preview temporarily replaces your view; recording keeps your normal view.

Use up to 64 points, straight or smooth movement, adjustable FOV, and 1–3,600 seconds per shot. Outputs are 720p24, 720p30, 1080p24, 1080p30, and 1080p60.

## Reuse shots and make time-lapses

In **Patterns**, choose one of ten slots, **A–J**, and press **Save to slot**. To restore it, select the slot and press **Load slot**: selecting alone does not load anything. Confirm replacement when prompted. Loading restores camera settings without moving your player or starting recording. Quality, audio, and FFmpeg settings remain separate.

Set **Playback speed** from **1–120×**, including decimals. At 24fps and 10×, the camera captures **2.4 frames per real second**; 60 seconds of shooting produces about 6 seconds of video. Game speed stays unchanged. Above 1×, recordings are silent; your audio preference is retained.

## Optional game audio

On **Windows 11 x64**, select **Recording → Game audio: ON** and use 1× playback speed. Version 1.0.4 calls Windows WASAPI process-loopback through JNA already supplied by Minecraft. No audio-helper EXE or helper-path entry is needed, and no additional EXE or DLL is bundled or downloaded for audio. **FFmpeg and ffprobe are still needed** for video encoding and validation.

New Windows x64 settings enable audio. Your existing ON/OFF choice is preserved, including OFF saved by 1.0.3. Old helper paths are unused, and old helper files are not automatically deleted. Capture targets Minecraft's playback, not your microphone or other applications. Audio requested on a non-Windows or non-x64 environment, or an audio initialization failure, produces an error; it does not silently switch to whole-system capture or no audio. Above 1×, recordings remain silent and retain your audio preference. Existing audio recovery metadata remains supported. The new audio integration has not been verified by launching Minecraft or making an actual recording in this update.

## Commands and files

- `/wac cam` appends the current view using the next number.
- `/wac cam N` sets or replaces point N; numbers must be consecutive.
- `/wac goto N` requests teleportation to that point and orientation; cheats/server teleport permission are required.

Files live under your game folder: videos in `wacamera/recordings/`, slots in `wacamera/patterns/`, and **Shot → Save shot** presets in `wacamera/shots/`.

## Manage interrupted recordings

Press **K → Recovery** to select an interrupted recording and try recovering it. Since 1.0.2, you can select unwanted recovery data, press **Delete this recovery data**, then confirm with **Permanently delete**. This removes the selected recording's video fragments, audio, metadata, logs, and temporary files from failed recovery attempts. Deletion cannot be undone, and the deleted data can no longer be used for recovery.

Completed MP4 files, other recovery entries, camera patterns, and saved shots are kept. Data in use cannot be deleted. If deletion fails, the error is shown so you can resolve the cause and retry. There is no automatic or bulk deletion.

## Compatibility and limits

Camera markers appear only in your play view, never in the video. Recording continues when switching windows. An actual game pause or leaving the world ends recording; non-pausing chat and inventory screens do not.

Forge shader recording was tested in earlier releases with **Embeddium 0.3.31, Oculus 1.8.0, and Complementary Unbound r5.6**. Other combinations are not guaranteed.

**1.0.1 ports independent shader recording to NeoForge**, targeting **Iris 1.8.12 and Sodium 0.6.13 for Minecraft 1.21.1**. This scope is unchanged in 1.0.4. The actual JAR APIs were checked against source code. No Minecraft launch, automated tests, or actual recording have been performed for the 1.0.1–1.0.4 updates. NeoForge rendering results and performance remain unverified. **Active shaders together with Distant Horizons are unsupported on NeoForge and recording is refused with an explanation.** Iris and Sodium are needed only when using this shader integration; neither is required for ordinary recording without shaders.

Only loaded terrain can be filmed. Rendering two views increases GPU load, including in the background. WaCamera records finished video; it does not provide replays or let you change the viewpoint afterward.

---

# WaCamera — 普段どおり遊びながら、別のカメラで録画

WaCameraは、**一人称でMinecraftを操作しながら、独立したカメラの映像をMP4へ保存するMOD**です。建築・移動・ブロック操作を続けられ、操作画面には手やHUDを表示したまま、動画には自分のキャラクターも映せます。

地点を決めて気軽に撮る使い方を目指しています。複雑な編集タイムラインはありません。**1.0.4ではMinecraftに含まれるJNAとWindows標準の音声APIを使うため、音声ヘルパーEXEの準備は不要です。** 以前の更新で追加した回復データ削除とNeoForgeのシェーダー録画処理も維持します。GitHubの公開Releaseは音声ヘルパーを同梱した旧版の1.0で、1.0.4のReleaseはまだ公開していません。

## 必要な環境

対応するJARを、使用するインスタンスの`mods`フォルダーへ入れてください。**クライアント専用**なので、サーバーへの導入は不要です。更新時は旧WaCameraのJARを外し、新しいJARを一つだけ入れます。

| Minecraft | 必要なローダー | Java |
| --- | --- | --- |
| 1.20.1 | Forge 47.4.10以上の47.x系 | 17 |
| 1.21.1 | NeoForge 21.1.251以上の21.1.x系 | 21 |

録画の対応環境は**Windows 11 x64**です。MP4の作成にはFFmpegとffprobeが必要で、JARには同梱していません。初回に**「FFmpegを準備」**を押すと、Windows用ツールを取得・検証します。この取得にはインターネット接続が必要です。導入済みのFFmpeg実行ファイルを指定することもでき、その場合は同じフォルダーにffprobeを置いてください。

## 最初の撮影

1. ワールド内で**K**を押して撮影パネルを開きます。
2. **「撮影地点」→「今の視点に更新」**で選択中の地点を設定するか、レンズのXYZ・左右／上下の向きを入力します。別の地点を追加するときは、パネルを閉じて移動し、再度開いて**「今の視点を追加」**を押します。一地点なら固定、複数地点なら移動撮影です。
3. **「ショット」**で撮影時間と開始までの秒数を設定します。**「視線」**は「地点に登録した向き」「自分を向く」「指定座標を見る」から選べます。
4. **「録画設定」**で画質を選び、必要ならFFmpegを準備します。最初は**720p30**から試してください。Windows x64の新規設定は**「ゲーム音：あり」**で、既存のON/OFFは維持します。
5. **「プレビュー」**で構図を確認し、**「別視点で録画」**を開始します。途中停止は**F8**です。キー割り当ては変更できます。プレビュー中だけ撮影カメラの視点へ切り替わり、録画中は通常の操作視点を維持します。

撮影地点は最大64個。直線・なめらかな移動、視野角の調整、1〜3,600秒の撮影に対応します。出力画質は720p24／720p30／1080p24／1080p30／1080p60です。

## 構図の保存とタイムラプス

**「パターン」**のA〜J、10個の枠へ撮影設定を保存できます。枠を選んで**「この枠に保存」**を押してください。再利用するときは、枠を選んで**「この枠を呼び出す」**を押します。**枠を選ぶだけでは呼び出されません。** 上書き・置き換えの確認内容も確認してください。呼び出しは撮影設定の復元であり、本人の移動や録画開始は行いません。画質・音声・FFmpegの場所はパターンとは別の設定です。

**「録画倍率（倍）」**は1〜120倍で、小数も指定できます。24fps・10倍なら、実時間1秒につき**2.4枚**を撮影。60秒の撮影から約6秒の動画を作ります。ゲームの進む速さは変わりません。1倍を超える録画は無音になり、元の音声設定は保持します。

## ゲーム音を付ける場合

**Windows 11 x64**で、**「録画設定」→「ゲーム音：あり」**を選び、録画倍率を1にします。1.0.4はMinecraftに既に含まれるJNAからWindowsのWASAPI process-loopbackへ接続します。音声ヘルパーEXEやヘルパーパス入力は不要で、この音声機能用の新しいEXE・DLLを同梱・ダウンロードしません。**動画用のFFmpeg・ffprobeは引き続き必要です。**

Windows x64の新規設定は音声ONですが、1.0.3で保存したOFFを含め、既存のON/OFFは維持します。旧ヘルパーのパスは使わず、実ファイルも自動削除しません。Minecraftの再生音が対象で、マイクや別アプリを録る機能ではありません。非Windows・非x64や音声初期化の失敗は理由を表示し、PC全体の音や無音へ自動的に切り替えません。1倍を超える録画は無音で、元の音声設定を保持します。旧音声付き回復データの形式も維持します。今回の新方式ではMinecraft起動・実録音・実録画を行っておらず、実動作は未検証です。

## コマンドと保存先

- `/wac cam`：今の視点を次の番号へ追加します。
- `/wac cam N`：地点Nを登録・上書きします。番号は飛ばさず順番に登録します。
- `/wac goto N`：地点Nの位置と向きへの移動をサーバーへ要求します。チート許可／サーバーのテレポート権限が必要です。

ゲームフォルダー内の`wacamera/recordings/`に動画、`wacamera/patterns/`にパターンを保存します。**「ショット」→「設定を保存」**による名前付きの保存先は`wacamera/shots/`です。

## 中断録画の回復データを整理する

**K →「回復」**で中断した録画を選ぶと、回復を試せます。1.0.2から、不要な対象を選び、**「この回復データを削除」→ 確認画面の「完全に削除」**で削除できます。選んだ録画の映像断片・音声・時刻情報・ログと、失敗した回復処理の一時データを削除します。削除は取り消せず、そのデータから回復できなくなります。

完成MP4、他の回復データ、カメラパターン、保存済みショット設定は残します。使用中のデータは削除できません。失敗時は理由を表示し、原因を解消して再試行できます。自動削除・一括削除はありません。

## 対応範囲と制限

カメラの位置・方向・画角の目印は操作画面だけに表示し、動画には入りません。別ウィンドウへ切り替えても録画を継続します。ゲームが実際に一時停止した場合やワールド退出時には終了・保存し、ゲームを止めないチャットやインベントリー画面では継続します。

Forgeのシェーダー録画は、過去の版で**Embeddium 0.3.31＋Oculus 1.8.0＋Complementary Unbound r5.6**を使って確認しています。他の組み合わせまで保証するものではありません。

**1.0.1でNeoForgeにも独立したシェーダー録画処理を移植しました。** 対象はMinecraft 1.21.1用の**Iris 1.8.12＋Sodium 0.6.13**で、1.0.4でも対応範囲は変わりません。実JARのAPIとソースを照合しましたが、1.0.1〜1.0.4の更新ではMinecraftの起動・自動テスト・実録画は行っていません。NeoForgeの描画結果・性能は未検証です。**NeoForgeのシェーダー有効＋Distant Horizons併用は未対応で、理由を表示して録画開始を拒否します。** Iris・Sodiumはこのシェーダー連携を使う場合だけ必要で、シェーダーなしの通常録画には不要です。

撮影できるのは読み込み済みの地形です。二つの視点を描くため、バックグラウンド録画中もGPU負荷が増えます。完成動画を直接保存する方式なので、リプレイ機能や撮影後の視点変更はありません。
