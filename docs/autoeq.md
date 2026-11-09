# 測定 CSV から PEQ を生成

PRESETS の「測定 CSV を選んで最適化」で 5 / 10 バンドの補正を生成します。測定値は自分で用意し、目標カーブは `target_db` に入れてください。目標を省略すると flat を使います。flat がすべてのヘッドホンに適した目標という意味ではありません。

```csv
frequency,measured_db,target_db
20,80,80
40,80,80
80,81,80
160,83,80
320,85,80
640,83,80
1280,81,80
2560,80,80
5120,80,80
10240,80,80
20000,80,80
```

- 8〜8192 点、512 KB 以下、周波数昇順、10〜24000 Hz、有限値。少なくとも 2 octave 以上の測定範囲が必要です。
- 名前付き列は `frequency` / `frequency_hz` / `hz`、`measured_db` / `measured` / `raw`、`target_db` / `target` を受け付けます。`error` 列を target と取り違えません。
- 名前なしの 2 列または 3 列も使えます。区切りは comma / semicolon / whitespace。引用符付き CSV は対象外です。
- 対数周波数 192 点に補間し、音量の定数差を除いたカーブの RMSE を最小化します。Fc / gain / log Q の座標降下法です。boost は +6 dB、cut は -12 dB、Q は 0.3〜8 に制限します。
- 出力は PK フィルターと preamp。合成 boost の密な周波数サンプルに 1 dB の余裕を足して preamp を設定します。再生時の最終 limiter も使用します。
- 計算は worker 上で実行します。計算中に EQ を編集した場合、結果で編集内容を上書きせず再実行を案内します。
- これは本アプリの独立した最適化器です。公式 AutoEq の solver の移植、全設定の互換、同一結果を保証するものではありません。測定データや target のオンライン取得は行いません。

PC 上でも同じ実装を使えます。

```sh
cargo run --release -p autoeq --example optimize -- tests/fixtures/measurement.csv 10
```

生成した設定をアプリ内プリセットに保存するか、JSON として書き出せます。最適化 RMSE は測定カーブの形状に対する値で、聴感評価とは別に扱います。
