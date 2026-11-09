# 実装検証記録

検証日：2026-11-10。

## 環境

- Windows / Rust 1.99.1 / cargo-ndk 4.1.2。
- JDK 21、Gradle wrapper 8.10.2、AGP 8.7.3、Kotlin 2.0.21。
- Android SDK 35 / NDK 27.2.12479018。
- ローカル検証では既設 CMake 4.3.1 を使用。標準設定と CI は SDK CMake 3.22.1。別バージョンは `-PcmakeVersion=...` または未追跡の `android/local.properties` の `cmake.version` で指定可能。
- 接続済み x86_64 / API 35 Android エミュレーターを使用。arm64-v8a はクロスコンパイル・リンクのみ。
- この作業環境では Cargo の直接ダウンロードに制約があったため、作業フォルダー内の一時キャッシュへ依存を取得し offline build を実施。通常のビルドはこの一時キャッシュを必要としません。

## 0.4.0 の検証

- 標準16プリセット、疑似BASSの量・抽出帯域、JSON v2保存とv1読込、102 / 104要素JNI互換性を追加。
- Rust 34テスト成功。60 Hz入力の120 Hz倍音、量への追従、高域への影響、左右差、bypass、DC・異常値を検証。可変callbackとリアルタイムallocation / deallocation検出でも疑似BASSを有効化。
- workspace整形 / Clippy、およびCPAL featureを有効にしたClippy成功。
- Android全11テスト成功（native / preset / optimizer 8、service 1、外部session 2）。50.608秒、0 failed / 0 ignored。
- 両ABIのassembleDebug / assembleRelease / lintDebug成功（4分1秒）。LintはNo issues found。
- debugのAPK v2署名、releaseの16 KB zipalign、両APK内各8個のELF LOAD alignmentが0x4000以上であることを確認。
- Windows CPALの実デバイスで48 kHz stereo f32、疑似BASS 55%、preamp −30 dBの2秒デモを再生。187 callbacks、PCM underrun 0。Android性能の代替検証ではありません。
- Android画面から16種類のメニュー表示と「疑似ウーファー」の選択を確認。10バンドへの切替、preamp −6.7 dB、疑似BASS 55% / 110 Hzの表示を確認して原本PNGを2枚追加。撮影後のアプリ設定は元のファイルとSHA-256が一致。
- 0.4.0 debug SHA-256: 9eaa8e364f816b5bef2da4d8564e253fe70e011aba76d0a4f835026eb0021838。
- 0.4.0 release SHA-256: 5368677e94fd29429b39163741ce4531e078cedd73c2dd2112f7ac3527d0a92d。
- 実機の聴感・長時間性能評価は未実施。

## 0.3.0 の検証

- Rust 30 テスト、整形、Clippy 成功。新たに長い FIR の直接畳み込み一致、3 秒 IR の末尾 / 品質別短縮、最適化器の既知カーブ・決定性・入力エラーを検証。
- IR は最大 8 秒。Standard は最大 2 秒、Low latency は最大 8 秒、合成 FIR のサンプル数上限でも短縮します。
- 測定 CSV の最適化と非rootの外部 session EQ を追加。外部 EQ は全アプリ / global output mix への適用ではありません。
- Android 全 10 テスト成功（native / optimizer 7、playback service 1、外部 session 2、33.216 秒、0 failed / 0 ignored）。別 UID の fixture に対し EQ 接続、master bypass、CLOSE 後の解放を確認。実際の他社プレーヤーへの対応を保証する結果ではありません。
- CSV 読み込み → Rust JNI 最適化 → 設定適用と、異常 CSV の拒否を確認。
- 両 ABI の build、`assembleDebug` / `assembleRelease` / `lintDebug` 成功。Lint は No issues found。release APK の zipalign 16 KB 検査と、8 個の ELF の LOAD alignment `0x4000` を確認。

同一ホスト・同一 2 秒 IR 入力での変更前後の p95（µs）：

| Frames | Room 変更前 | Room 変更後 | HRTF + room 変更前 | HRTF + room 変更後 |
|---:|---:|---:|---:|---:|
| 96 | 766.70 | 125.80 | 1127.20 | 329.60 |
| 192 | 809.70 | 193.90 | 1256.60 | 416.10 |
| 256 | 913.70 | 259.60 | 1241.40 | 491.90 |

この測定では約 2.5〜6.1 倍の改善。実行ごとのスケジューリング差を含む wall-clock 測定で、Android の性能保証ではありません。先頭 small partition / 後半 large partition と hop ごとの積和分散、real FFT の半スペクトルを使用し、追加のバッファ遅延は増やしていません。変更後の p99 / period は Room が 8.76 / 5.29 / 16.42%、合成が 22.59 / 13.16 / 17.07% でした。

追加の 8 秒 IR ストレス試行（48 kHz、partition 128、同じ共有ホスト）での p99 / period：

| Frames | 8 秒 Room | HRTF + 8 秒 Room |
|---:|---:|---:|
| 96 | 52.74% | 41.88% |
| 192 | 38.70% | 45.09% |
| 256 | 19.84% | 22.94% |

追加試行では 2 秒複合 / 96 frames も 51.00% になりました。実行ごとの差があり、全条件で p99 50% を達成した結果ではありません。自動軽量化を維持し、Android 実機の deadline / thermal gate は未合格として扱います。benchmark example は 2 秒 / 8 秒の両方を測定します。

## 0.2.0 の検証記録

- Rust：24 テスト成功（DSP 6、SRC 2、畳み込み / SOFA 6、engine unit 3、デコード integration 2、リアルタイム経路 5）。
- `cargo fmt --all --check`、`cargo clippy --workspace --all-targets --locked -- -D warnings` 成功。
- arm64-v8a / x86_64 の Rust static library と Oboe JNI shared library をビルド。
- Android instrumentation：全 7 テスト成功（native 6、service integration 1、最終実行 66.669 秒）。native callback 再生、pause 中の位置維持、異常ソース / repeated close、AutoEQ PK / JSON import、owner ごとの解放、シークと再生時間、HRTF / IR の準備と差し替えを確認。
- service integration は MediaSession 通知、画面非表示中の再生、通知の再生 / 一時停止操作、キューの自動送り、停止時の解放を確認。シーク中の停止後に再生が復活しないことも検証。
- 再生テストは AAudio の非同期開始を考慮し、固定待機ではなく再生位置の進行を期限付きで待ちます。
- 最終 `assembleDebug` 成功、Android Lint は **No issues found**。
- `assembleRelease` 成功。R8 による縮小を適用した未署名 APK を生成。
- release APK の `zipalign -c -P 16 -v 4` 成功。両 ABI の Rust EQ / Oboe / libc++ / AndroidX graphics の ELF LOAD segment は `0x4000` alignment。16 KB page の実機起動は別途確認が必要です。
- エミュレーターで Compose の EQ 画面表示と UI からのデモ再生を確認。
- 0.2.0 の EQ / Preset / Dynamics / Spatial / HRTF 画面表示を確認。最終検証後、再生サービスが停止済みであることを確認。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`。arm64-v8a / x86_64 を同梱。
- 1 バンド AutoEQ インポート後のリセットについて、有限の周波数値になることと JSON round-trip を回帰テストに追加。
- 人工的な SOFA fixture を使用して HDF v3、補間、SRC、左右独立の fractional delay を検証。測定 HRTF データの音響品質評価ではありません。
- WAV のゼロ sample rate による依存デコーダー内部の panic を入力検証で防止。設定や prepared convolution の交換時にも callback 内の allocation / deallocation がないことを確認。

Windows では以前の Gradle daemon が Lint の jar を保持して clean / Lint が失敗する場合がありました。この作業では `android/gradlew.bat -p android --stop` 後に `--no-daemon --no-watch-fs` で再ビルドして解消しました。

## 0.2.0 の性能測定記録

`cargo run --release -p dsp-spatial --example benchmark` はホスト上で 31-band PEQ、人工 HRTF、密な 2 秒 IR、合成 IR を 96 / 192 / 256 frames で測定します。実測値は実行環境に依存します。長い IR は短い callback の period を超える場合があり、無条件で有効化できる性能とは扱いません。アプリ側では品質ごとに reverb tail を短縮し、callback p99 に応じて品質を下げ、Eco でも期限を超え続ける場合は spatial 処理を停止します。

最終ホスト測定での p99 / callback period（%、48 kHz、共有 Windows ホストの wall-clock 測定、端末性能値ではない）：

| Frames | PEQ 31 | 人工 HRTF | 密な 2 秒 room | HRTF + room |
|---:|---:|---:|---:|---:|
| 96 | 3.67 | 5.10 | 87.45 | 201.56 |
| 192 | 10.96 | 68.68 | 76.46 | 110.62 |
| 256 | 5.00 | 6.07 | 833.24 | 162.79 |

この結果はホストの割り込みやスケジューリングも含み、性能の再現性や Android の deadline 合格を示しません。2 秒 IR はストレス条件で、通常の Standard モードの tail 上限は 1 秒です。重いモードの実機最適化と release gate は未完了です。

エミュレーターの再生成功は、端末実機での長時間 xrun、USB / Bluetooth 切替、CPU / thermal / battery、聴感評価、測定 HRTF dataset、製品 release gate の合格を意味するものではありません。これらは実機 QA の残作業です。

## 生成物の場所

各パスは最新のビルドで更新されます。

- インストール用 debug：`android/app/build/outputs/apk/debug/app-debug.apk`。
- 配布署名を付ける前の release：`android/app/build/outputs/apk/release/app-release-unsigned.apk`。未署名のままではインストールできません。
- 外部 UID の検証専用 fixture：`android/session-player/build/outputs/apk/debug/session-player-debug.apk`。製品 APK には含めません。
- 0.3.0 debug SHA-256：`4BC41DDE304170BDB76D920FE64BE9BDEEC57C401E71585FA672A3A528B208AF`。
- 0.3.0 release SHA-256：`B473FA6C80E41B21770C2F77674C0B8B3FF9859F05D8D76EAC754E45145847C7`。
