package com.francistech.vinylist;

import android.Manifest;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

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
 */
@CapacitorPlugin(
    name = "MediaStoreAudio",
    permissions = {
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "audioLegacy", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MediaStorePlugin extends Plugin {

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
}
