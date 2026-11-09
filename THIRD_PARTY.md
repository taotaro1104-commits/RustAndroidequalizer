# Third-party dependencies

依存バージョンは Cargo.lock と Gradle 設定を参照してください。

| Component | Purpose | Upstream license |
|---|---|---|
| Symphonia 0.5.5 | Audio decode / demux | MPL-2.0 |
| Rubato 0.16.2 | Worker-thread SRC | MIT |
| RustFFT 6.4.1 | Partitioned convolution / IR preparation | MIT OR Apache-2.0 |
| RealFFT 3.5.1 | Real-valued partition FFT / half-spectrum | MIT |
| Sofar 0.4.0 (local patches) | SOFA reader / filter lookup | MIT OR Apache-2.0 |
| rtrb 0.3.2 | SPSC queues | MIT OR Apache-2.0 |
| Oboe 1.9.3 | Android output | Apache-2.0 |
| Kotlin / AndroidX / Compose | Android app | Apache-2.0 |

Symphonia とその codec / format crate は upstream のソースを変更せずに利用します。配布時は MPL 対象部分のソース入手方法とライセンス文書を用意してください。transitive dependency も含め、公開配布前に SBOM と各ライセンス本文を取りまとめてください。

Sofar の原本ライセンスと変更内容は `vendor/sofar/` に保存しています。

HRTF / HRIR / SOFA / AutoEQ の測定データセットはアプリに同梱していません。テスト用の `synthetic.sofa` は独自生成の人工データ（CC0-1.0）です。将来バンドルする測定データはコードのライセンスとは別に配布条件を確認する必要があります。
