# Vinylist — building the native Android app locally

**If you're using Codemagic, skip this file — see `README-CODEMAGIC.md`
instead, which automates everything below.** This file is only for building
on your own machine (no CI). Nothing here touches your Netlify site — that
keeps working as-is for browser/desktop users; this produces a second,
separate distributable (the APK/AAB) for native install.

## Prerequisites (one-time)
- Node.js 18+
- Android Studio (includes the Android SDK)

## 1. Install dependencies
```
cd vinylist-capacitor
npm install
```

## 2. Generate the Android project and inject the native plugin
```
./scripts/prepare-android.sh
```
This runs `npx cap add android`, copies `MediaStorePlugin.kt` and
`MainActivity.java` from `native-src/` into the generated project, merges in
the manifest permissions, and runs `npx cap sync android`. Same script
Codemagic runs — so local and CI builds stay identical.

## 3. Open in Android Studio and run
```
npx cap open android
```
Press Run to install a debug build on a connected device/emulator and test
the "Use Device Library" flow — it'll prompt for the audio permission on
first use.

## 4. Build a signed release (for real distribution)
In Android Studio: **Build → Generate Signed Bundle / APK**.
- Create (or reuse) a keystore — **keep this file and its passwords safe**.
  Losing it means you can never ship an update to the same app; Android
  requires every update to be signed with the same key.
- Choose **Android App Bundle (.aab)** for Play Store, or **APK** if you're
  distributing the file directly (sideloading, your own site, etc.).

## 5. Distribute
- **Play Store**: upload the `.aab` through the Play Console. Standard path,
  auto-updates, no separate download page needed.
- **Direct APK**: host the signed `.apk` on your Netlify site (or anywhere)
  as a plain file link. Users tap it, allow "install unknown apps" once for
  that source, and it installs like any native app. You handle update
  notifications yourself (Play Store won't).

## Notification / lock-screen media controls (native, not WebView)
Previously the only background-playback hook was the Web MediaSession API
(`navigator.mediaSession`), which Android's WebView only partially supports —
that's why the notification wasn't showing up and controls stopped working
once the app left the foreground.

That's now backed by real native Android code:
- `MediaPlaybackNotificationService.java` — a foreground `Service` holding a
  `MediaSessionCompat` and posting a proper `MediaStyle` notification with
  working previous / play-pause / next actions, plus a lock-screen player.
- `MediaControlPlugin.java` — the Capacitor plugin `MediaControl` that JS
  talks to (`native-media.js` → `updateNativeMetadata`,
  `updateNativePlaybackState`, `onNativeMediaControl`, etc.). Notification and
  lock-screen taps come back into the same `togglePlay` / `playPrev` /
  `playNext` / `seek` functions the on-screen controls already use.
- `prepare-android.sh` now also copies these files in, registers the service
  in the manifest with `foregroundServiceType="mediaPlayback"`, adds the
  `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions, and
  adds the `androidx.media:media` Gradle dependency automatically — nothing
  extra to run by hand, it happens on the next Codemagic build (or a local
  `./scripts/prepare-android.sh`).

## Battery optimization exemption (why playback can still get killed)
A foreground Service + wake lock is normally enough to survive Android's
own Doze/App Standby, but it is **not** enough on its own against an OEM's
separate battery manager (Samsung's especially) — that can force-stop a
backgrounded app regardless. `BatteryPlugin.java` lets the app request the
standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption directly (Settings
→ Apps → Vinylist → Battery → Unrestricted), surfaced in-app under
Settings → Permissions → "Unrestricted battery use". Note this is a
special, user-visible permission, not a runtime "dangerous" one — it will
never show up on Android's own App info → Permissions screen (that screen
only lists runtime-permission groups like "Files and media"), which is
expected, not a bug.

**Scope note:** this fixes the actual bug (no notification / dead controls
once backgrounded) by keeping a real foreground service + native
`MediaSessionCompat` alive, which is what stops Android from freezing
playback under Doze/App Standby. Audio decoding itself still happens through
the `<audio>` element in the WebView (all the crossfade/EQ/visualizer code
depends on the Web Audio API) — so if Android fully kills the app's process
under very aggressive memory pressure, playback stops with it, same as any
other WebView-based player. Moving playback itself onto a native player
(ExoPlayer) so it survives process death too is a bigger, separate project;
what's here covers the normal "notification doesn't show / controls don't
work" case.

## Neumorphism skin
The existing "Neumorphism" skin (`skin: 'neu'`) already derives its soft
light/dark shadow pair live from whichever color theme is active. Two themes
were added specifically to match the reference look — a dark graphite dial
with a glowing cyan ring (**Cyan Pulse (Neu)**), and a pale soft-UI variant
(**Soft Steel (Neu)**) — pick either from Settings → Theme (works with any
skin, but was tuned with the neu dial in mind). The volume/preamp/crossfade
sliders now also render as an inset groove with a glowing accent-color fill
when the neu skin is active, matching the lit progress bars in the reference
art.

## Notes on what changed in the web app
- `native-media.js` — bridges to the native plugin; every function safely
  no-ops when `window.Capacitor` isn't present, so the Netlify-hosted PWA is
  unaffected.
- `index.html` — added `importFromNativeLibrary()` (uses MediaStore content
  URIs directly, no byte copy). Playlists/lyrics/settings are saved through
  `@capacitor/preferences` (native SharedPreferences — durable, not just
  WebView localStorage) as the primary store, mirrored to a JSON backup file
  via `@capacitor/filesystem` as a second line of defense, and flushed
  immediately on an explicit save and whenever the app is backgrounded (not
  just on a debounce timer) so an edit made right before closing the app
  isn't lost. The existing folder-picker/blob import path is untouched and
  still runs for web/PWA users.

## Stable song identity for device-library tracks (lyrics/playlists/likes)
Device-scanned songs used to be identified by MediaStore's row `_ID`, which
is a database surrogate key — Android reassigns it whenever the media
scanner re-indexes the library (reboot, firmware update, some phones'
storage-cleanup tools), silently orphaning every saved lyric, playlist
entry, and favorite tied to the old value. `nativeTrackFingerprint()` in
`index.html` now derives identity from `displayName+size` instead (the same
scheme picker-imported files always used), with a `title+artist+duration`
middle tier for the rare row missing `displayName`, and `_id` only as a last
resort. Every library scan (startup and the manual "re-link library"
action) also actively repairs any playlist or saved-lyrics entry still
filed under an old `_id`, rather than just falling back to it at lookup
time — this is what makes a stale reference self-heal instead of staying
broken forever.

## Notification no longer disappears while backgrounded
`MediaPlaybackNotificationService`'s notification used to call
`.setOngoing(isPlaying)`, which made it an ordinary, swipeable notification
the instant `isPlaying` read false for any reason — including a brief,
transient blip during a track crossfade, not just a genuine pause. Once
swipeable, a stray notification-shade interaction or Android 13+'s own
auto-hide-for-paused-media behavior could clear it entirely, while playback
(driven independently by the WebView's own audio pipeline) kept going right
through it. It's now pinned (`setOngoing(true)`) for the entire life of a
playback session and only ever removed via the explicit Stop action.

## In-app update check + install
`AppUpdatePlugin.java` checks a GitHub repo's latest release directly (no
separate manifest file to hand-maintain — the release tag and description
*are* the manifest), downloads the attached APK with progress, and hands it
to Android's own package installer. `native-media.js` bridges it
(`checkForUpdate`, `downloadUpdate`, `installUpdate`, etc.) and
`index.html`'s Settings screen has the check/progress/install UI.

Release workflow this is built around:
1. Bump `versionName` in the repo-root `version.json` (e.g. `"1.1.0"`).
   `prepare-android.sh` derives the native `versionCode` from it
   automatically (major\*1,000,000 + minor\*1,000 + patch) — the exact same
   formula `AppUpdatePlugin` uses to parse a GitHub release tag at runtime,
   so the two always compare correctly against each other.
2. Build, get your signed APK.
3. On GitHub (a public repo you control — see below), create a release
   tagged to match, e.g. `v1.1.0`. Write your release notes directly in the
   description box — that's what shows in the app.
4. Attach the APK, **always named exactly `vinylist-latest.apk`**. This
   fixed filename is what lets the app find the right asset without a
   separate manifest.

One-time setup: in `index.html`, set `UPDATE_MANIFEST_URL` to
`https://api.github.com/repos/<you>/<your-repo>/releases/latest` — GitHub
itself always keeps that URL pointed at whichever release is newest, so it
never needs editing again after this.

Android does not allow a normal app to silently install a package — the
final "Install" tap the person makes is Android's own confirmation dialog,
not something this plugin can skip. Everything up to that point (check,
download, progress) is native and in-app.

