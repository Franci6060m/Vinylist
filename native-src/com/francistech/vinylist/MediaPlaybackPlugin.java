package com.francistech.vinylist;

import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * JS-facing surface for MediaPlaybackService (see native-playback.js on the
 * web side). Starts/updates/stops the foreground service that backs the
 * lock-screen / notification transport controls, and relays button presses
 * from that service back up to JS as a "mediaControl" event.
 */
@CapacitorPlugin(name = "MediaPlayback")
public class MediaPlaybackPlugin extends Plugin implements MediaPlaybackService.Listener {

    @Override
    protected void handleOnDestroy() {
        MediaPlaybackService.setListener(null);
        super.handleOnDestroy();
    }

    @Override
    public void load() {
        MediaPlaybackService.setListener(this);
    }

    @PluginMethod
    public void start(PluginCall call) {
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        putMetadataExtras(intent, call);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to start playback service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void updateMetadata(PluginCall call) {
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        putMetadataExtras(intent, call);
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void updatePlaybackState(PluginCall call) {
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.putExtra("playing", call.getBoolean("playing", false));
        if (call.hasOption("positionMs")) intent.putExtra("positionMs", call.getInt("positionMs", 0));
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        intent.setAction(MediaPlaybackService.ACTION_STOP_SELF);
        getContext().startService(intent);
        call.resolve();
    }

    private void putMetadataExtras(Intent intent, PluginCall call) {
        if (call.hasOption("title")) intent.putExtra("title", call.getString("title", ""));
        if (call.hasOption("artist")) intent.putExtra("artist", call.getString("artist", ""));
        if (call.hasOption("album")) intent.putExtra("album", call.getString("album", ""));
        if (call.hasOption("artworkBase64")) intent.putExtra("artworkBase64", call.getString("artworkBase64", ""));
        if (call.hasOption("playing")) intent.putExtra("playing", call.getBoolean("playing", false));
        if (call.hasOption("positionMs")) intent.putExtra("positionMs", call.getInt("positionMs", 0));
        if (call.hasOption("durationMs")) intent.putExtra("durationMs", call.getInt("durationMs", 0));
    }

    // Called by MediaPlaybackService when a notification/lock-screen/hardware button is pressed.
    @Override
    public void onMediaAction(String action, long seekMs) {
        JSObject data = new JSObject();
        data.put("action", action);
        if ("seek".equals(action)) data.put("seekMs", seekMs);
        notifyListeners("mediaControl", data);
    }
}
