# WaCamera 1.0

## English

WaCamera 1.0 is the first stable release. This release updates version branding and distribution packaging; its recording features carry forward from **0.1.0-alpha.7**.

Included features:

- Independent camera recording to MP4 while retaining normal first-person play, with your player visible in the recording.
- Up to 64 camera points with XYZ, yaw/pitch, straight or smooth paths, and configurable look direction.
- Ten named pattern slots, A–J, with explicit **Save to slot** and **Load slot** actions.
- 1–120× time-lapse recording, including decimal speeds; recordings above 1× are silent.
- Camera position/direction guides visible in the play view and excluded from video.
- Recording that continues while switching windows, plus `/wac cam` and `/wac goto` commands.
- Optional Windows game audio, FFmpeg preparation, and interrupted-recording recovery.

Requirements: Minecraft **1.20.1 / Forge 47.4.10+ within 47.x / Java 17**, or Minecraft **1.21.1 / NeoForge 21.1.251+ within 21.1.x / Java 21**. Install the matching client-only JAR. The supported recording environment is Windows 11 x64.

Forge retains the Oculus/Embeddium shader integration; this integration has not been ported to NeoForge. Existing compatibility limits still apply.

This version and packaging update includes no new gameplay changes or new round of in-game validation. Validation remains the previously documented alpha.7 results and limitations. Pattern-loading behavior is unchanged: select a pattern, then explicitly press **Load slot** to restore it.

## 日本語

WaCamera 1.0は最初の正式版です。バージョン表記と配布パッケージを更新し、録画機能は**0.1.0-alpha.7**から引き継いでいます。

収録する主な機能：

- 通常の一人称操作を維持し、自分も映る独立カメラの映像をMP4へ保存。
- XYZ・左右／上下の向き、直線／曲線の移動、視線を設定できる最大64地点。
- 任意名を付けられるA〜Jの10パターン。「この枠に保存」「この枠を呼び出す」で明示的に操作。
- 小数も指定できる1〜120倍のタイムラプス。1倍を超える録画は無音。
- 操作画面だけに表示し、動画には入らないカメラ位置・方向の目印。
- 別ウィンドウへ切り替えても続く録画と、`/wac cam`・`/wac goto`コマンド。
- Windowsのゲーム音収録、FFmpegの準備、未完了録画の回復。

必要な環境は、Minecraft **1.20.1／Forge 47.4.10以上の47.x系／Java 17**、またはMinecraft **1.21.1／NeoForge 21.1.251以上の21.1.x系／Java 21**です。対応するクライアント用JARを一つだけ導入してください。録画の対応環境はWindows 11 x64です。

ForgeのOculus／Embeddium対応は継続します。NeoForge版にはシェーダー録画処理を移植しておらず、既存の互換性の制限も変わりません。

今回のバージョン表記・配布パッケージの更新には、ゲーム内機能の変更や新たなゲーム内動作検証は含みません。検証範囲は既存のalpha.7の記録に基づきます。パターンの呼び出し動作も従来通りで、枠を選択した後に**「この枠を呼び出す」**を押す必要があります。
