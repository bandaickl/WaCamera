# WaCamera 1.0.4

2026-10-23

## English

Game audio now uses Windows WASAPI process-loopback directly from Java through JNA already supplied by Minecraft: 5.12.1 on Minecraft 1.20.1 and 5.14.0 on Minecraft 1.21.1.

- No audio-helper EXE or helper-path entry is needed. WaCamera does not bundle or download an additional EXE or DLL for audio capture. The supported audio environment is Windows 11 x64.
- New Windows x64 settings enable game audio. Existing ON/OFF choices are preserved, including OFF saved by 1.0.3. Unsupported platforms or initialization failures report an error without silently switching to whole-system capture or silent recording.
- Above 1× playback speed, recordings remain silent and retain the saved audio preference. FFmpeg and ffprobe are still required separately; their existing preparation and encoding workflow is unchanged.
- Old helper path values are unused, and existing helper files are not automatically deleted. The old C++ source remains as historical reference, not a current installation requirement. Existing audio recovery metadata remains supported.

Camera features, recovery-data deletion, Minecraft/loader requirements, and shader compatibility scope are unchanged. NeoForge Iris 1.8.12 + Sodium 0.6.13 rendering remains unverified in-game; active shaders with Distant Horizons remain unsupported there.

**Validation:** Both loader assemblies and Forge's `reobfJar` succeeded with `-Release -SkipTests`. Read-only package inspection confirmed version 1.0.4, the new audio classes, recovery code, translations, and license notices. No native executables, nested JARs, or bundled `com/sun/jna/` classes were detected. At the user's request, no Minecraft launch, automated tests, actual audio capture, or recording was performed. Real audio behavior and synchronization for the JNA implementation remain unverified. Local JARs and SHA-256 hashes are available under `dist/1.0.4/`. The published GitHub release remains **v1.0**, which includes the older helper; no 1.0.4 GitHub release has been created. This change does not guarantee CurseForge approval.

## 日本語

ゲーム音の取得を、Minecraftに既に含まれるJNAからJava経由でWindows WASAPI process-loopbackへ接続する方式に変更します。使用するJNAはMinecraft 1.20.1で5.12.1、1.21.1で5.14.0です。

- 音声ヘルパーEXEの準備とヘルパーパス入力は不要です。音声取得用の新しいEXE・DLLを同梱・ダウンロードしません。音声の対応環境はWindows 11 x64です。
- Windows x64の新規設定はゲーム音ONです。1.0.3で保存したOFFを含め、既存のON/OFFは保持します。非対応環境や初期化失敗は理由を表示し、PC全体の音や無音へ自動的に切り替えません。
- 1倍を超える録画は従来どおり無音で、元の音声設定を保持します。FFmpeg・ffprobeは引き続き別途必要で、準備・エンコードの仕組みは変更しません。
- 旧ヘルパーパスは使用せず、配置済みの実ファイルは自動削除しません。旧C++ソースは履歴参考として残しますが、現行の導入には不要です。旧音声付き回復データのメタデータ形式も維持します。

カメラ機能、回復データ削除、Minecraft・ローダーの対応範囲、シェーダー対応範囲は変更しません。NeoForgeのIris 1.8.12＋Sodium 0.6.13の描画結果は実機未検証で、シェーダー有効時のDistant Horizons併用も引き続き未対応です。

**確認範囲：** `-Release -SkipTests`で両版のassembleとForgeのreobfJarが成功しました。両JARの1.0.4メタデータ、新しい音声取得クラス、回復処理、翻訳、ライセンス文書を読み取り確認しました。ネイティブ実行ファイル、入れ子JAR、`com/sun/jna/`クラスの同梱は検出されませんでした。利用者の希望によりMinecraft起動・自動テスト・実録音・実録画は行っていません。JNA方式の取得音・同期精度は未検証です。両JARとSHA-256一覧を`dist/1.0.4/`へ配置しました。GitHubの公開Releaseは音声ヘルパーを同梱した旧版の**v1.0**を維持し、1.0.4のReleaseは作成していません。この変更によるCurseForgeの承認を保証するものではありません。
