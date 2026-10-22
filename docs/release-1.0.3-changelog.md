# WaCamera 1.0.3

2026-10-23

## English

This update removes the bundled Windows audio helper executable from both mod JARs. The mod no longer extracts or automatically updates it, and no helper downloader is added.

- New settings default to silent recording. Optional game audio at 1× requires a separately prepared helper whose absolute executable path is entered under **Recording → Windows audio helper executable**.
- Existing audio preferences, configured helper paths, and helper files installed by older versions are retained. With game audio enabled, a missing or invalid helper produces an error; recording does not silently fall back to no audio.
- Time-lapse above 1× remains silent. FFmpeg and ffprobe remain separately obtained tools, and **Prepare FFmpeg** works as before.
- Standard mod builds need JDK 17 and 21, without a native helper build or Visual Studio C++ tools. Use `./scripts/build.ps1 -Release -SkipTests` for assembly without tests. `-SkipNative` remains a compatibility option with no effect. The optional helper can still be built separately from its source.

Camera features, recovery-data deletion, supported Minecraft/loader versions, and shader compatibility scope are unchanged. NeoForge shader rendering and performance remain unverified in-game; active shaders with Distant Horizons remain unsupported there.

**Validation:** Both loader assemblies and Forge's `reobfJar` succeeded with `-Release -SkipTests`. Read-only inspection confirmed version 1.0.3, required classes, translations and notices, with no bundled native executables or nested archives detected. No Minecraft launch, automated tests, audio tests, or actual recording were performed. Local JARs and SHA-256 hashes are available under `dist/1.0.3/`. The published GitHub release remains **v1.0**, which includes the older bundled helper; no 1.0.3 GitHub release has been created. This change does not guarantee CurseForge approval.

## 日本語

両MODのJARからWindows音声ヘルパーEXEを除去しました。MODによる展開・自動更新は行わず、新たな音声ヘルパーのダウンロード機能も追加しません。

- 新規設定は無音です。等倍でゲーム音を録る場合は、利用者がヘルパーを別途用意し、**「録画設定」→「Windows音声ヘルパーの実行ファイル」**へ絶対パスを入力します。
- 既存の音声設定・ヘルパーパス・旧版で配置済みのヘルパーは維持します。音声有効時にヘルパーが見つからない／無効な場合は明確なエラーを表示し、勝手に無音へ切り替えません。
- 1倍を超える録画は従来どおり無音です。FFmpeg・ffprobeは引き続きJARに含めず、**「FFmpegを準備」**の動作は変更しません。
- 通常のMODビルドはJDK 17と21で行え、ネイティブビルドやVisual Studio C++ツールは不要です。テストなしのassembleは`./scripts/build.ps1 -Release -SkipTests`で実行します。`-SkipNative`は旧コマンド互換として残すだけで、動作に影響しません。音声ヘルパーが必要な場合は、別途ソースからビルドできます。

カメラ機能、回復データ削除、Minecraft・ローダーの対応範囲、シェーダー対応範囲は変更しません。NeoForgeのシェーダー映像・性能は実機未検証で、シェーダー有効時のDistant Horizons併用も引き続き未対応です。

**確認範囲：** `-Release -SkipTests`で両版のassembleとForgeのreobfJarが成功しました。両JARの1.0.3メタデータ・必要クラス・翻訳・ライセンス文書を読み取り確認し、ネイティブ実行ファイルと入れ子アーカイブは検出されませんでした。Minecraft起動・自動テスト・音声テスト・実録画は行っていません。両JARとSHA-256一覧を`dist/1.0.3/`へ配置しました。GitHubの公開Releaseは音声ヘルパーを同梱した旧版の**v1.0**を維持し、1.0.3のReleaseは作成していません。この変更によるCurseForgeの承認を保証するものではありません。
