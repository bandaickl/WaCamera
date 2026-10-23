# GitHub公開先と更新方法

- ソースコード：https://github.com/bandaickl/WaCamera
- 配布ファイル：https://github.com/bandaickl/WaCamera/releases
- 不具合報告：https://github.com/bandaickl/WaCamera/issues

CurseForgeの「Source」欄にはソースコードのURLを設定します。「Issues」または不具合報告先の欄を使う場合は、上記の不具合報告URLを設定できます。

## 利用者がダウンロードするもの

Releasesの「Assets」にある、自分のMinecraftとローダーに合うJARを一つ使います。GitHubが自動生成する「Source code (zip)」「Source code (tar.gz)」は開発用ソースであり、modsフォルダーへ入れるファイルではありません。

**現在公開済みのReleaseはv1.0です。** この公開JARは音声ヘルパーEXEを同梱した旧版で、1.0.4のJNA音声方式は反映されていません。今回は1.0.4のソース更新とローカル配布物の準備までで、1.0.4のGitHub Releaseは作成しません。両版のassemble・ForgeのreobfJarとJAR内容を確認し、`dist/1.0.4/`に次のファイルを配置しました。将来公開する際は[1.0.4の変更履歴](release-1.0.4-changelog.md)の確認範囲を説明に残してください。

- `wacamera-forge-1.20.1-1.0.4.jar`
- `wacamera-neoforge-1.21.1-1.0.4.jar`
- `SHA256SUMS.txt`：JARのダウンロード内容を確認するためのハッシュ値。

1.0.4はMinecraft既存のJNAからWindows WASAPI process-loopbackへ接続し、音声ヘルパーEXEの準備・パス入力を不要にする変更です。音声用の新しいEXE・DLLは同梱・取得しません。Windows 11 x64を対象に、Windows x64の新規設定は音声ON、保存済みのON/OFFは保持します。非対応環境・初期化失敗は理由を表示し、倍速は無音です。旧ヘルパーパスを参照せず、既存実ファイルは自動削除しません。旧音声回復形式は維持します。通常のMODビルドにC++ツールは不要です。利用者の希望によりMinecraft起動・自動テスト・実録音・実録画は行いません。回復データ削除とNeoForgeのIris 1.8.12＋Sodium 0.6.13連携は従来の範囲を維持し、NeoForgeのシェーダー映像・性能は未検証です。シェーダー有効＋Distant Horizons併用も引き続き未対応です。

## 今後の更新

開発フォルダーは引き続きこのWaCameraフォルダーを使います。ソースの更新は修正をコミットしてGitHubへpushします。今後、新しいバージョンを公開すると決めた場合に、そのタグとReleaseを作成して対応するJARを掲載します。今回のソース更新だけではタグやReleaseを作成しません。公開済みの1.0ファイルは、通常の修正では上書きせず、新しいバージョンを作ります。

ビルド方法はルートのREADMEを参照してください。GitHub Actionsのビルドは手動実行のみです。ソースのアップロードや更新だけではビルド・テストを起動しません。

## 公開対象

ソース、ビルド用スクリプト、説明文、正式ロゴ、MITライセンス、外部コンポーネントの案内を公開します。録画、セーブ、ローカル設定、開発キャッシュ、参考画像、認証情報はGitの管理対象から除外しています。

FFmpeg・ffprobe本体はこのリポジトリや配布JARに含めません。1.0.4の音声は既存JNAとWindows標準APIを利用し、別の音声ヘルパーを配布・取得・実行しません。旧ヘルパーのソースとビルド手順は履歴参考として残します。既存のFFmpeg準備機能、各ツールとの連携方法、JNAを含むライセンス情報は、ルートの`THIRD_PARTY.md`に記載しています。
