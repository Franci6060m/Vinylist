package com.francistech.vinylist;

import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

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
 *
 * Also emits "audioRouteChanged" to JS whenever a Bluetooth/wired/USB
 * *output* device connects or disconnects. This exists because the WebView's
 * Web Audio graph (see ensureGraph() in index.html) binds its output to
 * whatever route was active when the AudioContext was created and does NOT
 * follow later route changes on its own -- unlike a plain <audio> element.
 * The JS side uses this event to tear down and rebuild that graph against
 * the newly-current route. This is purely additive/best-effort: if
 * registration fails on some device, playback behaves exactly as it did
 * before this existed.
 */
@CapacitorPlugin(
    name = "MediaControl",
    permissions = {
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class MediaControlPlugin extends Plugin implements MediaPlaybackNotificationService.Callback {

    private AudioDeviceCallback audioDeviceCallback;

    @Override
    public void load() {
        super.load();
        MediaPlaybackNotificationService.setCallback(this);
        registerAudioDeviceCallback();
    }

    @Override
    protected void handleOnDestroy() {
        MediaPlaybackNotificationService.setCallback(null);
        unregisterAudioDeviceCallback();
        super.handleOnDestroy();
    }

    private void registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT < 23) return; // AudioDeviceCallback requires API 23+
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            audioDeviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    notifyIfRelevant(addedDevices);
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    notifyIfRelevant(removedDevices);
                }
            };
            am.registerAudioDeviceCallback(audioDeviceCallback, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            // best-effort -- worst case this feature never fires and
            // playback behaves as it did before it existed
        }
    }

    private void unregisterAudioDeviceCallback() {
        if (audioDeviceCallback == null) return;
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.unregisterAudioDeviceCallback(audioDeviceCallback);
        } catch (Exception e) { /* best-effort */ }
        audioDeviceCallback = null;
    }

    /** Only worth telling JS about output ("sink") devices -- input-only
        devices (mics) don't affect where playback audio goes. */
    private void notifyIfRelevant(AudioDeviceInfo[] devices) {
        if (devices == null) return;
        for (AudioDeviceInfo d : devices) {
            if (!d.isSink()) continue;
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                notifyListeners("audioRouteChanged", new JSObject());
                return;
            }
        }
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
