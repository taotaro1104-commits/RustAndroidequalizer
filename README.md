# Rust EQ for Android

Android 10（API 29）以上向けのアプリ内音楽プレーヤー / イコライザー。Kotlin / Jetpack Compose が UI、Rust がデコード・SRC・DSP、Oboe / AAudio が出力を担当します。

紹介用の画面は [スクリーンショット一覧](docs/screenshots/README.md) を参照してください。

## 実装した機能

- 端末内ファイルの再生 / 一時停止 / シーク / リプレイ、複数ファイルのキュー・自動送り、60 秒のステレオデモ。
- Symphonia による MP3、WAV、FLAC、Ogg Vorbis、AAC-LC / M4A、ALAC、AIFF のデコード（mono / stereo、DRM なし）。実際の対応可否はコンテナとコーデックの組み合わせによります。
- 10 / 31 バンド PEQ、Hz / gain / Q 調整、対数周波数グラフとドラッグ操作、preamp、bypass。
- 明るい配色の「音づくり・空間・ツール」画面。音質カーブ付きプリセット、折りたためる詳細調整、大きな文字への対応（[画面](docs/screenshots/README.md)、[検証](docs/ui-design-validation.md)）。
- ステレオ連動 compressor、sample-peak limiter、M/S widener、低域 crossfeed。
- 16種類の標準プリセットと、倍音生成による疑似BASS / ウーファー。量・対象帯域を調整し、JSONに保存できます（[設定と仕組み](docs/presets-bass.md)）。
- 設定更新時の 20 ms クロスフェード。係数計算は control thread、処理は可変フレーム数対応。
- プリセットの端末内保存・復元・削除、JSON 書き出し、AutoEQ が生成した **PK フィルター設定テキスト**と独自 JSON のインポート。
- 測定 / target CSV から 5 / 10 バンドの PEQ と preamp を生成する独立した AutoEQ 最適化器。worker 上で計算し、形状 RMSE を表示（[形式とアルゴリズム](docs/autoeq.md)）。
- native sample rate 優先。必要な場合だけ decode worker で Rubato FFT SRC を実行し、遅延・末尾ゼロ埋めを補正。
- Oboe Exclusive → Shared fallback、xrun に応じたバッファ調整、再生情報の p95 / p99 概算表示。
- SOFA HRTF の読み込み、左右 2 音源を前方 ±10〜80° に配置するヘッドホン向け処理。
- mono / stereo IR のインポート、人工的な Small Room、HRTF + リバーブの合成。FFT の準備とリソース解放は audio thread 外。
- 長い IR は先頭と余韻の二段階に分け、real FFT と余韻の積和計算の分散で負荷を低減。バッファ遅延は従来と同じ 1 small partition。
- Eco / Standard / Low latency の品質設定、callback の実測負荷に応じた段階的な軽量化。
- foreground media playback service、MediaSession、通知からの再生・一時停止・次曲・停止。画面を閉じても再生を維持。
- 音声フォーカス喪失・ヘッドホン切断時の一時停止。キューは選択済み URI の読み取り許可とともに復元。
- 他アプリが公開した非ゼロ音声セッションに Android Equalizer を接続する任意機能。端末 EQ への近似であり、全アプリの system-wide Rust DSP ではありません（[範囲と使い方](docs/external-eq.md)）。

## ビルド

必要環境：Rust stable（検証環境 1.99.1）、JDK 17+、Android SDK 35、NDK **27.2.12479018**、CMake **3.22.1**、cargo-ndk **4.1.2**。依存の解決結果は `Cargo.lock` に固定しています。

```sh
cargo install cargo-ndk --version 4.1.2 --locked
rustup target add aarch64-linux-android x86_64-linux-android
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
```

`ANDROID_HOME` と `JAVA_HOME` を設定してください。Android Studio で `android/` を開くこともできます。SDK の場所は `android/local.properties` の `sdk.dir` に指定できます。Gradle が両 ABI の Rust static library をビルドしてから JNI / Oboe をリンクします。

```powershell
# Windows
.\scripts\build-android.ps1
```

```sh
# Linux / macOS
bash scripts/build-android.sh
```

APK：`android/app/build/outputs/apk/debug/app-debug.apk`。`arm64-v8a` とエミュレーター用 `x86_64` を含みます。release は配布用署名を別途設定してください。

## 検証

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
bash android/gradlew -p android :app:lintDebug :app:connectedDebugAndroidTest
```

Rust テストは RBJ 周波数応答、可変 callback サイズ、異常 PCM、mono 互換性、クロスフェード、SRC、WAV → 再生終了、sample-accurate WAV seek、壊れたヘッダー、queue backpressure を検証します。畳み込みは直接計算との一致、全行列経路、partition 差、SOFA 読み込み / SRC / per-ear delay を検証します。allocator はリソース差し替え中も callback 内の allocation **と deallocation** を検出します。

Android instrumentation は native 再生、シーク、設定 import、旧所有者の解放、HRTF / IR 差し替え、foreground service、バックグラウンド再生、通知操作、自動次曲を検証します。
最適化器の JNI / CSV worker と、別 UID の協調プレーヤーへの接続 / bypass / 解放も検証します。外部連携 fixture は製品 APK に含めません。実行前に `bash android/gradlew -p android :session-player:installDebug` でインストールしてください。未導入なら外部 UID の結合テストは skip します。
実行前に接続先のエミュレーターまたはテスト端末を起動し、画面ロックを解除してください。検証結果と未検証の実機項目は [docs/validation.md](docs/validation.md) に記録しています。

```sh
# ホスト CPU 上の測定。Android の性能保証には使用しないでください。
cargo run --release -p dsp-spatial --example benchmark
```

## スレッドと境界

```text
Compose → JNI control → coefficient preparation → bounded SPSC config queue
SAF file → app cache → Rust decode / Rubato worker → bounded SPSC PCM queue
Oboe callback → Rust renderer → preamp → PEQ → virtual bass → compressor → stereo processing
               → prepared HRTF/reverb convolution → final limiter → output
```

JNI はソースを開く・設定する・再生制御・メトリクス取得だけを担当します。PCM は Kotlin に戻しません。C++ shim の mutex は control 専用です。audio callback は fixed-size / Copy の設定と事前確保済み queue のみを使い、Rust 側に allocation / free / lock / I/O を置きません。ストリームの callback を終了してから Rust handle を破棄します。

## 現在の範囲と制限

0.4.0 は **PEQ / 標準プリセット / 疑似BASS / Dynamics / HRTF / 長い IR / AutoEQ 最適化 / ローカル再生 / 対応する外部セッションへの EQ** を実装しています。CPALは任意のdesktop再生バックエンドとして追加しました。Androidは引き続きOboeを使用します。AudioPlaybackCaptureと全アプリの出力を処理するglobal / root方式は未実装です。

- 録音権限・ストレージ全体の読み取り権限・インターネット権限は要求しません。ファイルは Android の選択画面で指定します。
- バックグラウンド再生には `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `WAKE_LOCK` を使用します。MediaSession の通知のみを投稿します。
- 他アプリ EQ の待ち受けを有効にする場合だけ、通常の継続通知のため通知権限を要求します。`MODIFY_AUDIO_SETTINGS`、Android 14+ の `FOREGROUND_SERVICE_SPECIAL_USE` と用途説明も宣言します。公開時の用途審査は別途必要です。
- 選択ファイルはアプリの cache へコピーします（上限 1 GB）。ネットワーク配信・DRM・3ch 以上・途中で sample rate が変わる音源は対象外です。
- SOFA / IR は利用できる権利を持つファイルを選択してください。測定データはアプリに同梱していません。SOFA の保存形式によって読み込めないファイルもあります。
- HRTF は固定の左右 2 スピーカー方式です。head tracking / 複数移動音源は対象外。SOFA の読み込みとフィルター抽出にローカル修正版 Sofar 0.4.0 を使用します（[差分](vendor/sofar/PATCHES.md)）。
- IR は 8 秒以内、mono / stereo、エフェクトのファイルは 64 MB 以内。reverb tail 上限は Eco 0.25 秒 / Standard 2 秒 / Low latency 8 秒で、合成 IR の上限 768000 taps に収まるようにも短縮します。短縮時は末尾をフェードし、HRTF taps は保持します。
- 品質に応じて partition は 512 / 256 / 128 frames。表示する遅延は **partition によるバッファ遅延** で、IR / HRTF データ自体の遅延と端末出力遅延を含みません。
- callback p99 が period の 50% を継続して超えると品質を下げます。Eco でも period を継続して超える場合は HRTF / reverb を停止して通知します。実機の deadline 保証ではありません。
- HRTF / IR ファイルと選択状態はセッション内で扱います。EQ プリセット・JSON に外部データは含めません。アプリプロセス終了後の曲再開はユーザーの再生操作が必要です。
- limiter は lookahead なしの sample-peak 型です。true-peak 保証は行いません。bypass でも NaN 除去と ±0.999 の最終安全クリップは残ります。
- response graph は PEQ + preamp の静的応答であり、疑似BASS / compressor / limiter / spatial の応答は含みません。
- p95 / p99 は直近の native callback の処理時間を 100 µs 刻みで集計します。最終 bin は `≥12.8 ms` と表示します。厳密な deadline 判定には Perfetto を併用してください。
- DSP の締め切りは実際の **`numFrames / sampleRate`** に従います。10 ms は固定の猶予ではありません。実機での性能・熱・電池・聴感・30 分 xrun gate は別途 QA が必要です。

[CPALのdesktop再生手順](docs/desktop.md)も参照してください。

構成の詳細と残作業は [docs/architecture.md](docs/architecture.md) を参照してください。

