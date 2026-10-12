# GitHub公開先と更新方法

- ソースコード：https://github.com/bandaickl/WaCamera
- 配布ファイル：https://github.com/bandaickl/WaCamera/releases
- 不具合報告：https://github.com/bandaickl/WaCamera/issues

CurseForgeの「Source」欄にはソースコードのURLを設定します。「Issues」または不具合報告先の欄を使う場合は、上記の不具合報告URLを設定できます。

## 利用者がダウンロードするもの

Releasesの「Assets」にある、自分のMinecraftとローダーに合うJARを一つ使います。GitHubが自動生成する「Source code (zip)」「Source code (tar.gz)」は開発用ソースであり、modsフォルダーへ入れるファイルではありません。

**現在公開済みのReleaseはv1.0です。** 今回は1.0.1のソース更新とローカル配布物の準備までを行い、1.0.1のGitHub Releaseは作成しません。両版のビルドとJAR内容の確認が完了し、ローカルの`dist/1.0.1/`へ次のファイルを配置しました。将来公開する際は[1.0.1の変更履歴](release-1.0.1-changelog.md)の確認範囲を説明に残してください。

- `wacamera-forge-1.20.1-1.0.1.jar`
- `wacamera-neoforge-1.21.1-1.0.1.jar`
- `SHA256SUMS.txt`：JARのダウンロード内容を確認するためのハッシュ値。

## 今後の更新

開発フォルダーは引き続きこのWaCameraフォルダーを使います。修正後のソースをコミットしてGitHubへpushし、新しいバージョンのタグとReleaseを作成して対応するJARを掲載します。公開済みの1.0ファイルは、通常の修正では上書きせず、新しいバージョンを作ります。

ビルド方法はルートのREADMEを参照してください。GitHub Actionsのビルドは手動実行のみです。ソースのアップロードや更新だけではビルド・テストを起動しません。

## 公開対象

ソース、ビルド用スクリプト、説明文、正式ロゴ、MITライセンス、外部コンポーネントの案内を公開します。録画、セーブ、ローカル設定、開発キャッシュ、参考画像、認証情報はGitの管理対象から除外しています。

FFmpeg本体はこのリポジトリや配布JARに含めません。初回準備時に別途取得する仕組みとライセンス情報は、ルートの`THIRD_PARTY.md`に記載しています。
