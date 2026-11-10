# Android APK のリリース署名

署名済みAPKは `dist/signed/` に出力します。通常のビルド結果の未署名APKを16 KBページ向けに整列してから、専用のリリース鍵でAPK Signature Scheme v2 / v3署名を行い、署名と整列を検証します。

## 初回の署名

JDK 17以降とAndroid SDK Build Tools 35.0.0、Python 3を使用します。`JAVA_HOME` と `ANDROID_HOME` を設定し、先にreleaseをビルドしてください。

```powershell
.\scripts\build-android.ps1 -Configuration Release
python scripts/sign-android.py --create-key
```

鍵がなければ、RSA 3072 bit・SHA256withRSA、有効期間10,000日のPKCS#12鍵をローカルに生成します。既存の鍵は置き換えません。鍵は自己署名のAPK配布用証明書で、認証局による本人確認を表すものではありません。

秘密情報は次の場所に保存します。どちらもGit対象外です。パスワードをコマンド行やログへ出力せず、子プロセスの環境変数から署名ツールに渡します。

- `.signing/rusteq-release.p12`：秘密鍵を含むキーストア。
- `.signing/keystore-pass.txt`：ランダム生成したパスワード。**キーストアと一緒に、安全な場所へバックアップしてください。**
- alias：`rusteq-release`。

この2ファイルを失うと、同じ署名で更新APKを作れません。公開フォルダやGoogleドライブのAPK配布フォルダへ秘密鍵・パスワードを置かないでください。

## 次回以降

同じ鍵を使い、既存APKと異なる出力先を指定します。インストール済みのリリース版を更新するときは `versionCode` を上げてください。

```powershell
python scripts/sign-android.py --output dist/signed/rust-eq-next-release.apk
```

別の保存場所の鍵は `--keystore` / `--password-file` / `--alias` で指定できます。既存の出力APKは上書きしません。

出力するもの：

- `rust-eq-<version>-release.apk`：arm64-v8a / x86_64入りの署名済みAPK。
- `*.apk.sha256`：APKのSHA-256。
- `*.signing.txt`：`apksigner verify --verbose --print-certs` と16 KB zipalign検証の結果。
- `rusteq-release-cert.pem`：公開証明書。秘密鍵は含みません。

署名検証ではv2とv3を両方確認するため、検証ツールにAPI 24以降の範囲を指定します。APK自体の最低対応環境は引き続きAndroid 10（API 29）です。

debug鍵でインストールした既存の開発版は、リリース鍵と署名が異なります。そのまま上書きインストールできないため、必要なプリセット等を先に書き出してから開発版を削除し、リリース版をインストールしてください。通常のリリース版同士の更新では、同じリリース鍵を使います。

## 今回の署名・検証（2026-11-10）

- APK：`rust-eq-0.4.0-release.apk`（15,337,975 bytes）。
- APK SHA-256：`77f533b605e5cde76172092ad307057471c1aec326ba0c3e195798f99369fc1b`。
- 署名証明書SHA-256：`6d6162953d457d4568fb69b61e277188d8445ad6cb7c5c9f8531b2e63473e607`。
- 専用のRSA 3072 bit鍵によるv2 / v3署名をそれぞれ検証。
- releaseビルドと`lintRelease`成功（No issues found）。
- 16 KB zipalign成功。両ABIの計8個のELFライブラリもLOAD segmentの16 KB整列を確認。
- Android API 35（x86_64）の読み取り専用エミュレーターでインストール成功、`am start -W` の `Status: ok` を確認。元のAVDデータディスクと暗号鍵ディスクのサイズ・更新日時は不変でした。
- 画面検証は未完了です。UIAutomatorがダンプファイルを作成せず、再試行はエミュレーター用ドライブの空き容量不足で起動できませんでした。
- arm64実機での起動は今回の署名作業では検証していません。

## 最新の0.4.2

0.4.2も同じリリース鍵で署名しています。v2 / v3、16KB zipalign、両ABI計8個のELF整列、0.4.1からの更新インストールを確認しました。日本語・Englishの画面検証も完了しています。上記0.4.0時点で未完了だった画面検証の状況は、[最新のリリース記録](releases/0.4.2.md)と[実画面](screenshots/README.md)で更新しています。
