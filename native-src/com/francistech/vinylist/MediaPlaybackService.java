package com.francistech.vinylist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

/**
 * Keeps Vinylist's audio "alive" (from the OS's point of view) and shows the
 * lock-screen / notification transport controls while the app is backgrounded
 * or the phone is locked.
 *
 * Actual audio decode/playback stays in the WebView's <audio> element -- this
 * service does not play audio itself. What it provides is the thing a plain
 * WebView cannot: a foreground service (so Android doesn't suspend the app
 * process in the background) tied to a MediaSessionCompat (so the system
 * renders real lock-screen / notification-shade transport controls and
 * forwards hardware media-button presses back to us). Button presses are
 * relayed to JS via MediaPlaybackPlugin#onMediaAction, which calls back into
 * the existing togglePlay/playNext/playPrev/seek functions in index.html.
 *
 * Note: if Android reclaims the whole app process under memory pressure the
 * WebView goes with it, same as any other foreground-service-backed player
 * whose playback engine lives in the host process. Keeping this service
 * foreground with an ongoing notification is what makes that reclaim far
 * less likely during normal background/lock-screen use.
 */
public class MediaPlaybackService extends Service {

    private static final String TAG = "VinylistPlayback";
    private static final String CHANNEL_ID = "vinylist_playback";
    private static final int NOTIFICATION_ID = 4242;

    public static final String ACTION_STOP_SELF = "com.francistech.vinylist.action.STOP_SELF";

    public interface Listener {
        void onMediaAction(String action, long seekMs);
    }

    private static Listener listener;
    public static void setListener(Listener l) { listener = l; }

    private MediaSessionCompat mediaSession;
    private String title = "", artist = "", album = "";
    private boolean playing = false;
    private long positionMs = 0, durationMs = 0;
    private Bitmap artwork = null;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        mediaSession = new MediaSessionCompat(this, "VinylistSession");
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { dispatch("play", 0); }
            @Override public void onPause() { dispatch("pause", 0); }
            @Override public void onSkipToNext() { dispatch("next", 0); }
            @Override public void onSkipToPrevious() { dispatch("previous", 0); }
            @Override public void onSeekTo(long pos) { dispatch("seek", pos); }
            @Override public void onStop() { dispatch("stop", 0); }
        });
        mediaSession.setActive(true);
    }

    private void dispatch(String action, long seekMs) {
        if (listener != null) listener.onMediaAction(action, seekMs);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP_SELF.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Lets hardware/Bluetooth media-button broadcasts reach mediaSession's callback.
        MediaButtonReceiver.handleIntent(mediaSession, intent);

        readExtras(intent);
        updateSessionAndNotification();
        return START_STICKY;
    }

    private void readExtras(Intent intent) {
        if (intent.hasExtra("title")) title = safe(intent.getStringExtra("title"));
        if (intent.hasExtra("artist")) artist = safe(intent.getStringExtra("artist"));
        if (intent.hasExtra("album")) album = safe(intent.getStringExtra("album"));
        if (intent.hasExtra("playing")) playing = intent.getBooleanExtra("playing", false);
        if (intent.hasExtra("positionMs")) positionMs = intent.getIntExtra("positionMs", 0);
        if (intent.hasExtra("durationMs")) durationMs = intent.getIntExtra("durationMs", 0);

        String artB64 = intent.getStringExtra("artworkBase64");
        if (artB64 != null && !artB64.isEmpty()) {
            try {
                String raw = artB64.contains(",") ? artB64.substring(artB64.indexOf(',') + 1) : artB64;
                byte[] bytes = Base64.decode(raw, Base64.DEFAULT);
                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (decoded != null) artwork = decoded;
            } catch (Exception e) {
                Log.w(TAG, "Failed to decode artwork", e);
            }
        }
    }

    private String safe(String s) { return s == null ? "" : s; }

    private void updateSessionAndNotification() {
        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        if (artwork != null) metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
        mediaSession.setMetadata(metaBuilder.build());

        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
            | PlaybackStateCompat.ACTION_STOP;
        int state = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        // Reporting position + playback speed here lets the OS extrapolate the
        // scrubber/elapsed time on its own -- we don't need to keep pushing
        // position updates every second.
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, positionMs, playing ? 1f : 0f)
            .build();
        mediaSession.setPlaybackState(playbackState);

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentPI = PendingIntent.getActivity(this, 0, contentIntent, piFlags);

        PendingIntent deletePI = PendingIntent.getService(
            this, 0, new Intent(this, MediaPlaybackService.class).setAction(ACTION_STOP_SELF), piFlags
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(getApplicationInfo().icon)
            .setContentTitle(title.isEmpty() ? "Vinylist" : title)
            .setContentText(artist)
            .setSubText(album)
            .setContentIntent(contentPI)
            .setDeleteIntent(deletePI)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(new NotificationCompat.Action(android.R.drawable.ic_media_previous, "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)))
            .addAction(playing
                ? new NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE))
                : new NotificationCompat.Action(android.R.drawable.ic_media_play, "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)))
            .addAction(new NotificationCompat.Action(android.R.drawable.ic_media_next, "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)))
            .setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));

        if (artwork != null) builder.setLargeIcon(artwork);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Playback controls", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows what's playing and lets you control it from the lock screen");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        mediaSession.setActive(false);
        mediaSession.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
