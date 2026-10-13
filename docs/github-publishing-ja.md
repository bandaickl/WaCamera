# GitHub公開先と更新方法

- ソースコード：https://github.com/bandaickl/WaCamera
- 配布ファイル：https://github.com/bandaickl/WaCamera/releases
- 不具合報告：https://github.com/bandaickl/WaCamera/issues

CurseForgeの「Source」欄にはソースコードのURLを設定します。「Issues」または不具合報告先の欄を使う場合は、上記の不具合報告URLを設定できます。

## 利用者がダウンロードするもの

Releasesの「Assets」にある、自分のMinecraftとローダーに合うJARを一つ使います。GitHubが自動生成する「Source code (zip)」「Source code (tar.gz)」は開発用ソースであり、modsフォルダーへ入れるファイルではありません。

**現在公開済みのReleaseはv1.0です。** 今回は1.0.2のソースをGitHubへ更新し、ローカル配布物を準備する範囲です。1.0.2のGitHub Releaseは作成しません。ビルドとJAR内容の確認を終え、ローカルの`dist/1.0.2/`へ次のファイルを配置しました。将来公開する際は[1.0.2の変更履歴](release-1.0.2-changelog.md)の確認範囲を説明に残してください。

- `wacamera-forge-1.20.1-1.0.2.jar`
- `wacamera-neoforge-1.21.1-1.0.2.jar`
- `SHA256SUMS.txt`：JARのダウンロード内容を確認するためのハッシュ値。

1.0.2では両版に、確認画面を挟む回復データの個別削除を追加しました。完成MP4やカメラパターンは対象外です。両版のassembleとForgeのreobfJarが成功しましたが、利用者の希望によりMinecraft起動・自動テスト・実録画・実データの削除は行っていません。NeoForgeのIris 1.8.12＋Sodium 0.6.13連携は1.0.1の対応範囲を維持し、ゲーム内の映像・性能は未検証です。シェーダー有効＋Distant Horizons併用も引き続き未対応です。

## 今後の更新

開発フォルダーは引き続きこのWaCameraフォルダーを使います。ソースの更新は修正をコミットしてGitHubへpushします。今後、新しいバージョンを公開すると決めた場合に、そのタグとReleaseを作成して対応するJARを掲載します。今回のソース更新だけではタグやReleaseを作成しません。公開済みの1.0ファイルは、通常の修正では上書きせず、新しいバージョンを作ります。

ビルド方法はルートのREADMEを参照してください。GitHub Actionsのビルドは手動実行のみです。ソースのアップロードや更新だけではビルド・テストを起動しません。

## 公開対象

ソース、ビルド用スクリプト、説明文、正式ロゴ、MITライセンス、外部コンポーネントの案内を公開します。録画、セーブ、ローカル設定、開発キャッシュ、参考画像、認証情報はGitの管理対象から除外しています。

FFmpeg本体はこのリポジトリや配布JARに含めません。初回準備時に別途取得する仕組みとライセンス情報は、ルートの`THIRD_PARTY.md`に記載しています。
