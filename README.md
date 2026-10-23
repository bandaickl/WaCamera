# WaCamera

Record yourself from an independent camera directly to MP4 while you keep playing in first person. WaCamera is a **client-only** Minecraft mod for Forge 1.20.1 and NeoForge 1.21.1. The supported recording environment is **Windows 11 x64**. [English overview and quick start](docs/curseforge-description-en.md)

**[Download WaCamera / ダウンロード](https://github.com/bandaickl/WaCamera/releases/latest)** · [日本語の操作説明](docs/usage-ja.md) · [Report an issue / 不具合報告](https://github.com/bandaickl/WaCamera/issues)

**一人称で普段どおり遊びながら、別のカメラで自分も映る動画を撮るMOD**です。操作画面の手・照準・HUDを維持し、撮影カメラの映像をMP4へ直接保存します。**現在のソース／ローカルビルドの対象は1.0.4、GitHubの公開Releaseは1.0です。** 上のリンク先の1.0は音声ヘルパーEXEを同梱した旧版で、1.0.4のJNA音声方式は反映されていません。1.0.4のReleaseはまだ公開していません。サーバーへの導入は不要です。

The source and local build target is **1.0.4**. Game audio uses Minecraft's existing JNA runtime and Windows process-loopback APIs, without an audio-helper EXE or an additional DLL. New Windows x64 settings enable audio; existing preferences are preserved. FFmpeg and ffprobe are still required separately. The published GitHub release is still **1.0**, which includes the older bundled helper; the download link above does not provide 1.0.4.

## 対応環境

| Minecraft | 必要なローダー | Java |
| --- | --- | --- |
| 1.20.1 | Forge 47.4.10以上・48未満（47系） | 17 |
| 1.21.1 | NeoForge 21.1.251以上・21.2未満（21.1系） | 21 |

録画の対応環境はWindows 11 x64です。使用するMinecraft・ローダーに合うJARを一つだけ、対象インスタンスの`mods`フォルダーへ入れてください。更新時は以前のWaCameraのJARを外します。

## できること

- **固定・移動カメラ**：最大64地点のXYZ・yaw/pitchを指定し、直線またはなめらかな経路で撮影。登録した向き、自分、指定座標へ視線を向けられます。
- **直接MP4へ録画**：720p24／720p30／1080p24／1080p30／1080p60を選択。撮影時間は1〜3,600秒です。
- **10個のパターン**：A〜Jに名前・地点・向き・経路・画角・撮影時間・倍率を保存して再利用できます。
- **1〜120倍のタイムラプス**：小数の倍率にも対応。24fps・10倍なら実時間1秒に2.4枚を撮影し、倍速のMP4を保存します。1倍を超える録画は無音です。
- **カメラの目印**：操作画面に位置・方向・画角を表示します。動画には入らず、F1で非表示にできます。
- **別ウィンドウ操作中も録画**：フォーカス喪失による自動一時停止を録画中だけ抑え、終了後は通常の設定へ戻します。
- **ゲーム音と回復機能**：Windows 11 x64では音声ヘルパーの準備なしで、等倍のMinecraft再生音を収録する設計です。保存が中断された録画は「回復」から確認でき、不要な回復データを選んで確認後に削除できます。

## 最初の撮影

1. ワールド内で **K** を押して撮影パネルを開きます。
2. 「撮影地点」の **今の視点に更新** で選択中の地点を設定するか、レンズのXYZ・向きを入力します。追加したい場所へ移動するときはパネルを閉じ、再度開いて **今の視点を追加** を押します。
3. 「ショット」で撮影時間を設定し、「視線」で「自分を向く」などを選びます。一地点なら固定、複数地点なら移動撮影です。
4. 「録画設定」で画質を選びます。初めは **720p30** を推奨します。Windows x64の新規設定では **ゲーム音：あり** です。既存の設定は維持します。FFmpegが未導入なら **FFmpegを準備** を押します。
5. **プレビュー**で構図を確認し、**別視点で録画**を開始します。途中停止は **F8**。キー割り当ては変更できます。

プレビュー中だけ画面を撮影カメラへ切り替えます。録画中の移動・マウス・ブロック操作は、普段どおり本人を操作します。動画には操作用HUDやカメラの目印を入れません。

地点はコマンドでも登録できます。`/wac cam` は現在のXYZ・yaw・pitchを1番から順に追加、`/wac cam 2` は地点2を登録・上書きします。`/wac goto 2` は地点2の位置と向きへのテレポートを要求するため、チート許可／サーバーのテレポート権限が必要です。[詳しい使い方](docs/usage-ja.md)

## FFmpegの準備と音声

動画の作成・検査には、別プロセスで起動する **FFmpegとffprobe** を使います。この2つの実行ファイルはWaCameraのJARに同梱していません。

「録画設定」→ **FFmpegを準備** は、[GyanのWindowsビルド](https://www.gyan.dev/ffmpeg/builds/)から固定バージョン **9.0.2 essentials** を取得し、固定したSHA-256と照合します。初回の取得にはインターネット接続が必要です。ゲームフォルダーの`wacamera/tools/`へFFmpeg・ffprobeを配置し、配布元のLICENSE・READMEと、取得元・ソース情報を記したORIGINを保存します。導入済みのFFmpegを手動指定する場合は、同じフォルダーにffprobeを置くか、PATHから見つかる状態にしてください。

選択しているFFmpegビルドのライセンス情報は、配布元の文書と[外部コンポーネント](THIRD_PARTY.md)を参照してください。WaCamera本体のMIT Licenseとは分けて案内しています。

**1.0.4からゲーム音はMinecraftに既に含まれるJNAを使い、JavaからWindows標準のWASAPI process-loopbackへ接続します。音声ヘルパーEXEやパス入力は不要です。** この音声機能のために新しいEXE・DLLを同梱・ダウンロードしません。FFmpeg・ffprobeの準備は引き続き必要です。

Windows x64では新規設定を **ゲーム音：あり** にします。保存済みのON/OFFは変更しないため、1.0.3で無音になっている場合は必要に応じて **ゲーム音：あり** を選んでください。音声の対応環境はWindows 11 x64です。非Windows・非x64や、音声APIの初期化に失敗する環境では理由を表示し、勝手に無音やPC全体の音へ切り替えません。

Minecraftプロセスの再生音が対象で、マイクや別アプリの通話を録音する機能ではありません。1倍を超える録画は音声設定にかかわらず無音で、元の設定は保持します。旧ヘルパーのパスは使わず、配置済みの実ファイルも自動削除しません。旧版の音声付き回復データを読める形式は維持します。今回のJNA方式はゲーム起動・自動テスト・実録音を行っておらず、実動作と同期精度は未検証です。

## 保存と再利用

「パターン」でA〜Jの枠を選び、**この枠に保存**を押します。復元するときは枠を選んだ後に、**この枠を呼び出す**を押してください。枠の選択だけでは読み込みません。呼び出しは撮影設定の復元であり、本人の移動や録画開始は行いません。

保存先はいずれもゲームフォルダー内です。

- 動画：`wacamera/recordings/`
- 10個のパターン：`wacamera/patterns/`
- 「ショット」→「設定を保存」による名前付き設定：`wacamera/shots/`

「ショット」の秒数は実際に撮影する時間です。600秒・10倍なら動画は約60秒になります。ゲームの速さは変えず、録画用カメラの描画・画像取得・エンコードの頻度を減らします。圧縮結果は映像に依存するため、容量が厳密に10分の1になるとは限りません。

1.0.2から、**K → 回復 → 対象を選択 → この回復データを削除 → 確認**で、不要な中断録画の回復データを完全に削除できます。選んだ録画の映像断片・音声・失敗した回復処理の一時データなどが対象です。削除後はそのデータから回復できません。完成MP4、カメラパターン、保存済みショット設定は削除しません。使用中のデータは削除を拒否し、失敗時は理由を表示して再試行できます。自動削除・一括削除はありません。[回復と削除の手順](docs/usage-ja.md#保存に失敗した場合)

## シェーダー対応と制限

Forgeのシェーダー録画は、**Embeddium 0.3.31＋Oculus 1.8.0＋Complementary Unbound r5.6**で確認しています。Distant Horizons 2.3.4-bを含む構成での過去の試験も、制限とあわせて[検証記録](docs/testing-ja.md)に記載しています。これらは通常録画の必須MODではありません。

**1.0.1でNeoForge版にも独立したシェーダー録画処理を移植しました。** 対象はMinecraft 1.21.1用の **Iris 1.8.12＋Sodium 0.6.13**です。実JARのAPIとソースを照合しましたが、利用者の希望により、今回Minecraftの起動・自動テスト・実録画は行っていません。NeoForgeでの描画結果・性能は未検証です。**NeoForgeのシェーダー有効＋Distant Horizons併用は未対応で、録画開始時に理由を表示します。** Iris・Sodiumはシェーダー利用時の追加構成であり、シェーダーなしの通常録画には不要です。

1.0.4でも回復データ削除とシェーダーの対応範囲は維持しています。両版とも未検証のMOD・シェーダーの組み合わせまで動作を保証しません。

- 撮影できるのは読み込み済みの地形です。カメラだけを遠くへ動かしても、未受信のチャンクは撮影できません。
- 二つの視点を描画するため、通常プレイよりGPU負荷が増えます。バックグラウンドでも負荷は続き、ドライバーや他MODの制限でfpsが下がる場合があります。
- ゲームが実際に一時停止した場合やワールド退出時には録画を終了・保存します。ゲームを止めないチャット・インベントリー画面では継続します。
- 通常のシングルプレイではEscのポーズも停止対象です。マルチプレイやLAN公開中のEsc画面では、ゲームが停止しなければ録画を継続します。
- 完成動画を直接保存する方式です。リプレイ機能や撮影後の視点変更、障害物の自動回避はありません。

1.0.4の変更点は[変更履歴](docs/release-1.0.4-changelog.md)、確認範囲は[検証記録](docs/testing-ja.md)を参照してください。[1.0.3](docs/release-1.0.3-changelog.md)・[1.0.2](docs/release-1.0.2-changelog.md)・[1.0.1](docs/release-1.0.1-changelog.md)・[1.0](docs/release-1.0-changelog.md)とalpha版の試験記録は、当時の記録として残しています。

## ビルド

通常のMODビルドに必要なものはJDK 17と21です。音声ヘルパーやVisual Studio C++ツールは不要です。
JDKの場所は `JAVA17_HOME` と `JAVA21_HOME` で指定できます。

```powershell
./scripts/build.ps1
```

公開用ファイルを`dist/1.0.4/`へまとめる場合は`./scripts/build.ps1 -Release`を使います。Minecraftを起動せず、テストも実行しないassembleは`./scripts/build.ps1 -Release -SkipTests`です。`-SkipNative`は旧コマンド互換のため残していますが、現在は何も変更しません。旧音声ヘルパーのソースは[履歴参考](native-audio/windows/README.md)で、現行MODのビルド・利用には不要です。

完成JARは `dist/` にコピーされます。共通処理のテストだけなら `./gradlew.bat test`。
通常のテストでは音を再生しません。FFmpeg統合テストは `WACAMERA_TEST_FFMPEG`、Windowsプロセス音声取得の試験は `WACAMERA_TEST_WINDOWS_AUDIO=true` で明示的に有効化します。音声ヘルパーのパスは不要です。1.0.4では利用者の希望により自動テスト・音声取得は実行していません。

## 開発用Minecraft

```powershell
# JAVA_HOMEをJDK 17へ設定
./forge-1.20.1/gradlew.bat -p forge-1.20.1 runClient
# JAVA_HOMEをJDK 21へ設定
./neoforge-1.21.1/gradlew.bat -p neoforge-1.21.1 runClient
```

`-Psmoke -PsmokeAudio` を加えると、ミュートした専用の新規フラットワールドで固定／移動カメラを自動録画し終了する開発用手順です。現行の音声取得に旧ヘルパーの準備・パス指定は不要です。既存セーブは開きません。`-PsmokeFfmpeg=<実行ファイル>` でテスト用エンコーダーを指定できます。1.0.4更新時にはこれらのゲーム試験・音声取得を実行していません。

`-Psmoke -PsmokeTimelapse` は10枠の保存・復元と10倍／120倍／等倍を検証します。保存済みのパターンを保護するため、開発用インスタンスの10枠がすべて空の場合だけ動作します。

`-Psmoke -PsmokeMarker` はカメラ目印の表示・追従、録画映像への除外、F1・終了・プレビューでの非表示を検証します。

`-Psmoke -PsmokeBackground` は自動一時停止設定を有効にしたまま、フォーカス離脱中の等倍・10倍録画、復帰、手動ポーズ時の保存、録画終了後の自動一時停止を検証します。`-PsmokeBackgroundIconify` を追加すると、開発用ウィンドウを実際に2秒間最小化して復元します。全試験は無音です。

## 文書

- [使い方と制限](docs/usage-ja.md)
- [実施したテストと未検証の範囲](docs/testing-ja.md)
- [仕様](docs/specification-ja.md)：操作、必須機能、制約、完成条件。
- [制作設計](docs/implementation-ja.md)：両バージョンの構成、カメラ、録画・音声、開発順序、検証方法。
- [撮影設定の例](examples/intro-shot.json)：実装済みの保存形式。

- [録画モジュールとテスト](recorder/README.md)
- [旧Windows音声ヘルパーの履歴参考](native-audio/windows/README.md)
- [外部コンポーネント](THIRD_PARTY.md)

公開用の紹介文は[英語](docs/curseforge-description-en.md)・[日本語](docs/curseforge-description-ja.md)・[英日併記](docs/curseforge-description-bilingual.md)を用意しています。[CurseForge登録用の案内](docs/curseforge-upload-ja.md)も参照できます。
