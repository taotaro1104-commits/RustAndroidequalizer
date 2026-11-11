# 他アプリ EQ と system-wide の境界

0.4.3では「ツール → 他アプリにもEQ → 対象アプリを選ぶ」で、アプリ名・パッケージ名を検索し、複数の対象を選んで保存できます。「キャンセル」は変更を破棄します。対象を保存しただけでは待ち受けを有効にしません。

未接続の対象の「接続」をタップすると、待ち受けが開始されたことを確認して対象アプリを開きます。開始できなかった場合はエラーを表示します。対象アプリで曲を再生・開き直すと、対応する音声セッションへ接続します。既知のセッションがあれば再試行し、接続中は「再接続」で効果を作り直せます。開けなくなったアプリも保存対象に残り、選択画面から解除できます。

選択された他アプリが `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` を送信した場合、非ゼロの audio session ID に Android `Equalizer` を接続します。CLOSEで解放します。プレーヤーの音響設定画面から本アプリを開く場合は、受け取ったIDを接続に使えます。選択解除で該当EQをすぐに解放し、すべて解除すると待ち受けも無効になります。保存するのは対象パッケージ、有効・無効、端末再起動時の復元設定で、変動するセッションIDは保存しません。

これは対応プレーヤーとのセッション連携です。Spotify / YouTube / DRM / offload / OEM 経路への適用や、全アプリの音声変更は保証できません。音声セッションを公開しないアプリは待機表示のままで、接続済みとは表示しません。通常のAndroidアプリから任意の他アプリの音声を必ずRust DSPへ通すことはできません。AudioPlaybackCaptureは許可された音声のコピーを取得する仕組みで、元の出力経路を置き換えるものではありません。本機能では録音・キャプチャを行いません。

PEQ と preamp の応答を端末 EQ の中心周波数でサンプリングし、各 band の gain に近似します。端末ごとの band 数・band shape・gain 範囲に制約があるため、Rust PEQ と同一の応答にはなりません。compressor / limiter / HRTF / IR はこの経路に適用しません。他アプリ側で clipping 対策を保証する機能もありません。gain 範囲の制限や制御権の喪失は画面に表示します。

設定はアプリ内の EQ と共通です。master bypass は外部 EQ も無効化します。他の EQ と競合した場合は優先度を奪い合わず、通常優先度 0 で制御状態を表示します。接続上限は 8 セッションで、停止時にすべて解放します。報告された package 名は表示用で、送信元認証には使いません。

待ち受けはforeground serviceを使用し、Android14以降は`specialUse`を宣言します。通知の「停止」とアプリのスイッチは明示的な無効化として保存し、プロセス終了では設定を変更しません。「再起動後に再開」が有効なら、保存済みの有効な待ち受けを端末再起動後に復元します。アプリ更新後とアプリを開いたときも有効な設定を復元し、OSによるサービス再作成にも対応します。再接続用の観測記録は一時的な32件に制限します。

強制停止・OSの実行中アプリの停止・メーカーの省電力設定で復元が制限される場合があります。アプリを開き直すか「待ち受けを再開」を押してください。既に再生中のアプリには新しいセッション通知が必要になる場合があります。通知権限を拒否しても選択や有効設定は保存され、実行中アプリ画面から停止できます。録音権限や全パッケージ照会権限は要求しません。公開時のサービス用途審査は別途必要です。

完全な global output mix への効果挿入（session 0）、root / Magisk / privileged audio HAL への改変は実装していません。通常アプリにこれらと同じ動作を保証する API はありません。root 方式を採る場合は端末・Android / ROM・既存音声モジュール・復旧手段を確定し、専用の別プロジェクトとして評価する必要があります。

## 検証

`android/session-player/` は別UIDの協調プレーヤーfixtureです。製品APKには含みません。低音量のテスト音とOPEN / CLOSEを送ります。instrumentationで接続・bypass・解放、未選択アプリへの非接続、対象の変更と保存、明示的停止と復元設定、画面の保存・キャンセルとタップ接続を検証します。

```sh
bash android/gradlew -p android :session-player:installDebug :app:connectedDebugAndroidTest
```

fixture が未インストールの場合、外部 UID の結合テストは skip します。テスト結果では skip の有無も確認してください。実際の他社プレーヤーや OEM 端末の対応一覧を保証するテストではありません。

公式 API の根拠：

- [Equalizer: session 指定と session 0 の非推奨](https://developer.android.com/reference/android/media/audiofx/Equalizer)
- [AudioEffect: OPEN / CLOSE と制御権](https://developer.android.com/reference/android/media/audiofx/AudioEffect)
- [AudioPlaybackCaptureの制限と同意](https://developer.android.com/media/platform/av-capture)
- [Foreground serviceのバックグラウンド起動と例外](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Foreground service: specialUse の宣言と審査](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)

## 0.4.3の検証結果（2026-11-11）

- Android 13 / x86_64で全31テスト成功、スキップなし。別UIDの協調プレーヤーへの接続と、セッション通知のない場合の待機表示を確認しました。
- 実際のプロセス終了を挟み、EQ・プリセット名・選択バンド・空間設定・対象アプリ・有効設定を保持することを確認しました。対象アプリ設定そのものが消える現象は旧版の独立試験でも再現せず、音声設定の初期化とは分けて検証しています。
- Q=0.1の数値比較と20kHzスライダーの丸め誤差で、保存したEQ全体が初期値に戻るケースを修正しました。同一署名の0.4.2から更新し、旧データの補正と対象設定の保持も確認しました。
- YouTube 19.17.42を匿名環境で開きましたが、更新要求画面で止まり、動画再生と実際のEQ接続は未検証です。これは現行YouTubeや利用者の端末での対応・非対応を確定する結果ではありません。
- debug / releaseビルド・Lint、日本語・英語の整合性、v2 / v3署名、16KB整列を確認しました。詳細は[0.4.3検証記録](releases/0.4.3.json)を参照してください。

## 0.4.2の検証結果（2026-11-10）

- Android API35 / x86_64で全22テスト成功、スキップなし（62.988秒）。旧実装では未選択アプリへの誤接続を再現し、修正後の非接続を確認。
- debug / releaseビルド成功、両Lintは警告・エラーなし。日本語・英語リソース整合性テスト2件成功。
- 同じデータディスクの一時エミュレーターを電源再投入し、アプリを開く前にBOOT_COMPLETEDでforeground待ち受けが復元されることを確認。adb rebootは応答待ちとなったため、テスト用エミュレーターのプロセスを終了して再起動した。
- 同一鍵の署名APKを0.4.1から0.4.2へ更新インストール。通知を拒否しても「接続」で対象が開き、サービスが動作し、対応セッションへの接続表示を確認。日英の実画面を撮影。
- v2 / v3署名、16KB zipalign、両ABIの8個のELF整列を検証。元AVDディスクは不変、一時コピーは停止・削除。arm64実機や他社プレーヤー全般の対応は今回の検証範囲外。
- APK：`dist/signed/rust-eq-0.4.2-release.apk`（15,575,599 bytes）。SHA-256：`caa531b9daaf7e76e2c69cb252cc18fe0b45d38b9601789db73c897878181342`。
