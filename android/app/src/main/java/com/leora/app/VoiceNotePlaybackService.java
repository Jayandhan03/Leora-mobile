package com.leora.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import java.io.IOException;

/**
 * Foreground service that owns real audio playback for voice-note pushes and
 * drives a native Android media notification (MediaStyle + MediaSession) —
 * the same mechanism WhatsApp/Spotify/etc. use to get a play/pause + seek bar
 * directly in the notification shade and the system Media Controls surface,
 * playable without ever opening the app.
 *
 * Started by VoiceNotePlugin.java in response to a JS call from the web app
 * (see components/PushNotificationBridge.tsx in the main Leora repo).
 */
public class VoiceNotePlaybackService extends Service {

    public static final String ACTION_PLAY = "com.leora.app.action.PLAY";
    public static final String ACTION_PAUSE = "com.leora.app.action.PAUSE";
    public static final String ACTION_STOP = "com.leora.app.action.STOP";

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";

    private static final String CHANNEL_ID = "leora_voice_notes";
    private static final int NOTIFICATION_ID = 9001;

    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private String currentTitle = "Leora";
    private String currentBody = "Voice note";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        mediaSession = new MediaSessionCompat(this, "LeoraVoiceNote");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stopSelfAndClean(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            String title = intent.getStringExtra(EXTRA_TITLE);
            String body = intent.getStringExtra(EXTRA_BODY);
            if (title != null) currentTitle = title;
            if (body != null) currentBody = body;

            // Must call startForeground() promptly after startForegroundService()
            // or Android kills the app with a "did not call startForeground in
            // time" crash — show a loading-state notification immediately, then
            // update it once the clip is actually playing.
            startForeground(NOTIFICATION_ID, buildNotification());
            if (url != null) startPlayback(url);
        } else if (ACTION_PAUSE.equals(action)) {
            pause();
        } else if (ACTION_STOP.equals(action)) {
            stopSelfAndClean();
        }

        MediaButtonReceiver.handleIntent(mediaSession, intent);
        return START_NOT_STICKY;
    }

    private void startPlayback(String url) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            );
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                refreshNotification();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
                stopSelfAndClean();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                stopSelfAndClean();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            stopSelfAndClean();
        }
    }

    private void resume() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            refreshNotification();
        }
    }

    private void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            refreshNotification();
        }
    }

    private void seekTo(int ms) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(ms);
            updatePlaybackState(mediaPlayer.isPlaying()
                ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED);
        }
    }

    private void stopSelfAndClean() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (IllegalStateException ignored) { }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        stopForeground(true);
        stopSelf();
    }

    private void updatePlaybackState(int state) {
        long position = mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SEEK_TO
                    | PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, position, 1f)
            .build();
        mediaSession.setPlaybackState(playbackState);

        long duration = mediaPlayer != null ? mediaPlayer.getDuration() : 0;
        MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentBody)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build();
        mediaSession.setMetadata(metadata);
    }

    private void refreshNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        boolean playing = mediaPlayer != null && mediaPlayer.isPlaying();

        PendingIntent playPauseIntent = PendingIntent.getService(
            this, 0,
            new Intent(this, VoiceNotePlaybackService.class).setAction(playing ? ACTION_PAUSE : ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stopIntent = PendingIntent.getService(
            this, 0,
            new Intent(this, VoiceNotePlaybackService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int playPauseIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(currentTitle)
            .setContentText(currentBody)
            .setContentIntent(contentIntent)
            .addAction(playPauseIcon, playing ? "Pause" : "Play", playPauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setStyle(
                new MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Voice notes", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Playback controls for Leora voice-note briefings");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) mediaSession.release();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
