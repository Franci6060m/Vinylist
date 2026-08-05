/* native-media.js
   Bridges the web app to the native MediaStoreAudio plugin when Vinylist is
   running inside the Capacitor-wrapped Android app. In a plain browser/PWA
   context (e.g. the Netlify-hosted site), window.Capacitor is undefined and
   every function here safely no-ops so the existing picker/blob import path
   in index.html keeps working unchanged.
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

  global.VinylistNative = {
    isNative,
    checkPermission,
    requestPermission,
    scanLibrary,
    backupAppData,
    restoreAppData,
  };
})(window);
