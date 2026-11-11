# Android 13・YouTube「未接続」の解消方法の調査

調査日：2026-11-11。対象：Rust EQ 0.4.3 / Android 13。公開リポジトリのコード、開発者の説明、Android 13のAOSP実装を照合した結果です。利用者の端末・現行YouTubeでの動作確認結果ではありません。今回、製品コード・APK・端末権限は変更していません。

## 結論

最初に採用を検討するのは、**DUMP権限を使った音声セッションの拡張検出**です。YouTubeのOPEN通知を待つ現状から、システムの音声サービス情報を読み、選択済みアプリの実セッションへ端末EQを接続する方式へ拡張します。Waveletの公式資料にYouTube向けの説明があり、RootlessJamesDSPとEqualizer314には参考になる公開コードがあります。

他アプリ音声へRustのPEQ・コンプレッサー・HRTF・IRまで適用する要件には、**音声取り込み → Rust DSP → 再出力と、元音声のセッション単位の消音**が別途必要です。RootlessJamesDSPに、この経路をrootなしで実現する実装があります。単にAudioPlaybackCaptureを追加するだけでは元音声が重なります。

「標準の通知連携だけではYouTubeへの接続を保証できない」は維持します。一方、「rootなしでは他アプリ音声を独自DSPへ通す実装自体ができない」という広い説明は適切ではありません。追加の許可と音声取り込み・消音を組み合わせた実例があります。ただし、すべてのアプリ・再生経路への適用を保証する仕組みではありません。

## 現状のコードとの相違

- `SystemEqService.kt` はOPEN/CLOSEブロードキャストと、明示的に渡されたセッションIDだけを受け取ります。起動前から再生中の場合や通知のない再生を、自分から探索しません。
- `SessionEqualizers.kt` は有効な非ゼロIDを受け取ればAndroidの`Equalizer`を作成できます。したがって、最初の検証対象は「YouTubeを開けるか」ではなく「実セッションIDを取得できるか」です。
- ManifestにDUMP権限・通知リスナー・録音権限はありません。**現行0.4.3にADBコマンドを実行するだけでは解決しません。** 権限宣言と検出機能の追加が必要です。
- `audio/NativeEngine.kt` は制御操作のみをJNIへ渡す設計で、外部PCMを入力するAPIはありません。取り込み方式は、現在の端末EQ接続より大きな変更になります。
- 既存のAndroid 13検証では別UIDの協調プレーヤーへの接続は成功。検証用YouTube 19.17.42は更新要求で止まり、動画再生・接続は未検証です。

## 方法の比較

| 方法 | 追加の設定・権限 | 今回のアプリへの適合 | 制約 |
|---|---|---|---|
| セッション拡張検出＋既存の端末EQ | ADBまたはShizuku経由のDUMP付与。通知アクセスは再生状態監視の補助として検討 | 最初の候補。現在の対象アプリ選択・保存・Equalizer処理を活用できる | 端末EQの近似処理。OEMのダンプ形式、非公開API、offloadなどの差を実機確認する必要あり |
| 取り込み＋元音の消音＋Rust DSP再出力 | 上記に加えRECORD_AUDIO、MediaProjectionの許可、専用の音声入力・出力経路 | 独自DSPまで適用する本命候補 | 取り込みを禁止する再生は対象外。遅延、二重再生、消音と取得順序、停止時の復元が課題 |
| root・Magiskによる音声効果挿入 | rootと対応モジュール | 一般配布版とは別の実装候補 | 端末依存が大きく、現状のアプリの小修正では導入できない |

## 調べた公開実装

### Wavelet：方式の根拠

[公式Settings](https://pittvandewitt.github.io/Wavelet/Settings/#enhanced-session-detection)は、通常の通知連携がないプレーヤー向けにDUMP権限を使う拡張検出を説明しています。Android 10以降の実験的機能とされ、通知アクセスの設定も案内されています。[開発者のYouTubeに関する回答](https://github.com/Pittvandewitt/Wavelet/issues/169)でも、YouTubeのセッション発見に拡張検出を使うことが説明されています。

このGitHubリポジトリは説明資料が中心で、アプリ本体のソースを提供するライブラリではありません。方式の参考資料として扱います。[Android 13での端末依存の報告](https://github.com/Pittvandewitt/Wavelet/issues/312)もあり、権限付与だけで全端末の解決を断定しません。

### RootlessJamesDSP：検出・消音・独自DSPの公開実装

[リポジトリ](https://github.com/timschneeb/RootlessJamesDSP)。調査したソースのコミット：`60d25ae8a53c6f4691c090673df290a73c6b6357`。

[README](https://github.com/timschneeb/RootlessJamesDSP/blob/60d25ae8a53c6f4691c090673df290a73c6b6357/README.md)はYouTubeを動作確認済みアプリに挙げ、Android 13のPixelとAOSPエミュレーターでの試験を記載しています。ただし、これは本プロジェクトや現在のYouTubeでの成功を意味しません。

確認した処理は以下です。

- [DumpUtils](https://github.com/timschneeb/RootlessJamesDSP/blob/60d25ae8a53c6f4691c090673df290a73c6b6357/app/src/main/java/me/timschneeberger/rootlessjamesdsp/session/dump/utils/DumpUtils.kt)：DUMP権限を確認し、システムサービスのBinderからダンプを取得。
- [AudioServiceDumpProvider](https://github.com/timschneeb/RootlessJamesDSP/blob/60d25ae8a53c6f4691c090673df290a73c6b6357/app/src/main/java/me/timschneeberger/rootlessjamesdsp/session/dump/provider/AudioServiceDumpProvider.kt)：UID/PIDとセッションIDを読み取り、古い形式ではAudioFlingerの情報を補助利用。
- [BaseSessionManager](https://github.com/timschneeb/RootlessJamesDSP/blob/60d25ae8a53c6f4691c090673df290a73c6b6357/app/src/main/java/me/timschneeberger/rootlessjamesdsp/session/shared/BaseSessionManager.kt)：再生状態・メディアセッションの変化、設定に応じたポーリングで再探索。
- [MutedAudioEffectFactory](https://github.com/timschneeb/RootlessJamesDSP/blob/60d25ae8a53c6f4691c090673df290a73c6b6357/app/src/main/java/me/timschneeberger/rootlessjamesdsp/utils/MutedAudioEffectFactory.kt)：元セッションにDynamicsProcessingなどを付け、元音声を消音。
- [RootlessAudioProcessorService](https://github.com/timschneeb/RootlessJamesDSP/blob/60d25ae8a53c6f4691c090673df290a73c6b6357/app/src/main/java/me/timschneeberger/rootlessjamesdsp/service/RootlessAudioProcessorService.kt)：AudioPlaybackCaptureで取得したPCMを独自DSPで処理し、AudioTrackへ出力。自分の出力を取り込み対象から外す。
- [OnboardingFragment](https://github.com/timschneeb/RootlessJamesDSP/blob/60d25ae8a53c6f4691c090673df290a73c6b6357/app/src/main/java/me/timschneeberger/rootlessjamesdsp/fragment/OnboardingFragment.kt)：ADBとShizukuによる設定導線を実装。

元音声の消音と取り込みが両立するかは重要な検証項目です。端末・効果実装・音声経路によって取り込み側まで無音になる可能性を排除できません。公開コードの存在を、そのまま普遍的な互換性の証拠にはしません。

### Equalizer314：既存EQに近い検出実装

[リポジトリ](https://github.com/bearinmindcat/Equalizer314)。調査したソースのコミット：`9294a5c8fccc6cebf44b2d45bfb01609f8ec7e94`。

[AudioPolicyDumpParser](https://github.com/bearinmindcat/Equalizer314/blob/9294a5c8fccc6cebf44b2d45bfb01609f8ec7e94/app/src/main/java/com/bearinmind/equalizer314/audio/AudioPolicyDumpParser.kt)は、audioサービスの情報からUIDとセッションIDを取得し、パッケージ別の複数セッションへ対応しています。[PlaybackListenerService](https://github.com/bearinmindcat/Equalizer314/blob/9294a5c8fccc6cebf44b2d45bfb01609f8ec7e94/app/src/main/java/com/bearinmind/equalizer314/audio/PlaybackListenerService.kt)は、再生変化を契機にワーカースレッドで探索する構造です。

そのまま移植すべきコードとは評価していません。例えば、`readLine()`前の時刻確認だけでは、読み取り自体がブロックしたときの厳密なタイムアウトになりません。またshared UIDを最初のパッケージへ割り当てる処理は、「指定したアプリだけ」という要件では曖昧です。独立した実装で、これらを扱う必要があります。

## Android 13で確認した権限境界

- AOSP Android 13の[Manifest](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/core/res/AndroidManifest.xml)ではDUMPは`signature|privileged|development`です。通常の実行時ダイアログでは付与できません。アプリが宣言したうえで、ADBなどの設定手順が必要です。
- Android 13の[AudioService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/services/core/java/com/android/server/audio/AudioService.java)は、ダンプ前にDUMP権限を確認します。
- [PlaybackActivityMonitor](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java)は、非特権クライアントに匿名化した再生情報を返します。公開の再生コールバックを登録するだけで、他アプリの実セッションIDが得られるとは考えません。
- [Android公式の取り込み仕様](https://developer.android.com/media/platform/av-capture)では、RECORD_AUDIO、MediaProjectionの許可、同じユーザープロファイル、再生元の許可方針などが条件です。DUMPや通知アクセスは、取り込み禁止を解除する権限ではありません。
- [Shizuku](https://github.com/RikkaApps/Shizuku)はADBまたはrootの権限で起動する仲介プロセスです。root必須という意味ではありませんが、インストールしただけで権限が得られるわけでもありません。設定状態・停止・再起動を処理する必要があります。

## このアプリへ導入する順序

1. **拡張検出の検証用実装**：明示的なオプション、DUMP宣言と権限確認、ワーカースレッドでの時間・サイズ制限付き取得、Android 13のダンプ解析を追加。まず通知を送らない別UIDの試験プレーヤーで検証します。
2. **既存EQへ接続**：選択したパッケージのUIDを確認し、正の実セッションだけを`SessionEqualizers`へ渡します。複数セッションを保持し、通知由来と検出由来の所有関係を区別します。停止・権限失効・選択解除・セッション消滅では該当効果を解放します。
3. **診断表示**：「権限不足」「YouTube再生検出・ID未取得」「ID取得・端末EQ拒否」「端末EQ接続済み」を分けます。通知アクセスを使う場合も通知内容の保存は不要です。再生アプリ名やIDの取得だけで接続済みにはしません。
4. **実際のYouTubeで評価**：Android 13で再生可能なバージョンを使い、動画・広告・Shorts・出力先切り替え・バックグラウンド・再起動を確認。効果の制御状態に加え、テスト音源の出力変化で実適用を確認します。以前の更新要求画面のままでは合格としません。
5. **Rust DSP入力方式を別途設計**：選択UIDだけの取り込み、消音との両立、外部PCM入力、サンプルレート変換、出力ループ防止、遅延、許可失効・クラッシュ時の元音復元を検証してから製品化します。録音データを保存する必要はありません。

session 0の全体効果を主方式にする案は、非推奨・端末依存で、対象アプリを限定する要件にも合わないため優先しません。YouTube APKの改変も、更新・署名・配布を別に管理する必要があるため、最初の解決策にはしません。

## 再利用と今回の到達点

参考実装の[RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP/blob/master/LICENSE)と[Equalizer314](https://github.com/bearinmindcat/Equalizer314/blob/main/LICENSE)はGPL v3系で、現在のRust EQはMITです。ソースをそのまま取り込む場合は配布条件の整理が必要です。現段階では公開仕様・方式を参考にした導入検討で、コードは転用していません。

今回確認できたのは、追加権限による検出経路と、取り込み・消音・独自DSP経路の具体的な公開実装です。**利用者端末でのYouTube接続成功はまだ未確認です。** 次の実装候補としては、変更が比較的小さく現在の選択・保存機能を活用できる拡張検出を先行させることを推奨します。

調査ツール：agent-reachのExa検索、GitHubコネクター、Android公式資料。ツール更新確認ではAgent Reach v1.6.1が利用可能でした。今回ツール自体の更新は行っていません。
