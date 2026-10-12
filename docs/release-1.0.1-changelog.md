# WaCamera 1.0.1

## English

Source update and local artifacts only. Both loader JARs and their SHA-256 checksums are available locally in `dist/1.0.1/`. The published GitHub release remains **v1.0**; a 1.0.1 release has not been published.

Adds independent shader recording to **NeoForge 1.21.1**, targeting **Iris 1.8.12 + Sodium 0.6.13** for that Minecraft version. Forge features are unchanged from 1.0.

- Gives the recording camera its own shader pipeline, gbuffer, shadow targets, temporal history and Sodium terrain programs.
- Restores the normal play view's rendering state and releases recording-owned GPU resources when recording ends.
- Uses the recording resolution for shader output and passes recording-camera data to shader uniforms.
- Refuses recording with an explanation when **shaders are active and Distant Horizons is installed on NeoForge**. That combination is not supported in this update.

Iris and Sodium are optional for ordinary recording without shaders. Minecraft, loader, Java and Windows recording requirements are unchanged from 1.0. Replace the old WaCamera JAR with the one matching your loader.

**Validation:** the actual Iris 1.8.12 and Sodium 0.6.13 JAR APIs and relevant source code were inspected. The final `assemble` builds of both loaders and Forge's `reobfJar` succeeded. At the user's request, no Minecraft launch, automated test, audio test or actual recording was performed for 1.0.1. NeoForge shader image quality, normal-view behavior, performance and long-session stability remain unverified. Earlier Forge shader tests are not evidence of NeoForge runtime compatibility.

## 日本語

今回はソース更新とローカル配布物の準備までを行います。両版のJARとSHA-256をローカルの`dist/1.0.1/`へ配置しました。GitHubの公開済みReleaseは**v1.0**を維持し、1.0.1のReleaseはまだ公開していません。

**NeoForge 1.21.1へ独立したシェーダー録画処理を移植しました。** 対象は、そのMinecraft版用の**Iris 1.8.12＋Sodium 0.6.13**です。Forgeの機能は1.0から変更していません。

- 撮影カメラ専用のシェーダーパイプライン、gbuffer、影の描画先、描画履歴、Sodiumの地形プログラムを分離しました。
- 通常プレイ側の描画状態を復元し、録画終了時に撮影が所有するGPU資源を解放します。
- シェーダーの出力寸法を録画解像度に合わせ、撮影カメラの情報をuniformへ渡します。
- **NeoForgeでシェーダー有効かつDistant Horizonsが導入されている構成は未対応**として、理由を表示して録画開始を拒否します。

Iris・Sodiumはシェーダーなしの通常録画には不要です。Minecraft・ローダー・Java・Windows録画環境の要件は1.0から変更していません。旧WaCameraのJARを外し、ローダーに合うJAR一つへ入れ替えてください。

**確認範囲：** Iris 1.8.12・Sodium 0.6.13の実JAR APIと関連ソースを照合し、修正反映後の両版の最終`assemble`とForgeの`reobfJar`が成功しました。利用者の希望により、1.0.1ではMinecraft起動・自動テスト・音声テスト・実録画を行っていません。NeoForgeでの映像、通常操作画面の維持、性能、長時間の安定性は未検証です。Forgeの過去の試験をNeoForgeの動作確認として扱いません。

詳しい記録は[検証記録](testing-ja.md)を参照してください。[1.0の変更履歴](release-1.0-changelog.md)は当時の内容を保持しています。
