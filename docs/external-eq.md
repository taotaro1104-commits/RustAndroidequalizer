# 他アプリ EQ と system-wide の境界

「OTHER APPS / SESSION EQ」を有効にし、対応プレーヤーで曲を開き直してください。他アプリが `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` を送信した場合、非ゼロの audio session ID に Android `Equalizer` を接続します。CLOSE で解放します。プレーヤーの音響設定画面から本アプリを開く場合は、受け取った session ID をスイッチ操作時に接続します。

これは対応プレーヤーとのセッション連携です。Spotify / YouTube / DRM / offload / OEM 経路への適用や、全アプリの音声変更は保証できません。音声セッションを公開しないアプリは一覧に現れません。未対応アプリの音を盗聴・録音・再生し直す処理はありません。

PEQ と preamp の応答を端末 EQ の中心周波数でサンプリングし、各 band の gain に近似します。端末ごとの band 数・band shape・gain 範囲に制約があるため、Rust PEQ と同一の応答にはなりません。compressor / limiter / HRTF / IR はこの経路に適用しません。他アプリ側で clipping 対策を保証する機能もありません。gain 範囲の制限や制御権の喪失は画面に表示します。

設定はアプリ内の EQ と共通です。master bypass は外部 EQ も無効化します。他の EQ と競合した場合は優先度を奪い合わず、通常優先度 0 で制御状態を表示します。接続上限は 8 セッションで、停止時にすべて解放します。報告された package 名は表示用で、送信元認証には使いません。

常時待ち受けはユーザー操作で開始する foreground service を使用します。Android 14+ は `specialUse` と用途説明を宣言しています。Google Play 公開時の foreground service 用途審査は別途必要です。通知の停止操作、アプリのスイッチ、OS の実行中アプリ画面から停止できます。自動起動やプロセス終了後の自動復活は行いません。通知権限はこの待ち受け通知のためにだけ要求します。録音権限は不要です。

完全な global output mix への効果挿入（session 0）、root / Magisk / privileged audio HAL への改変は実装していません。通常アプリにこれらと同じ動作を保証する API はありません。root 方式を採る場合は端末・Android / ROM・既存音声モジュール・復旧手段を確定し、専用の別プロジェクトとして評価する必要があります。

## 検証

`android/session-player/` は別 UID の協調プレーヤー fixture です。製品 APK には含まれません。低音量のテスト音と OPEN / CLOSE broadcast を送ります。instrumentation で外部セッションへの接続、master bypass、CLOSE / service stop の解放を検証します。

```sh
bash android/gradlew -p android :session-player:installDebug :app:connectedDebugAndroidTest
```

fixture が未インストールの場合、外部 UID の結合テストは skip します。テスト結果では skip の有無も確認してください。実際の他社プレーヤーや OEM 端末の対応一覧を保証するテストではありません。

公式 API の根拠：

- [Equalizer: session 指定と session 0 の非推奨](https://developer.android.com/reference/android/media/audiofx/Equalizer)
- [AudioEffect: OPEN / CLOSE と制御権](https://developer.android.com/reference/android/media/audiofx/AudioEffect)
- [Foreground service: specialUse の宣言と審査](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
