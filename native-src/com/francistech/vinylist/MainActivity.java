package com.francistech.vinylist;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

/**
 * Why the override below exists (fixes: notification "Next"/"Previous" only
 * taking effect after reopening the app):
 *
 * Capacitor's BridgeActivity calls webView.onPause() from Activity#onPause(),
 * which Android is allowed to treat as "suspend this WebView's JS/DOM
 * processing" -- not just animations. MediaPlaybackService keeps the process
 * alive (foreground service) and keeps dispatching button presses via
 * MediaPlaybackPlugin#onMediaAction -> notifyListeners() regardless, but
 * those bridge calls just queue up against a paused WebView instead of
 * running the "next"/"previous" JS handlers in index.html. The queued call
 * only gets flushed once the Activity resumes -- i.e. once you reopen the
 * app -- which matches the reported symptom exactly.
 *
 * Immediately resuming the WebView after super.onPause()/onStop() keeps its
 * JS execution "live" while the Activity itself stays backgrounded/stopped,
 * so notification/lock-screen button presses are handled right away. This
 * is gated on MediaPlaybackService actually running, so Vinylist doesn't pay
 * for an always-on WebView when nothing is playing.
 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaStorePlugin.class);
        registerPlugin(MediaPlaybackPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onPause() {
        super.onPause();
        keepWebViewLiveIfPlaybackActive();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebViewLiveIfPlaybackActive();
    }

    private void keepWebViewLiveIfPlaybackActive() {
        if (!MediaPlaybackService.isRunning()) return;
        if (getBridge() == null || getBridge().getWebView() == null) return;
        getBridge().getWebView().onResume();
        getBridge().getWebView().resumeTimers();
    }
}
