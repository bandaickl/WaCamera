# WaCamera 1.0.2

## English

Source update and local artifact preparation only. Artifacts are available locally in `dist/1.0.2/`. The published GitHub release remains **v1.0**; a 1.0.2 release has not been published.

Adds deletion of selected interrupted-recording recovery data to **Forge 1.20.1 and NeoForge 1.21.1**.

- Open **K → Recovery**, select an entry, and press **Delete this recovery data**. A confirmation screen identifies the selected entry; choose **Permanently delete** to proceed or cancel to keep it.
- Removes that entry's video fragments, audio, metadata, logs, and temporary files from failed recovery attempts. Deletion cannot be undone, and the deleted data can no longer be used for recovery.
- Keeps completed MP4 files, other recovery entries, camera patterns, and saved shots.
- Refuses to delete data in use. Errors are displayed, allowing a retry after the cause is resolved.
- Does not add automatic or bulk deletion.

Minecraft, loader, Java, and Windows recording requirements are unchanged. The NeoForge shader integration added in 1.0.1 still targets **Iris 1.8.12 + Sodium 0.6.13** for Minecraft 1.21.1. Its rendering results and performance remain unverified. **Active shaders together with Distant Horizons remain unsupported on NeoForge.** Earlier Forge shader tests do not establish NeoForge runtime compatibility.

**Validation:** both `assemble` builds and Forge's `reobfJar` succeeded. Packaged classes, translations and version metadata were inspected. As requested, no Minecraft launch, automated tests, audio tests, recording or deletion of actual recording data was performed. In-game deletion and error handling have not been exercised.

## 日本語

今回はソース更新とローカル配布物の準備を行い、成果物を`dist/1.0.2/`へ配置しました。GitHubの公開済みReleaseは**v1.0**を維持し、1.0.2のReleaseは公開していません。

**Forge 1.20.1・NeoForge 1.21.1の両版に、選択した中断録画の回復データを削除する機能を追加しました。**

- **K →「回復」→ 対象を選択 →「この回復データを削除」**で確認画面を開きます。対象を確認して**「完全に削除」**を押すと実行し、キャンセルすると残します。
- 選んだ録画の映像断片・音声・時刻情報・ログと、失敗した回復処理の一時データを削除します。削除は取り消せず、そのデータから録画を回復できなくなります。
- 完成MP4、他の回復データ、カメラパターン、保存済みショット設定は残します。
- 使用中のデータは削除を拒否します。失敗時は理由を表示し、原因を解消して再試行できます。
- 自動削除・一括削除は追加しません。

Minecraft・ローダー・Java・Windows録画環境の要件は変更しません。1.0.1で追加したNeoForgeのシェーダー連携は、引き続きMinecraft 1.21.1用の**Iris 1.8.12＋Sodium 0.6.13**が対象です。描画結果・性能は未検証で、**NeoForgeのシェーダー有効＋Distant Horizons併用は未対応**です。Forgeの過去の試験をNeoForgeの動作確認として扱いません。

**確認範囲：** 両版のassembleとForgeのreobfJarが成功し、JAR内のクラス・翻訳・バージョン表記を確認しました。利用者の希望により、Minecraft起動・自動テスト・音声テスト・実録画・実データの削除は行っていません。ゲーム内での削除とエラー時の挙動は実行未検証です。

操作は[使い方](usage-ja.md)、検証結果は[検証記録](testing-ja.md)を参照してください。[1.0.1の変更履歴](release-1.0.1-changelog.md)と[1.0の変更履歴](release-1.0-changelog.md)は当時の記録を保持しています。
