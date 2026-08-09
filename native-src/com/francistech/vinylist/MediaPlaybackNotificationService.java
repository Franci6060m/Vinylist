package com.francistech.vinylist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.lang.ref.WeakReference;

/**
 * Real native playback notification + lock-screen controls.
 *
 * This is a foreground Service holding a MediaSessionCompat and posting a
 * MediaStyle notification with working previous/play-pause/next actions.
 * None of this depends on the WebView or the Web MediaSession API (which
 * Android's WebView only partially supports) -- it is plain native Android,
 * so the notification and its buttons keep working even when the app is
 * backgrounded, the screen is off, or the WebView process is frozen.
 *
 * Design note: every update from JS (MediaControlPlugin) arrives here as a
 * command Intent handled in onStartCommand, rather than as a direct method
 * call on a live instance. That makes the update path race-free -- it does
 * not matter whether this Service was already running or is only just now
 * being created by that exact Intent (Android guarantees onStartCommand
 * runs after onCreate, in order, on the main thread).
 */
public class MediaPlaybackNotificationService extends Service {

    static final String CHANNEL_ID = "vinylist_playback";
    private static final int NOTIF_ID = 4201;

    static final String ACTION_UPDATE_METADATA = "com.francistech.vinylist.action.UPDATE_METADATA";
    static final String ACTION_UPDATE_STATE = "com.francistech.vinylist.action.UPDATE_STATE";
    static final String ACTION_PLAY = "com.francistech.vinylist.action.PLAY";
    static final String ACTION_PAUSE = "com.francistech.vinylist.action.PAUSE";
    static final String ACTION_NEXT = "com.francistech.vinylist.action.NEXT";
    static final String ACTION_PREVIOUS = "com.francistech.vinylist.action.PREVIOUS";
    static final String ACTION_STOP = "com.francistech.vinylist.action.STOP";

    static final String EXTRA_TITLE = "title";
    static final String EXTRA_ARTIST = "artist";
    static final String EXTRA_ALBUM = "album";
    static final String EXTRA_DURATION_MS = "durationMs";
    static final String EXTRA_ARTWORK = "artworkBase64";
    static final String EXTRA_IS_PLAYING = "isPlaying";
    static final String EXTRA_POSITION_MS = "positionMs";

    /** Notification / lock-screen taps flow back out through this. MediaControlPlugin implements it. */
    public interface Callback {
        void onPlay();
        void onPause();
        void onNext();
        void onPrevious();
        void onSeekTo(long positionMs);
        void onStop();
    }

    private static WeakReference<Callback> sCallback;

    public static void setCallback(Callback cb) {
        sCallback = (cb == null) ? null : new WeakReference<>(cb);
    }

    private static Callback callback() {
        return sCallback != null ? sCallback.get() : null;
    }

    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder stateBuilder;

    private String title = "";
    private String artist = "";
    private String album = "";
    private long durationMs = 0;
    private Bitmap artwork = null;
    private boolean isPlaying = false;
    private long positionMs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        mediaSession = new MediaSessionCompat(this, "VinylistSession");
        stateBuilder = new PlaybackStateCompat.Builder().setActions(
            PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
            PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO |
            PlaybackStateCompat.ACTION_STOP);
        mediaSession.setPlaybackState(stateBuilder.build());
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { Callback cb = callback(); if (cb != null) cb.onPlay(); }
            @Override public void onPause() { Callback cb = callback(); if (cb != null) cb.onPause(); }
            @Override public void onSkipToNext() { Callback cb = callback(); if (cb != null) cb.onNext(); }
            @Override public void onSkipToPrevious() { Callback cb = callback(); if (cb != null) cb.onPrevious(); }
            @Override public void onSeekTo(long pos) {
                positionMs = pos;
                updateSessionState();
                Callback cb = callback();
                if (cb != null) cb.onSeekTo(pos);
            }
            @Override public void onStop() {
                Callback cb = callback();
                if (cb != null) cb.onStop();
                stopSelfSafely();
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_UPDATE_METADATA.equals(action)) {
            applyMetadataExtras(intent);
        } else if (ACTION_UPDATE_STATE.equals(action)) {
            applyStateExtras(intent);
        } else if (ACTION_PLAY.equals(action)) {
            Callback cb = callback(); if (cb != null) cb.onPlay();
        } else if (ACTION_PAUSE.equals(action)) {
            Callback cb = callback(); if (cb != null) cb.onPause();
        } else if (ACTION_NEXT.equals(action)) {
            Callback cb = callback(); if (cb != null) cb.onNext();
        } else if (ACTION_PREVIOUS.equals(action)) {
            Callback cb = callback(); if (cb != null) cb.onPrevious();
        } else if (ACTION_STOP.equals(action)) {
            Callback cb = callback(); if (cb != null) cb.onStop();
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        // Android requires startForeground() shortly after every
        // startForegroundService() call, regardless of which action
        // triggered it -- so this always runs, even for a plain metadata
        // update. If playback is actually paused we immediately relax the
        // foreground guarantee with stopForeground(false), which keeps the
        // notification visible without pinning the process.
        startForegroundCompat();
        if (!isPlaying) stopForegroundCompat();
        return START_NOT_STICKY;
    }

    private void applyMetadataExtras(Intent intent) {
        String t = intent.getStringExtra(EXTRA_TITLE);
        String a = intent.getStringExtra(EXTRA_ARTIST);
        String al = intent.getStringExtra(EXTRA_ALBUM);
        title = t != null ? t : "";
        artist = a != null ? a : "";
        album = al != null ? al : "";
        durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0);
        artwork = decodeArtwork(intent.getStringExtra(EXTRA_ARTWORK));

        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        if (artwork != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
        }
        mediaSession.setMetadata(metaBuilder.build());
    }

    private void applyStateExtras(Intent intent) {
        isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false);
        positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0);
        updateSessionState();
    }

    private Bitmap decodeArtwork(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private void updateSessionState() {
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        stateBuilder.setState(state, positionMs, isPlaying ? 1f : 0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Vinylist playback controls");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private PendingIntent serviceActionIntent(String action) {
        Intent intent = new Intent(this, MediaPlaybackNotificationService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, action.hashCode(), intent, flags);
    }

    private Notification buildNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.isEmpty() ? "Vinylist" : title)
            .setContentText(artist)
            .setSubText(album)
            .setSmallIcon(iconRes("ic_stat_vinylist", android.R.drawable.ic_media_play))
            .setLargeIcon(artwork)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(new NotificationCompat.Action(
                iconRes("ic_media_previous", android.R.drawable.ic_media_previous), "Previous", serviceActionIntent(ACTION_PREVIOUS)))
            .addAction(isPlaying
                ? new NotificationCompat.Action(iconRes("ic_media_pause", android.R.drawable.ic_media_pause), "Pause", serviceActionIntent(ACTION_PAUSE))
                : new NotificationCompat.Action(iconRes("ic_media_play", android.R.drawable.ic_media_play), "Play", serviceActionIntent(ACTION_PLAY)))
            .addAction(new NotificationCompat.Action(
                iconRes("ic_media_next", android.R.drawable.ic_media_next), "Next", serviceActionIntent(ACTION_NEXT)))
            .setDeleteIntent(serviceActionIntent(ACTION_STOP))
            .setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));

        PendingIntent openApp = openAppIntent();
        if (openApp != null) builder.setContentIntent(openApp);
        return builder.build();
    }

    private PendingIntent openAppIntent() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) return null;
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 0, launch, flags);
    }

    private int iconRes(String name, int fallback) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        return id != 0 ? id : fallback;
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private void stopSelfSafely() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
        stopForeground(true);
        if (mediaSession != null) mediaSession.setActive(false);
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }
}
