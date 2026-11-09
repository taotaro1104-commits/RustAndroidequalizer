package dev.rusteq.fixture;

import android.app.Activity;
import android.content.Intent;
import android.media.*;
import android.media.audiofx.AudioEffect;
import android.os.Bundle;
import android.widget.TextView;

/** Separate UID cooperating player for integration tests; never shipped in EQ APK. */
public final class SessionPlayer extends Activity {
    private AudioTrack track;
    private void announce(String action) {
        sendBroadcast(new Intent(action).putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId())
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName()));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("STOP".equals(getIntent().getAction())) { finish(); return; }
        final int frames = 48000;
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(frames * 4).setTransferMode(AudioTrack.MODE_STATIC).build();
        short[] samples = new short[frames * 2];
        for (int i = 0; i < frames; ++i) { short v = (short)(Math.sin(i * 2 * Math.PI * 440 / 48000) * 500); samples[2*i] = v; samples[2*i+1] = v; }
        track.write(samples, 0, samples.length); track.setLoopPoints(0, frames, -1); track.play();
        announce(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        TextView text = new TextView(this); text.setText("Integration test player\nSession " + track.getAudioSessionId()); setContentView(text);
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); if ("STOP".equals(intent.getAction())) finish(); }
    @Override public void onDestroy() {
        if (track != null) { announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION); track.stop(); track.release(); track = null; }
        super.onDestroy();
    }
}
