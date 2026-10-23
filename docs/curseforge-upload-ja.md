# WaCamera 1.0.4 — CurseForgeへの登録

1.0.4を今後登録する場合の準備資料です。今回の作業はソース更新とローカル配布物の準備までです。CurseForgeへの公開や1.0.4のGitHub Release作成は行わず、GitHubの公開済みReleaseは音声ヘルパーを同梱した旧版の1.0を維持します。assembleとJAR内容を確認し、ローカル配布物を`dist/1.0.4/`へ配置しました。

## プロジェクト情報

- Project name：`WaCamera`
- Summary（英語）：`Record yourself from a separate camera while you play. Simple camera paths, saved presets, time-lapse, and direct MP4 capture.`
- Summary（日本語参考）：`一人称で遊びながら別カメラで自分を撮影。座標指定・10パターン保存・倍速録画に対応し、MP4へ直接保存します。`
- Description：`curseforge-description-en.md`を使用。日本語版は`curseforge-description-ja.md`。併記用の`curseforge-description-bilingual.md`は英語を先に配置しています。
- Logo：`wacamera-logo.png`（正方形PNG）
- License：既存のMODメタデータと同じ`MIT License`

一覧に表示される短い説明はSummary欄、操作方法を含む本文はDescription欄へ入れます。登録時はロゴと説明文を[公式登録ガイド](https://support.curseforge.com/support/solutions/articles/9000199552-project-submission-guide-and-tips)および下記の現行審査ポリシーに照らして確認してください。

## 2つのJARを別々のファイルとして登録

| 項目 | Forge版 | NeoForge版 |
| --- | --- | --- |
| Upload file | `wacamera-forge-1.20.1-1.0.4.jar` | `wacamera-neoforge-1.21.1-1.0.4.jar` |
| Display name | WaCamera 1.0.4 — Forge 1.20.1 | WaCamera 1.0.4 — NeoForge 1.21.1 |
| Game version | 1.20.1 | 1.21.1 |
| Mod loader | Forge | NeoForge |
| Java | 17 | 21 |
| Environment | Client | Client |
| Release type | 公開時に選択 | 実録画未検証のためBetaを検討 |
| 必要なローダー | Forge 47.4.10以上・48未満 | NeoForge 21.1.251以上・21.2未満 |

Release Typeは実際に公開するときに選びます。JAR内のバージョンを1.0.4にしただけでは、このサーバー側の公開区分は設定されません。新しいJNA音声方式とNeoForgeのシェーダー録画は実動作未検証なので、その状態を説明文へ明記し、公開区分はBetaも検討してください。[公式アップロード説明](https://support.curseforge.com/support/solutions/articles/9000197241-creating-and-submitting-a-project)

Changelogには`release-1.0.4-changelog.md`の対応する言語の本文を使えます。公開時には確認済みのビルド結果を反映し、ビルド確認とゲーム内動作・実録画の検証を区別してください。配布準備用ZIPを使う場合も、展開した各JARを個別にアップロードします。ロゴ・説明文・ZIPそのものをMODのJARとしてアップロードしません。

クライアント側だけに導入します。Forge版とNeoForge版を同じインスタンスへ同時に入れないでください。ForgeのOculus・Embeddium、NeoForgeのIris・Sodiumはシェーダーを使う場合の追加構成であり、通常録画の必須MODではありません。1.0.1のNeoForge移植対象はMinecraft 1.21.1用Iris 1.8.12＋Sodium 0.6.13です。実JAR API・ソースは照合済みですが、ゲーム起動・テスト・実録画は未実施です。NeoForgeのシェーダー有効＋Distant Horizons併用は未対応として録画開始を拒否します。この確認範囲をDescriptionとChangelogに残してください。

FFmpeg・ffprobe・Windows音声ヘルパーEXEはJARに同梱しません。1.0.4の音声はMinecraftに既に含まれるJNAからWindows標準のWASAPI process-loopbackへ接続するため、音声ヘルパーの別途導入・パス指定は不要です。音声用の新しいEXE・DLLの同梱やダウンロードも行いません。旧C++ソースは履歴参考として残しますが、利用者の導入手順には含めません。動画用の既存の「FFmpegを準備」は維持します。

## 審査について

1.0.4はEXE同梱に加えて音声ヘルパーの別途導入も不要にする変更ですが、承認を保証しません。現行の[Project and Modpack Moderation Policies](https://support.curseforge.com/support/solutions/articles/9000197279-project-and-modpack-moderation-policies)はThird Party Downloadsで外部ファイルへのダウンロードリンクを認めないとしています。既存のFFmpeg取得機能の扱いは審査側の判断が必要です。JNA音声への変更だけで承認済みとは扱わず、Minecraft既存JNA・Windows標準APIの利用と、別途残るFFmpeg機能を提出時に区別して説明してください。

## 今回の更新内容と確認範囲

1.0.4ではJavaからJNA経由でゲーム音を取得します。Windows 11 x64が対象で、Windows x64の新規設定は音声ON、既存のON/OFFは保持します。非対応環境や初期化失敗は明示エラーにします。旧ヘルパーパスは使わず、既存ファイルは自動削除しません。倍速は無音、旧音声回復形式は維持します。通常のMODビルドにC++ツールや音声ヘルパーは不要です。

回復データ削除とNeoForgeのシェーダー連携は従来の対応範囲を維持します。両版のassembleとForgeのreobfJarが成功し、JARの1.0.4メタデータ、新しい音声取得クラス、回復処理、日英翻訳、ライセンス文書を確認しました。ネイティブ実行ファイル・入れ子JAR・JNA本体クラスの同梱は検出されませんでした。利用者の希望に従い、Minecraft起動・ゲーム操作・自動テスト・実録音・実録画は行っていません。今回と過去の確認範囲は[検証記録](testing-ja.md)を参照してください。
