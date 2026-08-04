/* native-playback.js
   Keeps audio playing and shows full lock-screen / notification transport
   controls while Vinylist is backgrounded or the phone is locked, by
   mirroring playback state into a real Android foreground service
   (MediaPlaybackService, backed by MediaSessionCompat) via the native
   "MediaPlayback" Capacitor plugin.

   Why this exists: the web Media Session API (navigator.mediaSession,
   used elsewhere in index.html) only sets metadata for the browser/tab to
   *optionally* surface as a system notification. A generic Android WebView
   embedded in a native app has no OS-level guarantee of doing that, and
   more importantly nothing keeps the WebView's JS/audio timers alive once
   the screen locks or the app goes to the background — Android is free to
   suspend/kill it. A foreground service with its own MediaSessionCompat is
   the standard, supported way to keep playback alive and to get full,
   reliable lock-screen controls in a native Android app.

   No-ops entirely outside the native app (isNative stays false), so the
   Netlify-hosted PWA keeps using the plain navigator.mediaSession path in
   index.html unchanged.
*/
(function (global) {
  const isNative = !!(global.Capacitor && global.Capacitor.isNativePlatform && global.Capacitor.isNativePlatform());
  const plugin = isNative && global.Capacitor.Plugins ? global.Capacitor.Plugins.MediaPlayback : null;

  /** Converts a blob:/data: artwork URL into a data: URL the native side can decode.
   *  Native Java code cannot dereference blob: URLs (they only resolve inside this
   *  page's realm), so anything that isn't already a data: URL gets re-fetched and
   *  re-encoded here first. */
  async function toArtworkBase64(url) {
    if (!plugin || !url) return '';
    if (url.startsWith('data:')) return url;
    if (!url.startsWith('blob:')) return '';
    try {
      const res = await fetch(url);
      const blob = await res.blob();
      return await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onloadend = () => resolve(reader.result);
        reader.onerror = reject;
        reader.readAsDataURL(blob);
      });
    } catch (e) {
      return '';
    }
  }

  async function ensureNotificationPermission() {
    // Android 13+ requires POST_NOTIFICATIONS for the foreground-service
    // notification (and thus the lock-screen controls) to actually be
    // visible. Ask opportunistically the first time playback starts,
    // independent of the separate in-app "Notifications" settings toggle.
    // No-op / resolves instantly if already granted or denied.
    try { await global.VinylistNotifications?.requestPermission(); } catch (e) { /* best-effort */ }
  }

  async function start(meta) {
    if (!plugin) return;
    try {
      await ensureNotificationPermission();
      await plugin.start(meta);
    } catch (e) { /* best-effort */ }
  }
  async function updateMetadata(meta) {
    if (!plugin) return;
    try { await plugin.updateMetadata(meta); } catch (e) { /* best-effort */ }
  }
  async function updatePlaybackState(state) {
    if (!plugin) return;
    try { await plugin.updatePlaybackState(state); } catch (e) { /* best-effort */ }
  }
  async function stop() {
    if (!plugin) return;
    try { await plugin.stop(); } catch (e) { /* best-effort */ }
  }
  /** handler receives { action: 'play'|'pause'|'next'|'previous'|'seek'|'stop', seekMs? } */
  function onControl(handler) {
    if (!plugin) return () => {};
    const sub = plugin.addListener('mediaControl', handler);
    return () => { try { sub.remove(); } catch (e) {} };
  }

  global.VinylistPlayback = { isNative: !!plugin, toArtworkBase64, start, updateMetadata, updatePlaybackState, stop, onControl };
})(window);
