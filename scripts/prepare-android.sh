#!/usr/bin/env bash
# Runs on every Codemagic build. Regenerates android/ from scratch (so it
# never goes stale in git), then drops the custom native plugins, the
# playback-notification service, its icons, and manifest/gradle wiring
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
cp native-src/com/francistech/vinylist/MediaControlPlugin.java "$DEST/"
cp native-src/com/francistech/vinylist/MediaPlaybackNotificationService.java "$DEST/"
cp native-src/com/francistech/vinylist/AppUpdatePlugin.java "$DEST/"
cp native-src/com/francistech/vinylist/MainActivity.java "$DEST/"

echo "== Copying notification icons =="
RES_DEST="android/app/src/main/res/drawable"
mkdir -p "$RES_DEST"
cp native-src/res/drawable/ic_stat_vinylist.xml "$RES_DEST/"
cp native-src/res/drawable/ic_media_previous.xml "$RES_DEST/"
cp native-src/res/drawable/ic_media_play.xml "$RES_DEST/"
cp native-src/res/drawable/ic_media_pause.xml "$RES_DEST/"
cp native-src/res/drawable/ic_media_next.xml "$RES_DEST/"

echo "== Copying FileProvider path spec (in-app update installer) =="
XML_DEST="android/app/src/main/res/xml"
mkdir -p "$XML_DEST"
cp native-src/res/xml/file_paths.xml "$XML_DEST/"

echo "== Merging manifest permissions =="
MANIFEST="android/app/src/main/AndroidManifest.xml"
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
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
"""
    content = re.sub(r'(<manifest[^>]*>)', r'\1' + perms, content, count=1)
    with open(path, 'w') as f:
        f.write(content)
    print("Permissions inserted.")
PY

echo "== Registering the notification service in the manifest =="
python3 - "$MANIFEST" <<'PY'
import sys, re
path = sys.argv[1]
with open(path) as f:
    content = f.read()

if 'MediaPlaybackNotificationService' in content:
    print("Service already present, skipping.")
else:
    service = """
        <service
            android:name="com.francistech.vinylist.MediaPlaybackNotificationService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
"""
    # Insert right after the opening <application ...> tag.
    content = re.sub(r'(<application[^>]*>)', r'\1' + service, content, count=1)
    with open(path, 'w') as f:
        f.write(content)
    print("Service inserted.")
PY

echo "== Registering the FileProvider for in-app update installs =="
python3 - "$MANIFEST" <<'PY'
import sys, re
path = sys.argv[1]
with open(path) as f:
    content = f.read()

if 'androidx.core.content.FileProvider' in content:
    print("FileProvider already present, skipping.")
else:
    provider = """
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
"""
    # Insert right after the opening <application ...> tag, same as the service above.
    content = re.sub(r'(<application[^>]*>)', r'\1' + provider, content, count=1)
    with open(path, 'w') as f:
        f.write(content)
    print("FileProvider inserted.")
PY

echo "== Adding androidx.media dependency =="
GRADLE="android/app/build.gradle"
if ! grep -q "androidx.media:media" "$GRADLE"; then
    python3 - "$GRADLE" <<'PY'
import sys, re
path = sys.argv[1]
with open(path) as f:
    content = f.read()
content = re.sub(
    r'(dependencies\s*\{)',
    r'\1\n    implementation "androidx.media:media:1.7.0"',
    content, count=1
)
with open(path, 'w') as f:
    f.write(content)
print("androidx.media dependency added.")
PY
else
    echo "androidx.media dependency already present, skipping."
fi

echo "== Setting versionCode/versionName from version.json =="
python3 - "$GRADLE" <<'PY'
import sys, re, json
gradle_path = sys.argv[1]
with open('version.json') as f:
    v = json.load(f)
version_name = str(v['versionName'])

# Same formula AppUpdatePlugin.versionCodeFromName() uses at runtime to turn
# a GitHub release tag into a comparable number -- keeping both in sync is
# what lets "tag a release as v1.2.0" be the entire update workflow, with no
# separate manifest file to hand-maintain anywhere.
def parse_int_safe(s):
    m = re.match(r'\d+', s)
    return int(m.group(0)) if m else 0

parts = version_name.split('.')
major = parse_int_safe(parts[0]) if len(parts) > 0 else 0
minor = parse_int_safe(parts[1]) if len(parts) > 1 else 0
patch = parse_int_safe(parts[2]) if len(parts) > 2 else 0
version_code = major * 1_000_000 + minor * 1_000 + patch

with open(gradle_path) as f:
    content = f.read()
content = re.sub(r'versionCode\s+\d+', f'versionCode {version_code}', content, count=1)
content = re.sub(r'versionName\s+"[^"]*"', f'versionName "{version_name}"', content, count=1)
with open(gradle_path, 'w') as f:
    f.write(content)
print(f"Set versionCode={version_code}, versionName={version_name} (from version.json).")
PY

echo "== Syncing Capacitor =="
npx cap sync android

echo "== prepare-android.sh done =="
