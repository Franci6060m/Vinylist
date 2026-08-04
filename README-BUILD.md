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

## Notes on what changed in the web app
- `native-media.js` — bridges to the native plugin; every function safely
  no-ops when `window.Capacitor` isn't present, so the Netlify-hosted PWA is
  unaffected.
- `index.html` — added `importFromNativeLibrary()` (uses MediaStore content
  URIs directly, no byte copy) and a native backup mirror for playlists/
  lyrics via `@capacitor/filesystem`. The existing folder-picker/blob import
  path is untouched and still runs for web/PWA users.
