# WaCamera 1.0 — CurseForgeへの登録

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
| Upload file | `wacamera-forge-1.20.1-1.0.jar` | `wacamera-neoforge-1.21.1-1.0.jar` |
| Display name | WaCamera 1.0 — Forge 1.20.1 | WaCamera 1.0 — NeoForge 1.21.1 |
| Game version | 1.20.1 | 1.21.1 |
| Mod loader | Forge | NeoForge |
| Java | 17 | 21 |
| Environment | Client | Client |
| Release type | Release | Release |
| 必要なローダー | Forge 47.4.10以上・48未満 | NeoForge 21.1.251以上・21.2未満 |

Release TypeはCurseForgeのアップロード画面でも`Release`を選びます。JAR内のバージョンを1.0にしただけでは、このサーバー側の公開区分は設定されません。[公式アップロード説明](https://support.curseforge.com/support/solutions/articles/9000197241-creating-and-submitting-a-project)

Changelogには`release-1.0-changelog.md`の対応する言語の本文を使えます。配布準備用ZIPを使う場合も、展開した各JARを個別にアップロードします。ロゴ・説明文・ZIPそのものをMODのJARとしてアップロードしません。

クライアント側だけに導入します。Forge版とNeoForge版を同じインスタンスへ同時に入れないでください。Oculus・EmbeddiumはForgeでシェーダーを使う場合の任意の構成であり、通常録画の必須MODではありません。NeoForge版のシェーダー録画は未対応です。

FFmpegはJARに同梱せず、初回の「FFmpegを準備」で取得する仕組みです。Windows用音声ヘルパーは同梱しています。録画環境と制限は紹介文に記載しています。

## このリリースで行った作業

両版のバージョン・マニフェストを1.0へ統一し、ロゴと紹介文を用意しました。撮影機能はalpha.7から引き継いでいます。利用者の希望に従い、今回のリリース作業ではMinecraft起動・ゲーム操作・テストを行いません。過去の確認範囲は`TESTING-ja.md`を参照してください。
