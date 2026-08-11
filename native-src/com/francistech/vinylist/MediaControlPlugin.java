package com.francistech.vinylist;

import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * JS-facing bridge for native playback notification / lock-screen controls.
 * Every call becomes a command Intent sent to MediaPlaybackNotificationService
 * (real MediaSessionCompat + MediaStyle notification -- not the Web
 * MediaSession API, which Android's WebView only partially supports).
 * Notification/lock-screen button taps flow back to JS as plugin events:
 * "play", "pause", "next", "previous", "seek", "stop".
 */
@CapacitorPlugin(
    name = "MediaControl",
    permissions = {
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class MediaControlPlugin extends Plugin implements MediaPlaybackNotificationService.Callback {

    @Override
    public void load() {
        super.load();
        MediaPlaybackNotificationService.setCallback(this);
    }

    @Override
    protected void handleOnDestroy() {
        MediaPlaybackNotificationService.setCallback(null);
        super.handleOnDestroy();
    }

    @PluginMethod
    public void checkNotificationPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias("notifications", call, "notifPermCallback");
    }

    @PermissionCallback
    private void notifPermCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(ret);
    }

    @PluginMethod
    public void updateMetadata(PluginCall call) {
        Intent intent = new Intent(getContext(), MediaPlaybackNotificationService.class)
            .setAction(MediaPlaybackNotificationService.ACTION_UPDATE_METADATA)
            .putExtra(MediaPlaybackNotificationService.EXTRA_TITLE, call.getString("title", ""))
            .putExtra(MediaPlaybackNotificationService.EXTRA_ARTIST, call.getString("artist", ""))
            .putExtra(MediaPlaybackNotificationService.EXTRA_ALBUM, call.getString("album", ""))
            .putExtra(MediaPlaybackNotificationService.EXTRA_DURATION_MS, longFromCall(call, "durationMs"))
            .putExtra(MediaPlaybackNotificationService.EXTRA_ARTWORK, call.getString("artworkBase64", ""));
        startServiceCompat(intent);
        call.resolve();
    }

    @PluginMethod
    public void updatePlaybackState(PluginCall call) {
        Boolean playing = call.getBoolean("isPlaying", false);
        Intent intent = new Intent(getContext(), MediaPlaybackNotificationService.class)
            .setAction(MediaPlaybackNotificationService.ACTION_UPDATE_STATE)
            .putExtra(MediaPlaybackNotificationService.EXTRA_IS_PLAYING, playing != null && playing)
            .putExtra(MediaPlaybackNotificationService.EXTRA_POSITION_MS, longFromCall(call, "positionMs"));
        startServiceCompat(intent);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent intent = new Intent(getContext(), MediaPlaybackNotificationService.class)
            .setAction(MediaPlaybackNotificationService.ACTION_STOP);
        startServiceCompat(intent);
        call.resolve();
    }

    private long longFromCall(PluginCall call, String key) {
        Integer v = call.getInt(key, 0);
        return v == null ? 0L : v.longValue();
    }

    private void startServiceCompat(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
        } catch (Exception e) {
            // Best-effort: if the OS refuses a background start (e.g. the app
            // was force-stopped), in-app playback still works, it just won't
            // have a system notification until the person reopens the app.
        }
    }

    /* -------- native -> JS: notification / lock-screen taps -------- */
    @Override public void onPlay() { notifyListeners("play", new JSObject()); }
    @Override public void onPause() { notifyListeners("pause", new JSObject()); }
    @Override public void onNext() { notifyListeners("next", new JSObject()); }
    @Override public void onPrevious() { notifyListeners("previous", new JSObject()); }
    @Override public void onStop() { notifyListeners("stop", new JSObject()); }

    @Override
    public void onSeekTo(long positionMs) {
        JSObject data = new JSObject();
        data.put("positionMs", positionMs);
        notifyListeners("seek", data);
    }
}
