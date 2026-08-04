#!/usr/bin/env bash
# Runs on every Codemagic build. Regenerates android/ from scratch (so it
# never goes stale in git), then drops the custom MediaStore plugin and
# manifest permissions into it.
set -euo pipefail

echo "== Installing JS deps =="
npm ci

echo "== Generating Android project =="
rm -rf android
npx cap add android

echo "== Copying native plugin source =="
DEST="android/app/src/main/java/com/francistech/vinylist"
mkdir -p "$DEST"
cp native-src/com/francistech/vinylist/MediaStorePlugin.java "$DEST/"
cp native-src/com/francistech/vinylist/MainActivity.java "$DEST/"

echo "== Merging manifest permissions =="
MANIFEST="android/app/src/main/AndroidManifest.xml"
PERMS=$(grep '<uses-permission' native-src/AndroidManifest-additions.xml)
# Insert right after the opening <manifest ...> tag, once.
python3 - "$MANIFEST" <<'PY'
import sys, re
path = sys.argv[1]
with open(path) as f:
    content = f.read()
if 'READ_MEDIA_AUDIO' in content:
    print("Permissions already present, skipping.")
else:
    perms = """
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
"""
    content = re.sub(r'(<manifest[^>]*>)', r'\1' + perms, content, count=1)
    with open(path, 'w') as f:
        f.write(content)
    print("Permissions inserted.")
PY

echo "== Syncing Capacitor =="
npx cap sync android

echo "== prepare-android.sh done =="
