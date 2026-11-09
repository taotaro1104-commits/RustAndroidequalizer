# CPAL desktop再生

audio-cpalは任意featureのdesktop出力バックエンドです。Androidと同じengine、デコーダー、SRC、PEQ、疑似BASS、Dynamicsを使います。AndroidアプリのOboe経路はそのままです。

通常のworkspaceビルドではCPALを有効にしません。次のように明示して実行します。

    cargo run -p audio-cpal --features cpal-backend --locked -- --list-devices
    cargo run --release -p audio-cpal --features cpal-backend --locked -- --demo --seconds 10 --bass 0.5 --preamp -12
    cargo run --release -p audio-cpal --features cpal-backend --locked -- --file path/to/music.flac

LinuxではビルドにALSA開発パッケージが必要です（Ubuntu: libasound2-dev）。WindowsではMSVC、macOSでは通常のRustビルド環境を使用します。

デバイスの既定sample rate / formatを使い、整数PCMへの変換にも対応します。mono出力は左右を平均し、3ch以上の出力では先頭2chだけを使用します。callbackは固定長scratchで分割処理し、PCMをUIへ転送しません。終了時はstreamを破棄してからdecode workerをjoinします。

これはdesktop向けのCLIです。KotlinからのAndroid context初期化・Android backend切替・GUIは実装していません。HRTF / IRの読み込み操作もこのCLIにはありません。
