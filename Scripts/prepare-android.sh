#!/usr/bin/env bash
# Runs on every Codemagic build. Regenerates android/ from scratch (so it
# never goes stale in git), then drops the custom native plugins, manifest
# permissions/components, and the one extra Gradle dependency they need
# into it.
set -euo pipefail

echo "== Installing JS deps =="
npm install

echo "== Generating Android project =="
rm -rf android
npx cap add android

echo "== Copying native plugin source =="
DEST="android/app/src/main/java/com/francistech/vinylist"
mkdir -p "$DEST"
cp native-src/com/francistech/vinylist/MediaStorePlugin.java "$DEST/"
cp native-src/com/francistech/vinylist/MediaPlaybackService.java "$DEST/"
cp native-src/com/francistech/vinylist/MediaPlaybackPlugin.java "$DEST/"
cp native-src/com/francistech/vinylist/MainActivity.java "$DEST/"

echo "== Merging manifest permissions + components =="
MANIFEST="android/app/src/main/AndroidManifest.xml"
python3 - "$MANIFEST" <<'PY'
import sys, re
path = sys.argv[1]
with open(path) as f:
    content = f.read()

if 'READ_MEDIA_AUDIO' in content:
    print("Manifest permissions already present, skipping.")
else:
    perms = """
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
"""
    content = re.sub(r'(<manifest[^>]*>)', r'\1' + perms, content, count=1)
    print("Manifest permissions inserted.")

if 'MediaPlaybackService' in content:
    print("Manifest service/receiver already present, skipping.")
else:
    components = """
        <service
            android:name="com.francistech.vinylist.MediaPlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_BUTTON" />
            </intent-filter>
        </service>

        <receiver
            android:name="androidx.media.session.MediaButtonReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_BUTTON" />
            </intent-filter>
        </receiver>
"""
    # Insert right before the closing </application> tag.
    content = content.replace('</application>', components + '    </application>')
    print("Manifest service/receiver inserted.")

with open(path, 'w') as f:
    f.write(content)
PY

echo "== Adding androidx.media Gradle dependency (needed for MediaSessionCompat) =="
GRADLE="android/app/build.gradle"
if grep -q 'androidx.media:media' "$GRADLE"; then
  echo "Gradle dependency already present, skipping."
else
  python3 - "$GRADLE" <<'PY'
import sys, re
path = sys.argv[1]
with open(path) as f:
    content = f.read()
dep = '    implementation "androidx.media:media:1.7.0"\n'
content = re.sub(r'(dependencies\s*\{)', r'\1\n' + dep, content, count=1)
with open(path, 'w') as f:
    f.write(content)
print("Gradle dependency inserted.")
PY
fi

echo "== Syncing Capacitor =="
npx cap sync android

echo "== prepare-android.sh done =="
