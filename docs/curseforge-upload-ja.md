# WaCamera 1.0.2 — CurseForgeへの登録

1.0.2を今後登録する場合の準備資料です。今回の作業はGitHubのソース更新とローカル配布物の準備までです。CurseForgeへの公開や1.0.2のGitHub Release作成は行わず、GitHubの公開済みReleaseは1.0を維持します。ローカル配布物の準備先は`dist/1.0.2/`を予定しています。

## プロジェクト情報

- Project name：`WaCamera`
- Summary（英語）：`Record yourself from a separate camera while you play. Simple camera paths, saved presets, time-lapse, and direct MP4 capture.`
- Summary（日本語参考）：`一人称で遊びながら別カメラで自分を撮影。座標指定・10パターン保存・倍速録画に対応し、MP4へ直接保存します。`
- Description：`curseforge-description-en.md`を使用。日本語版は`curseforge-description-ja.md`。併記用の`curseforge-description-bilingual.md`は英語を先に配置しています。
- Logo：`wacamera-logo.png`（正方形PNG）
- License：既存のMODメタデータと同じ`MIT License`

一覧に表示される短い説明はSummary欄、操作方法を含む本文はDescription欄へ入れます。ロゴの最小サイズは400×400pxで、それより大きい正方形PNGはCurseForge側で縮小されます。[公式登録ガイド](https://support.curseforge.com/support/solutions/articles/9000199552-project-submission-guide-and-tips)

## 2つのJARを別々のファイルとして登録

| 項目 | Forge版 | NeoForge版 |
| --- | --- | --- |
| Upload file | `wacamera-forge-1.20.1-1.0.2.jar` | `wacamera-neoforge-1.21.1-1.0.2.jar` |
| Display name | WaCamera 1.0.2 — Forge 1.20.1 | WaCamera 1.0.2 — NeoForge 1.21.1 |
| Game version | 1.20.1 | 1.21.1 |
| Mod loader | Forge | NeoForge |
| Java | 17 | 21 |
| Environment | Client | Client |
| Release type | 公開時に選択 | 実録画未検証のためBetaを検討 |
| 必要なローダー | Forge 47.4.10以上・48未満 | NeoForge 21.1.251以上・21.2未満 |

Release Typeは実際に公開するときに選びます。JAR内のバージョンを1.0.2にしただけでは、このサーバー側の公開区分は設定されません。NeoForgeのシェーダー録画は引き続き実録画未検証なので、その状態を説明文へ明記し、公開区分はBetaも検討してください。[公式アップロード説明](https://support.curseforge.com/support/solutions/articles/9000197241-creating-and-submitting-a-project)

Changelogには`release-1.0.2-changelog.md`の対応する言語の本文を使えます。公開時には確認済みのビルド結果を反映し、ビルド確認とゲーム内動作・実録画の検証を区別してください。配布準備用ZIPを使う場合も、展開した各JARを個別にアップロードします。ロゴ・説明文・ZIPそのものをMODのJARとしてアップロードしません。

クライアント側だけに導入します。Forge版とNeoForge版を同じインスタンスへ同時に入れないでください。ForgeのOculus・Embeddium、NeoForgeのIris・Sodiumはシェーダーを使う場合の追加構成であり、通常録画の必須MODではありません。1.0.1のNeoForge移植対象はMinecraft 1.21.1用Iris 1.8.12＋Sodium 0.6.13です。実JAR API・ソースは照合済みですが、ゲーム起動・テスト・実録画は未実施です。NeoForgeのシェーダー有効＋Distant Horizons併用は未対応として録画開始を拒否します。この確認範囲をDescriptionとChangelogに残してください。

FFmpegはJARに同梱せず、初回の「FFmpegを準備」で取得する仕組みです。Windows用音声ヘルパーは同梱しています。録画環境と制限は紹介文に記載しています。

## 今回の更新内容と確認範囲

1.0.2では両版の「回復」に、選択した回復データを確認後に完全削除する機能を追加します。映像断片・音声・失敗した回復処理の一時データなどを対象とし、完成MP4・カメラパターン・保存済みショット設定は残します。使用中は拒否し、失敗時は理由を表示して再試行できます。自動削除・一括削除はありません。

NeoForgeのシェーダー連携は1.0.1の対応範囲を維持します。両版のassembleとForgeのreobfJarが成功し、JAR内のバージョン・クラス・翻訳を確認しました。利用者の希望に従い、Minecraft起動・ゲーム操作・自動テスト・実録画・実データの削除は行っていません。今回と過去の確認範囲は[検証記録](testing-ja.md)を参照してください。
