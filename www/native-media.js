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
     Save-a-copy-to-phone for lyrics: writes each song's lyrics out as a
     plain, human-readable .txt file into the public Downloads/Music
     Lyrics folder -- visible in any file manager / Files app, and
     independent of the app's own storage. This is a belt-and-suspenders
     copy for the user to keep, NOT the app's primary lyrics store
     (that's still songs[].lyrics, backed up via backupAppData above);
     if the in-app copy is ever lost, readLyricsFromPhone()/listPhoneLyrics()
     below let it be read back and re-attached by matching artist/title.

     This goes through the custom MediaStoreAudio native plugin (see
     MediaStorePlugin.java) rather than @capacitor/filesystem's
     Directory.Documents, because writing a raw file path into a public
     folder like that is blocked by Android's scoped storage on
     Android 11+ regardless of any permission the user grants -- it isn't
     something a "Files and media" toggle can fix. MediaStore.Downloads is
     the officially-supported way to place a file in a public folder
     without needing any runtime permission on Android 10+.
     ------------------------------------------------------------------- */

  /** Turns a song's artist/title into a filesystem-safe but still
      human-readable file name -- strips characters Android file names
      don't allow, keeps everything else recognizable in a file browser. */
  function lyricsFileName(song) {
    const clean = (s) => (s || 'Unknown').replace(/[\\/:*?"<>|]/g, '').trim().slice(0, 80);
    return `${clean(song?.artist)} - ${clean(song?.title)}.txt`;
  }

  /** Writes the given raw lyrics text to the phone's Downloads/Music Lyrics
      folder for this song. Returns {ok:true} on success, or {ok:false, reason}
      so the caller can show a specific toast (not native, empty lyrics,
      permission/write failure). */
  async function saveLyricsToPhone(song, lyricsRaw) {
    if (!isNative || !global.Capacitor.Plugins.MediaStoreAudio) return { ok: false, reason: 'not-native' };
    if (!lyricsRaw || !lyricsRaw.trim()) return { ok: false, reason: 'empty' };
    try {
      const { MediaStoreAudio } = global.Capacitor.Plugins;
      // No-op on Android 10+; only actually prompts on Android 9 and below.
      const perm = await MediaStoreAudio.requestLegacyStoragePermission();
      if (!perm.granted) return { ok: false, reason: 'permission' };
      const res = await MediaStoreAudio.saveLyricsFile({ fileName: lyricsFileName(song), content: lyricsRaw });
      return res.ok ? { ok: true } : { ok: false, reason: res.reason || 'write-failed' };
    } catch (e) {
      return { ok: false, reason: e?.message || 'write-failed' };
    }
  }

  /** Reads back a single song's lyrics copy from the phone's Downloads/Music
      Lyrics folder, if one exists there -- used to re-attach lyrics to
      a song that lost them in-app (e.g. a fresh install) but still has
      a copy sitting out in public storage. Returns raw text or null. */
  async function readLyricsFromPhone(song) {
    if (!isNative || !global.Capacitor.Plugins.MediaStoreAudio) return null;
    try {
      const { MediaStoreAudio } = global.Capacitor.Plugins;
      const res = await MediaStoreAudio.readLyricsFile({ fileName: lyricsFileName(song) });
      return res?.content || null;
    } catch (e) {
      return null; // no phone copy for this song, or unreadable
    }
  }

  /** Lists every file name currently sitting in the phone's Music
      Lyrics folder -- used for a library-wide recovery pass that
      matches file names back to songs by artist/title, rather than
      checking one song at a time. */
  async function listPhoneLyrics() {
    if (!isNative || !global.Capacitor.Plugins.MediaStoreAudio) return [];
    try {
      const { MediaStoreAudio } = global.Capacitor.Plugins;
      const res = await MediaStoreAudio.listLyricsFiles();
      return res.files || [];
    } catch (e) {
      return []; // folder doesn't exist yet -- nothing has been saved there yet
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

  /** Fires cb() whenever a Bluetooth/wired/USB output device connects or
      disconnects (see MediaControlPlugin's AudioDeviceCallback). Returns an
      unsubscribe function, matching onNativeMediaControl's shape below.
      No-ops (returns a no-op unsubscribe) outside the native app. */
  function onAudioRouteChanged(cb) {
    const plugin = mediaControlPlugin();
    if (!plugin) return () => {};
    const sub = plugin.addListener('audioRouteChanged', cb);
    return () => { sub?.then?.(h => h.remove()).catch(() => {}); };
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

  /* -------------------------------------------------------------------
     Battery-optimization exemption (see BatteryPlugin.java). Separate from
     the notification permission above -- this is what actually keeps the
     process alive through long background playback, especially on OEM
     skins (Samsung etc.) that kill backgrounded apps more aggressively
     than stock Android's own Doze/App Standby.
     ------------------------------------------------------------------- */
  function batteryPlugin() {
    return isNative ? global.Capacitor.Plugins.Battery : null;
  }

  async function isIgnoringBatteryOptimizations() {
    const plugin = batteryPlugin();
    if (!plugin) return true; // nothing to exempt outside the native app
    try {
      const res = await plugin.isIgnoringBatteryOptimizations();
      return !!res.ignoring;
    } catch (e) { return true; }
  }

  /** Fires the system "allow to run in background?" dialog. Caller should
      re-check isIgnoringBatteryOptimizations() on the next app resume,
      since not every OEM reports the result back synchronously. */
  async function requestIgnoreBatteryOptimizations() {
    const plugin = batteryPlugin();
    if (!plugin) return;
    try { await plugin.requestIgnoreBatteryOptimizations(); } catch (e) { /* best-effort */ }
  }

  /** Fallback for OEM builds where the direct request above doesn't stick --
      opens this app's own page in system Settings so the person can find
      the battery toggle by hand. */
  async function openBatterySettings() {
    const plugin = batteryPlugin();
    if (!plugin) return;
    try { await plugin.openBatterySettings(); } catch (e) { /* best-effort */ }
  }

  /* -------------------------------------------------------------------
     In-app update: checks your GitHub repo's latest release directly (see
     AppUpdatePlugin in native-src/) — no manifest file to hand-maintain,
     downloads the APK it points to with progress, and hands it to the
     system installer. The final "Install" tap is Android's own
     confirmation dialog -- nothing here bypasses that, since a normal app
     isn't allowed to.
     ------------------------------------------------------------------- */
  function appUpdatePlugin() {
    return isNative ? global.Capacitor.Plugins.AppUpdate : null;
  }

  async function getCurrentAppVersion() {
    const plugin = appUpdatePlugin();
    if (!plugin) return null;
    try { return await plugin.getCurrentVersion(); } catch (e) { return null; }
  }

  /** manifestUrl is a GitHub API "releases/latest" URL — e.g.
      https://api.github.com/repos/<owner>/<repo>/releases/latest — which
      GitHub itself always keeps pointed at the newest release, so this URL
      is set once and never changes. Returns null on any failure (offline,
      bad URL, malformed JSON) rather than throwing, so callers can just
      show "couldn't check right now" and move on. */
  async function checkForUpdate(manifestUrl) {
    const plugin = appUpdatePlugin();
    if (!plugin || !manifestUrl) return null;
    try { return await plugin.checkForUpdate({ url: manifestUrl }); } catch (e) { return null; }
  }

  async function canInstallUpdates() {
    const plugin = appUpdatePlugin();
    if (!plugin) return false;
    try { const res = await plugin.canInstallPackages(); return !!res.allowed; } catch (e) { return false; }
  }

  /** Sends the person to Android's "install unknown apps" settings screen
      for this app specifically. Fire-and-forget navigation — check
      canInstallUpdates() again once they return to the app. */
  async function requestInstallPermission() {
    const plugin = appUpdatePlugin();
    if (!plugin) return;
    try { await plugin.requestInstallPermission(); } catch (e) { /* best-effort */ }
  }

  /** onProgress receives {percent, bytesDownloaded, totalBytes} as the
      download streams in. Resolves to the downloaded file's local path, or
      throws on failure (caller should catch and show an error/retry). */
  async function downloadUpdate(apkUrl, onProgress) {
    const plugin = appUpdatePlugin();
    if (!plugin) throw new Error('Not running in the native app');
    let sub = null;
    if (typeof onProgress === 'function') {
      sub = plugin.addListener('downloadProgress', onProgress);
    }
    try {
      const res = await plugin.downloadUpdate({ url: apkUrl });
      return res.filePath;
    } finally {
      sub?.then?.(h => h.remove()).catch(() => {});
    }
  }

  async function installUpdate(filePath) {
    const plugin = appUpdatePlugin();
    if (!plugin) return;
    await plugin.installUpdate({ filePath });
  }

  /* -------------------------------------------------------------------
     Generic local notifications (re-engagement nudge, "update available",
     upload confirmation) via the official @capacitor/local-notifications
     plugin -- separate from MediaControl's notification above, which is
     specifically the playback foreground-service notification. Both ask
     for the same underlying Android POST_NOTIFICATIONS permission, but the
     two plugins track/request it independently, so this has its own
     permission check rather than assuming MediaControl's covers it.
     ------------------------------------------------------------------- */
  function localNotifPlugin() {
    return isNative ? global.Capacitor.Plugins.LocalNotifications : null;
  }

  async function requestLocalNotificationPermission() {
    const plugin = localNotifPlugin();
    if (!plugin) return false;
    try {
      const check = await plugin.checkPermissions();
      if (check.display === 'granted') return true;
      const res = await plugin.requestPermissions();
      return res.display === 'granted';
    } catch (e) { return false; }
  }

  async function checkLocalNotificationPermission() {
    const plugin = localNotifPlugin();
    if (!plugin) return false;
    try { const res = await plugin.checkPermissions(); return res.display === 'granted'; } catch (e) { return false; }
  }

  /** id must be a stable integer per notification "slot" (e.g. one fixed id
      for the inactivity reminder, another for update-available) -- scheduling
      a new notification with the same id replaces/reschedules that slot
      instead of stacking duplicates. `at` is a Date for a future delivery
      time; omit it (or pass a Date already in the past/near-now) to fire
      essentially immediately, which is how the upload-confirmation and
      update-available notifications use this same function. */
  async function scheduleLocalNotification({ id, title, body, at }) {
    const plugin = localNotifPlugin();
    if (!plugin) return false;
    try {
      await plugin.schedule({
        notifications: [{
          id, title, body,
          schedule: at ? { at } : undefined,
        }],
      });
      return true;
    } catch (e) { return false; }
  }

  async function cancelLocalNotification(id) {
    const plugin = localNotifPlugin();
    if (!plugin) return;
    try { await plugin.cancel({ notifications: [{ id }] }); } catch (e) { /* best-effort */ }
  }

  global.VinylistNative = {
    isNative,
    checkPermission,
    requestPermission,
    scanLibrary,
    backupAppData,
    restoreAppData,
    lyricsFileName,
    saveLyricsToPhone,
    readLyricsFromPhone,
    listPhoneLyrics,
    updateNativeMetadata,
    updateNativePlaybackState,
    onAudioRouteChanged,
    stopNativeMedia,
    requestNotificationPermission,
    checkNotificationPermission,
    onNativeMediaControl,
    isIgnoringBatteryOptimizations,
    requestIgnoreBatteryOptimizations,
    openBatterySettings,
    artworkToBase64,
    getCurrentAppVersion,
    checkForUpdate,
    canInstallUpdates,
    requestInstallPermission,
    downloadUpdate,
    installUpdate,
    requestLocalNotificationPermission,
    checkLocalNotificationPermission,
    scheduleLocalNotification,
    cancelLocalNotification,
  };
})(window);
