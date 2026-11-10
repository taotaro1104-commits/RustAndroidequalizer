# 対象アプリの指定・保存と接続

## 目的と動作範囲

利用者が対象アプリを選んで保存し、そのアプリが公開する非ゼロ音声セッションへ端末EQを自動適用する。未接続の対象には「接続」を置き、既知のセッションへ再接続を試み、対象アプリを開いて再生を始められるようにする。接続していないものを接続済みと表示しない。

通常のAndroidアプリに、任意アプリの出力を強制的にRust DSP経由へ差し替える公開APIはない。AudioPlaybackCaptureはコピーと同意に基づく機能で、常時の出力置換にはならない。root/特権API、録音、MediaProjection、session 0は追加しない。対象アプリからOPEN通知を受け取れない場合は適用できないことを日英で表示する。

## 保存する設定とサービス

- SharedPreferences `external_eq`：`selected_packages` (StringSet)、`enabled` (Boolean、既定false)、`resume_after_boot` (Boolean、既定true)。選択なしの場合enabledはfalseに正規化。
- 選択内容はアプリ名とは別にpackage名で保存。EQの値は既存の `eq/last` を引き続き使用する。
- 明示的なOFFと通知の停止はenabledをfalseに保存。サービス破棄だけでは保存した意図を消さない。
- 有効設定はアプリ再表示時とMY_PACKAGE_REPLACED後に復元。BOOT_COMPLETED後の復元はユーザーの再起動復元設定が有効な場合に限る。Androidの制限・ユーザーによる強制停止では保証できない。
- START_STICKYでシステムによる通常の再生成に対応。セッションIDは永続化しない。新しいOPENに選択条件を適用し直す。
- 選択を外したアプリの既存EQは即解放。停止時は全解放。CLOSEは選択変更後も処理する。

## 共有API

- `dev.rusteq.audio.ExternalEqSettings(selectedPackages:Set<String>, enabled:Boolean, resumeAfterBoot:Boolean)`、`shouldRun`。
- `ExternalEqSettingsStore(context).state: StateFlow<ExternalEqSettings>`、`update((ExternalEqSettings)->ExternalEqSettings)`。
- `EqApplication.externalEqSettings` に上記ストア。
- `dev.rusteq.ExternalEqController.start(context, request:Pair<Int,String>?=null)`、`connect(context, packageName:String, request:Pair<Int,String>?=null)`、`stop(context)`、`restore(context, afterBoot:Boolean=false)`。失敗は既存runtimeのUiTextメッセージにする。
- `SystemEqService.CONNECT_PACKAGE` と `EXTRA_TARGET_PACKAGE`。connectは選択済みpackageだけを受け付ける。
- UIは接続ボタンから保存enabledを有効にして接続要求し、ユーザー操作として対象アプリのlaunch intentを開く。サービスは勝手に他アプリの画面を開かない。

## UI・権限・互換性

起動できるアプリを検索可能なチェックリストで選び、保存・キャンセルする。選択済みアプリには名前・package・接続状態・接続ボタンを表示。アプリが削除されても保存したpackageは消さず、接続できない状態を説明する。通知許可の結果に依存せず設定保存は完了し、実際の待ち受け状態を別途表示する。

英語・日本語を維持。ランチャーアプリのquery intentのみをmanifestに追加し、QUERY_ALL_PACKAGESは使用しない。BOOT受信権限と非exported復元receiverを追加。最小API29、target35、既存署名鍵・JSONと利用者プリセットを維持。配布は0.4.2 / versionCode6。

## 検証

実装前に「選択外fixtureへ接続しない」テストが旧実装で失敗することを確認する。実装後は選択外の除外、選択中の接続、選択解除の解放、保存復元、タップ再接続、明示停止、起動復元条件、日英表示、既存再生・プリセットを検証する。実際の端末再起動で復元を確認できたかを記録し、未検証を成功扱いしない。

公式根拠：Android DevelopersのAudioEffect / Equalizer、AudioPlaybackCapture、foreground serviceのbackground-start exemptions / specialUse資料。
