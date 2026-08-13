package com.francistech.vinylist;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;

/**
 * Reads the device's audio index via MediaStore and hands back content:// URIs.
 * Nothing is ever copied -- Vinylist keeps only the URI + metadata in its own
 * store, and streams playback straight from the URI (see convertFileSrc on
 * the JS side, which the Capacitor bridge proxies through ContentResolver).
 *
 * Also handles the "save a lyrics copy to my phone" feature (see
 * saveLyricsFile/readLyricsFile/listLyricsFiles below). That used to go
 * through @capacitor/filesystem writing straight to the public Documents
 * folder, which silently fails on Android 11+ (scoped storage blocks raw
 * file-path writes outside app-private storage without the intrusive "All
 * files access" permission, which this app doesn't request and Play Store
 * wouldn't approve for this use case anyway). The "Files and media" toggle
 * users see in Settings only maps to the "audio" permission above, used
 * for scanAudio() -- it never covered writing files out, so granting it
 * could never have fixed the old Documents-folder save path.
 *
 * The fix: use MediaStore.Downloads, the officially-supported, scoped-storage
 * -safe way to place a file in a public folder on Android 10+. It needs no
 * runtime permission at all on API 29+. Below that (pre scoped-storage), it
 * falls back to a direct file write into the public Downloads dir, guarded
 * by the legacy WRITE_EXTERNAL_STORAGE permission.
 */
@CapacitorPlugin(
    name = "MediaStoreAudio",
    permissions = {
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "audioLegacy", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = "storageLegacy", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE })
    }
)
public class MediaStorePlugin extends Plugin {

    /** Subfolder under the public Downloads directory that lyrics copies are saved to. */
    private static final String LYRICS_SUBDIR = "Music Lyrics";

    private String permissionAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "audioLegacy";
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        PermissionState state = getPermissionState(permissionAlias());
        JSObject ret = new JSObject();
        ret.put("granted", state == PermissionState.GRANTED);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        requestPermissionForAlias(permissionAlias(), call, "permissionCallback");
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        PermissionState state = getPermissionState(permissionAlias());
        JSObject ret = new JSObject();
        ret.put("granted", state == PermissionState.GRANTED);
        call.resolve(ret);
    }

    /** Full library scan -- id/title/artist/album/duration/uri for every audio row MediaStore knows about. */
    @PluginMethod
    public void scanAudio(PluginCall call) {
        if (getPermissionState(permissionAlias()) != PermissionState.GRANTED) {
            call.reject("Permission not granted. Call requestPermission first.");
            return;
        }

        JSArray results = new JSArray();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[] {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

        try (Cursor cursor = getContext().getContentResolver().query(collection, projection, selection, null, null)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    Uri contentUri = Uri.withAppendedPath(collection, String.valueOf(id));
                    JSObject row = new JSObject();
                    // "id" is still sent (kept only as a one-release migration
                    // fallback on the JS side, and it's what the playable
                    // content:// URI is built from) but it is NOT what
                    // lyrics/playlists/favorites get keyed by anymore --
                    // MediaStore._ID is a database row number, not a stable
                    // identity for the file. It gets reassigned by the OS
                    // whenever the media scanner re-indexes the library
                    // (happens on reboot, after a firmware update, after some
                    // phones' storage-cleanup tools run, etc.), which was
                    // silently orphaning every saved lyric/playlist/favorite
                    // tied to the old id. displayName+size mirrors the scheme
                    // already used for picker-imported files, which never had
                    // this problem, because it's derived from the file itself
                    // instead of a database surrogate key.
                    row.put("id", String.valueOf(id));
                    row.put("displayName", cursor.getString(nameCol) != null ? cursor.getString(nameCol) : "");
                    row.put("uri", contentUri.toString());
                    row.put("title", cursor.getString(titleCol) != null ? cursor.getString(titleCol) : "");
                    row.put("artist", cursor.getString(artistCol) != null ? cursor.getString(artistCol) : "");
                    row.put("album", cursor.getString(albumCol) != null ? cursor.getString(albumCol) : "");
                    row.put("durationMs", cursor.getLong(durationCol));
                    row.put("sizeBytes", cursor.getLong(sizeCol));
                    row.put("dateAddedSec", cursor.getLong(dateCol));
                    results.put(row);
                }
            }
            JSObject ret = new JSObject();
            ret.put("tracks", results);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("MediaStore query failed: " + e.getMessage());
        }
    }

    /**
     * Writes a plain-text file into the public Downloads/Music Lyrics folder.
     * Expects { fileName, content } on the call. Resolves { ok, reason }
     * so the JS side can show a specific toast instead of a generic failure.
     */
    @PluginMethod
    public void saveLyricsFile(PluginCall call) {
        String fileName = call.getString("fileName");
        String content = call.getString("content");
        if (fileName == null || content == null) {
            call.reject("fileName and content are required");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Scoped-storage-safe path: MediaStore.Downloads needs no
                // runtime permission for an app to insert/update its own rows.
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + LYRICS_SUBDIR + "/";

                Uri existing = findExistingDownload(relativePath, fileName);
                Uri target;
                if (existing != null) {
                    target = existing;
                } else {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                    target = getContext().getContentResolver().insert(collection, values);
                }
                if (target == null) {
                    resolveSaveResult(call, false, "write-failed");
                    return;
                }
                try (OutputStream out = getContext().getContentResolver().openOutputStream(target, "wt")) {
                    if (out == null) { resolveSaveResult(call, false, "write-failed"); return; }
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                resolveSaveResult(call, true, null);
            } else {
                // Pre-scoped-storage fallback (Android 9 and below).
                if (getPermissionState("storageLegacy") != PermissionState.GRANTED) {
                    resolveSaveResult(call, false, "permission");
                    return;
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LYRICS_SUBDIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    resolveSaveResult(call, false, "write-failed");
                    return;
                }
                try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                resolveSaveResult(call, true, null);
            }
        } catch (SecurityException e) {
            resolveSaveResult(call, false, "permission");
        } catch (Exception e) {
            resolveSaveResult(call, false, e.getMessage() != null ? e.getMessage() : "write-failed");
        }
    }

    /** Requests the legacy write-storage permission, only ever needed on Android 9 and below. */
    @PluginMethod
    public void requestLegacyStoragePermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 29) {
            JSObject ret = new JSObject();
            ret.put("granted", true); // not needed on this OS version
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias("storageLegacy", call, "legacyStorageCallback");
    }

    @PermissionCallback
    private void legacyStorageCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", getPermissionState("storageLegacy") == PermissionState.GRANTED);
        call.resolve(ret);
    }

    /** Reads back a lyrics file previously saved by saveLyricsFile, if it exists. */
    @PluginMethod
    public void readLyricsFile(PluginCall call) {
        String fileName = call.getString("fileName");
        if (fileName == null) { call.reject("fileName is required"); return; }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + LYRICS_SUBDIR + "/";
                Uri existing = findExistingDownload(relativePath, fileName);
                if (existing == null) { call.resolve(emptyRead()); return; }
                try (InputStream in = getContext().getContentResolver().openInputStream(existing)) {
                    if (in == null) { call.resolve(emptyRead()); return; }
                    String text = new Scanner(in, "UTF-8").useDelimiter("\\A").next();
                    JSObject ret = new JSObject();
                    ret.put("content", text);
                    call.resolve(ret);
                } catch (java.util.NoSuchElementException emptyFile) {
                    JSObject ret = new JSObject();
                    ret.put("content", "");
                    call.resolve(ret);
                }
            } else {
                File f = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LYRICS_SUBDIR), fileName);
                if (!f.exists()) { call.resolve(emptyRead()); return; }
                StringBuilder sb = new StringBuilder();
                try (Scanner sc = new Scanner(f, "UTF-8")) {
                    while (sc.hasNextLine()) { sb.append(sc.nextLine()).append('\n'); }
                }
                JSObject ret = new JSObject();
                ret.put("content", sb.toString());
                call.resolve(ret);
            }
        } catch (Exception e) {
            call.resolve(emptyRead());
        }
    }

    /** Lists every file name currently saved in the Downloads/Music Lyrics folder. */
    @PluginMethod
    public void listLyricsFiles(PluginCall call) {
        JSArray names = new JSArray();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + LYRICS_SUBDIR + "/";
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] projection = new String[] { MediaStore.Downloads.DISPLAY_NAME };
                String selection = MediaStore.Downloads.RELATIVE_PATH + "=?";
                String[] args = new String[] { relativePath };
                try (Cursor cursor = getContext().getContentResolver().query(collection, projection, selection, args, null)) {
                    if (cursor != null) {
                        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                        while (cursor.moveToNext()) { names.put(cursor.getString(nameCol)); }
                    }
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LYRICS_SUBDIR);
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) { names.put(f.getName()); }
                }
            }
        } catch (Exception e) {
            // fall through and resolve whatever we managed to collect
        }
        JSObject ret = new JSObject();
        ret.put("files", names);
        call.resolve(ret);
    }

    private Uri findExistingDownload(String relativePath, String fileName) throws Exception {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = new String[] { MediaStore.Downloads._ID };
        String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + "=?";
        String[] args = new String[] { relativePath, fileName };
        try (Cursor cursor = getContext().getContentResolver().query(collection, projection, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                return ContentUris.withAppendedId(collection, id);
            }
        }
        return null;
    }

    private JSObject emptyRead() {
        JSObject ret = new JSObject();
        ret.put("content", (String) null);
        return ret;
    }

    private void resolveSaveResult(PluginCall call, boolean ok, String reason) {
        JSObject ret = new JSObject();
        ret.put("ok", ok);
        if (reason != null) ret.put("reason", reason);
        call.resolve(ret);
    }
}
