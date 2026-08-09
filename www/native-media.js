/* native-media.js
   Bridges the web app to the native MediaStoreAudio plugin when Vinylist is
   running inside the Capacitor-wrapped Android app. In a plain browser
   context, window.Capacitor is undefined and every function here safely
   no-ops so the existing picker/blob import path in index.html keeps
   working unchanged.
*/
(function (global) {
  const isNative = !!(global.Capacitor && global.Capacitor.isNativePlatform && global.Capacitor.isNativePlatform());

  async function checkPermission() {
    if (!isNative) return false;
    const { MediaStoreAudio } = global.Capacitor.Plugins;
    const res = await MediaStoreAudio.checkPermission();
    return !!res.granted;
  }

  async function requestPermission() {
    if (!isNative) return false;
    const { MediaStoreAudio } = global.Capacitor.Plugins;
    const res = await MediaStoreAudio.requestPermission();
    return !!res.granted;
  }

  /** Returns [{id, uri, title, artist, album, durationMs, sizeBytes, playableSrc}] */
  async function scanLibrary() {
    if (!isNative) return [];
    const { MediaStoreAudio } = global.Capacitor.Plugins;
    const granted = await checkPermission();
    if (!granted) {
      const ok = await requestPermission();
      if (!ok) return [];
    }
    const { tracks } = await MediaStoreAudio.scanAudio();
    return tracks.map(t => ({
      ...t,
      // convertFileSrc proxies content:// URIs through the Capacitor bridge
      // so they can be set directly as an <audio> element's src, with no
      // byte copy anywhere in the pipeline.
      playableSrc: global.Capacitor.convertFileSrc(t.uri),
    }));
  }

  /* -------------------------------------------------------------------
     Native backup of playlists/lyrics/settings, written alongside the
     existing IndexedDB/localStorage copy. Belt-and-suspenders: WebView
     storage is durable in practice, but a plain JSON file in app-private
     storage survives even aggressive OS storage-pressure cleanup.
     ------------------------------------------------------------------- */
  const BACKUP_PATH = 'vinylist-backup.json';

  async function backupAppData(dataObj) {
    if (!isNative || !global.Capacitor.Plugins.Filesystem) return;
    try {
      const { Filesystem, Directory, Encoding } = global.Capacitor.Plugins;
      await Filesystem.writeFile({
        path: BACKUP_PATH,
        data: JSON.stringify(dataObj),
        directory: Directory.Data,
        encoding: Encoding.UTF8,
      });
    } catch (e) {
      // best-effort, same as the existing store.set() calls in index.html
    }
  }

  async function restoreAppData() {
    if (!isNative || !global.Capacitor.Plugins.Filesystem) return null;
    try {
      const { Filesystem, Directory, Encoding } = global.Capacitor.Plugins;
      const res = await Filesystem.readFile({
        path: BACKUP_PATH,
        directory: Directory.Data,
        encoding: Encoding.UTF8,
      });
      return JSON.parse(res.data);
    } catch (e) {
      return null; // no backup yet, or unreadable — caller falls back to IndexedDB/localStorage
    }
  }

  /* -------------------------------------------------------------------
     Native playback notification / lock-screen controls, backed by a real
     Android foreground Service + MediaSessionCompat (see MediaControlPlugin
     + MediaPlaybackNotificationService in native-src/). This is what makes
     play/pause/next/previous work from the notification shade and lock
     screen, and what keeps the notification alive once the app itself is
     backgrounded or the WebView is frozen -- none of it depends on the Web
     MediaSession API, which Android's WebView only partially implements.
     ------------------------------------------------------------------- */
  function mediaControlPlugin() {
    return isNative ? global.Capacitor.Plugins.MediaControl : null;
  }

  async function updateNativeMetadata({ title, artist, album, durationMs, artworkBase64 }) {
    const plugin = mediaControlPlugin();
    if (!plugin) return;
    try {
      await plugin.updateMetadata({ title: title || '', artist: artist || '', album: album || '', durationMs: durationMs || 0, artworkBase64: artworkBase64 || '' });
    } catch (e) { /* best-effort */ }
  }

  async function updateNativePlaybackState({ isPlaying, positionMs }) {
    const plugin = mediaControlPlugin();
    if (!plugin) return;
    try { await plugin.updatePlaybackState({ isPlaying: !!isPlaying, positionMs: positionMs || 0 }); } catch (e) { /* best-effort */ }
  }

  async function stopNativeMedia() {
    const plugin = mediaControlPlugin();
    if (!plugin) return;
    try { await plugin.stop(); } catch (e) { /* best-effort */ }
  }

  async function requestNotificationPermission() {
    const plugin = mediaControlPlugin();
    if (!plugin) return false;
    try {
      const check = await plugin.checkNotificationPermission();
      if (check.granted) return true;
      const res = await plugin.requestNotificationPermission();
      return !!res.granted;
    } catch (e) { return false; }
  }

  /** Check-only variant (no system prompt) — used to reflect the real,
      already-decided Android permission state on screen (e.g. the Settings
      toggle), as opposed to requestNotificationPermission() above, which
      may pop the system dialog. Needed because the plain browser
      Notification API a WebView also exposes does NOT track Android's
      actual POST_NOTIFICATIONS runtime permission -- it stays stuck on
      "default"/ungranted inside the packaged app regardless of what the
      person actually granted, which is what made Settings permanently show
      "not enabled" there even after granting the real OS permission. */
  async function checkNotificationPermission() {
    const plugin = mediaControlPlugin();
    if (!plugin) return false;
    try {
      const res = await plugin.checkNotificationPermission();
      return !!res.granted;
    } catch (e) { return false; }
  }

  /** handlers: { play, pause, previous, next, seek }. Returns an unsubscribe function. */
  function onNativeMediaControl(handlers) {
    const plugin = mediaControlPlugin();
    if (!plugin) return () => {};
    const subs = Object.entries(handlers).map(([event, fn]) => plugin.addListener(event, fn));
    return () => { subs.forEach(s => s?.then?.(h => h.remove()).catch(() => {})); };
  }

  /** Downscales an image (blob:/data: URL) to a small square JPEG and returns
      raw base64 (no data: prefix) -- kept small on purpose, since it travels
      to the native side as an Intent extra for the notification's artwork. */
  async function artworkToBase64(url, maxSize = 320) {
    if (!url) return '';
    const img = await new Promise((resolve, reject) => {
      const el = new Image();
      el.crossOrigin = 'anonymous';
      el.onload = () => resolve(el);
      el.onerror = reject;
      el.src = url;
    });
    const side = Math.min(maxSize, Math.max(img.width, img.height) || maxSize);
    const canvas = document.createElement('canvas');
    canvas.width = side; canvas.height = side;
    const ctx = canvas.getContext('2d');
    const scale = Math.max(side / img.width, side / img.height);
    const dw = img.width * scale, dh = img.height * scale;
    ctx.drawImage(img, (side - dw) / 2, (side - dh) / 2, dw, dh);
    const dataUrl = canvas.toDataURL('image/jpeg', 0.82);
    return dataUrl.split(',')[1] || '';
  }

  global.VinylistNative = {
    isNative,
    checkPermission,
    requestPermission,
    scanLibrary,
    backupAppData,
    restoreAppData,
    updateNativeMetadata,
    updateNativePlaybackState,
    stopNativeMedia,
    requestNotificationPermission,
    checkNotificationPermission,
    onNativeMediaControl,
    artworkToBase64,
  };
})(window);
