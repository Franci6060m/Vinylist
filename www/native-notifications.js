/* native-notifications.js
   Bridges the "Notifications" permission + delivery to Capacitor's
   LocalNotifications plugin when Vinylist is running inside the native
   Android app. The plain web `Notification` API is not reliably available
   inside an Android WebView (it's frequently `undefined` there even though
   the surrounding app is a real native app) — that mismatch is exactly why
   "Notifications aren't supported in this browser" was showing up. In a
   plain browser/PWA context (e.g. the Netlify-hosted site) this falls back
   to the standard web Notification API unchanged.
*/
(function (global) {
  const isNative = !!(global.Capacitor && global.Capacitor.isNativePlatform && global.Capacitor.isNativePlatform());

  function webAvailable() {
    return typeof Notification !== 'undefined';
  }

  async function isSupported() {
    if (isNative) return !!(global.Capacitor.Plugins && global.Capacitor.Plugins.LocalNotifications);
    return webAvailable();
  }

  /** Returns 'granted' | 'denied' | 'prompt' | 'unsupported' */
  async function getPermissionState() {
    if (isNative) {
      const { LocalNotifications } = global.Capacitor.Plugins;
      if (!LocalNotifications) return 'unsupported';
      const res = await LocalNotifications.checkPermissions();
      return res.display || 'prompt';
    }
    if (!webAvailable()) return 'unsupported';
    return Notification.permission === 'default' ? 'prompt' : Notification.permission;
  }

  async function requestPermission() {
    if (isNative) {
      const { LocalNotifications } = global.Capacitor.Plugins;
      if (!LocalNotifications) return { granted: false, state: 'unsupported' };
      const res = await LocalNotifications.requestPermissions();
      return { granted: res.display === 'granted', state: res.display };
    }
    if (!webAvailable()) return { granted: false, state: 'unsupported' };
    if (Notification.permission === 'denied') return { granted: false, state: 'denied' };
    const perm = await Notification.requestPermission();
    return { granted: perm === 'granted', state: perm };
  }

  let nextId = 1;
  /** Fires a local notification right now (used for e.g. "weekly report is ready"). */
  async function notify(title, body) {
    if (isNative) {
      const { LocalNotifications } = global.Capacitor.Plugins;
      if (!LocalNotifications) return;
      try {
        await LocalNotifications.schedule({
          notifications: [{ id: nextId++, title, body, schedule: { at: new Date(Date.now() + 200) } }],
        });
      } catch (e) { /* best-effort, same posture as the other native bridges in this app */ }
      return;
    }
    if (!webAvailable() || Notification.permission !== 'granted') return;
    try { new Notification(title, { body }); } catch (e) { /* best-effort */ }
  }

  global.VinylistNotifications = { isNative, isSupported, getPermissionState, requestPermission, notify };
})(window);
