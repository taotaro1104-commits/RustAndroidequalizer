package dev.rusteq.fixture;

import android.app.Activity;
import android.content.Intent;
import android.media.*;
import android.media.audiofx.AudioEffect;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.widget.TextView;

/** Separate UID cooperating player for integration tests; never shipped in EQ APK. */
public final class SessionPlayer extends Activity {
    private AudioTrack track;
    private AudioTrack secondTrack;
    private boolean announceSessions;
    private MediaSession mediaSession;
    private void mediaState(int state, long position) {
        if (mediaSession != null) mediaSession.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
            .setState(state, position, state == PlaybackState.STATE_PLAYING ? 1f : 0f).build());
    }
    private void metadata(Intent intent) {
        if (mediaSession != null) mediaSession.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, intent.getStringExtra("metadata_title") == null ? "External music test title" : intent.getStringExtra("metadata_title"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "External test artist")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "External test album")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 125000).build());
    }
    private void announce(String action) {
        if (!announceSessions) return;
        sendBroadcast(new Intent(action).putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId())
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName()));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("STOP".equals(getIntent().getAction())) { finish(); return; }
        announceSessions = !getIntent().getBooleanExtra("silent", false);
        final int frames = 48000;
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(frames * 4).setTransferMode(AudioTrack.MODE_STATIC).build();
        short[] samples = new short[frames * 2];
        for (int i = 0; i < frames; ++i) { short v = (short)(Math.sin(i * 2 * Math.PI * 440 / 48000) * 500); samples[2*i] = v; samples[2*i+1] = v; }
        track.write(samples, 0, samples.length); track.setLoopPoints(0, frames, -1); track.play();
        if (getIntent().getBooleanExtra("multiple", false)) {
            secondTrack = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(frames * 4).setTransferMode(AudioTrack.MODE_STATIC).build();
            secondTrack.write(samples, 0, samples.length); secondTrack.setLoopPoints(0, frames, -1); secondTrack.play();
        }
        announce(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        if (getIntent().getBooleanExtra("publish_metadata", false)) {
            mediaSession = new MediaSession(this, "RustEq external metadata fixture");
            metadata(getIntent()); mediaState(PlaybackState.STATE_PLAYING, 15000); mediaSession.setActive(true);
        }
        TextView text = new TextView(this); text.setText("Integration test player\nSession " + track.getAudioSessionId()); setContentView(text);
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if ("STOP".equals(intent.getAction())) finish();
        if ("METADATA".equals(intent.getAction())) metadata(intent);
        if ("PAUSE".equals(intent.getAction()) && track != null) { track.pause(); mediaState(PlaybackState.STATE_PAUSED, 20000); }
        if ("RESUME".equals(intent.getAction()) && track != null) { track.play(); mediaState(PlaybackState.STATE_PLAYING, 20000); }
    }
    @Override public void onDestroy() {
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); mediaSession = null; }
        if (track != null) { announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION); track.stop(); track.release(); track = null; }
        if (secondTrack != null) { secondTrack.stop(); secondTrack.release(); secondTrack = null; }
        super.onDestroy();
    }
}
