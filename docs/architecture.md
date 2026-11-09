# 実装構成と設計上の判断

## 採用構成

| 層 | 実装 | 責務 |
|---|---|---|
| UI / lifecycle | `MainActivity.kt`, `EqApplication.kt`, `PlayerController.kt` | Compose、SAF、キュー、音声フォーカス、設定保存 |
| Background | `PlaybackService.kt` | foreground media playback、MediaSession、通知、再生中の wake lock |
| JNI / I/O | `native/oboe_bridge.cpp` | control 専用 JNI、Oboe callback、AAudio stream 管理 |
| FFI | `rust/crates/ffi-android` | opaque handle と C ABI、所有権境界 |
| Engine | `rust/crates/engine` | decode worker、SPSC queue、render、メトリクス |
| DSP | `rust/crates/dsp-core` | RBJ peaking DF1、疑似BASS、compressor、limiter、wide、crossfeed |
| SRC | `rust/crates/dsp-resample` | Rubato FFT SRC、初期遅延除去と末尾補正 |
| Spatial | `rust/crates/dsp-spatial` | stereo matrix partitioned convolution、SOFA filter 抽出、per-ear delay、IR 合成 |
| AutoEQ | `rust/crates/autoeq` | CSV 検証、対数補間、独立した PEQ 最適化、headroom 計算 |
| Desktop I/O | rust/crates/audio-cpal | 任意featureのCPAL native-rate出力、同じengine / DSP |
| 外部 session | `SystemEqService.kt`, `SessionEqualizers.kt` | 非ゼロ session の platform EQ、制御権・近似範囲の表示、停止時の解放 |

Oboe C++ との境界が中心になるため、設計書で比較した **C ABI + 小さな C++ JNI shim** を採用しています。ヘッダー `native/include/eq.h` と Rust の ABI は対になっています。control メソッド数が少ないため binding generator は必須依存にしていません。

PEQ は今回、RBJ 係数生成と DF1 フィルターを自前の小さいモジュールとして実装しました。外部 crate の振る舞いに依存せず、係数と周波数応答をテストできる構成です。Android API は `dsp-core` に含めません。オプションの spatial 重処理や AutoEQ optimizer を再生 loop に追加しないでください。

## リアルタイム上の規約

- 設定は control thread で係数へ変換し、8 スロットの SPSC queue に Copy 値で投入します。満杯なら最新設定を UI が再送します。
- callback が読む設定の数には上限を置き、最後の snapshot を使います。20 ms の新旧 pipeline crossfade 中の次設定は一つだけ保持します。
- PCM queue は stereo frame 単位。decode worker のみが producer、Oboe callback のみが consumer です。worker は queue が満杯なら短時間 sleep し、callback はデータがなければゼロを出します。
- renderer が停止中なら PCM を消費しません。worker の中断は atomic cancel flag → join で行います。join は control 側だけです。
- config に Vec、String、Arc のような解放処理を持つ payload を入れないでください。現在の renderer は heap allocation / deallocation と mutex を使用しません。
- callback にはファイル I/O、ログ、JNI、FFT plan、JSON、係数生成を持ち込みません。
- handle は一つの render thread に属します。callback 停止と stream close より前に FFI handle を destroy してはいけません。

HRTF と stereo reverb の IR は worker で合成し、一つの 2×2 FIR として処理します。virtual source は左入力・右入力の 2 本で、4 つの耳への伝達経路を保ちます。SOFA は native rate で読み、選択済みフィルターだけを Rubato で変換します。データの fractional delay は IR に組み込みます。

長い FIR の先頭は small partition、後半は 1024 frames 以上の large partition で処理します。後半の開始 offset は large partition の 2 倍です。入力の FFT 後、積和演算を small hop ごとに分散し、次の large block までに逆 FFT を完了します。後半には small partition 分の delay を加え、先頭経路と厳密に時刻を合わせます。実数 FFT の半スペクトルのみを保存し、存在しない交差経路の kernel は確保しません。新旧 renderer の遅延は同じです。

AutoEQ は CSV → 192 点の log grid → level-normalized shape loss → bounded coordinate descent の順で実行します。再生 handle の mutex を取りません。JSON / PK 設定の既存経路へ結果を渡し、計算中に設定が編集された場合は適用を中止します。公式 AutoEq solver と同一結果を保証しません。

差し替えは 1 スロットの準備済み resource queue と 2 スロットの retired queue を使用します。audio callback は旧 resource を retired queue に返し、control / metrics 側が回収します。新旧の convolution を warmup 後 50 ms でクロスフェードし、pause 中も無音で差し替えを進めます。EOF 後は IR の余韻を出してから finished にします。

JNI の native engine は Kotlin instance ごとの owner ID を持ちます。古い instance の非同期 close が別 instance のストリームを破棄しません。prepare の token は install 成功時のみ消費され、キャンセル時には worker が未消費 token を解放します。

## ライフサイクル

ソース変更では旧 stream を停止・close し、旧 worker を cancel / join、native rate で新 stream と engine を作ります。新ソースは初期バッファ準備後に開きますが、audio focus が得られるまでは無音です。経路が切断されたら callback 内で再接続せず、UI の再生操作で新しい stream を開きます。この場合は曲頭から再生します。

ファイル選択は SAF を使います。キューの URI は永続読み取り許可を取得して保存し、置き換え・クリア時に不要な許可を解放します。media permission や RECORD_AUDIO は不要です。Application が controller を所有し、Activity の破棄と再生ライフサイクルを分離します。ユーザー操作から media playback service を開始し、foreground になってから音声フォーカスを取得します。停止通知でソースと foreground service を解放します。

MediaSession の通知は Android の通知権限の適用除外です。通常通知は投稿せず、該当箇所だけ Lint の permission 警告を抑制しています。[Android の公式説明](https://developer.android.com/develop/ui/compose/notifications/notification-permission#media-sessions)

## Phase 2 以降

1. 実際の SOFA dataset と複数端末での聴感・性能・電池評価。任意 dataset の再配布権は自動判定しません。
2. head tracking、複数移動音源、長い IR、FFT partition / latency / thermal の実機評価。
3. 公式 AutoEq solver との比較評価、別 target / solver、任意測定値での品質検証。現実装は独立した bounded optimizer です。
4. gapless queue transitions、アルバムアート、再生状態の詳細なプロセス復元。CPAL desktopは実装済み（Android埋め込みbackendは未対応）。
5. low / medium / flagship、USB DAC / wired / Bluetooth / speaker の QA、ABX、Perfetto、CPU / memory / battery の測定。
6. AudioPlaybackCapture と global / root system-wide は別 feasibility track とする。非rootの協調 session 連携は [実装済みの経路](external-eq.md) です。

これらは残作業です。エミュレーター検証と、端末横断の製品 release gate を同一視しないでください。

