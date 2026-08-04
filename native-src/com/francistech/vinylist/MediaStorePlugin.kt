package com.francistech.vinylist

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Reads the device's audio index via MediaStore and hands back content:// URIs.
 * Nothing is ever copied — Vinylist keeps only the URI + metadata in its own
 * store, and streams playback straight from the URI (see convertFileSrc on
 * the JS side, which the Capacitor bridge proxies through ContentResolver).
 */
@CapacitorPlugin(
    name = "MediaStoreAudio",
    permissions = [
        Permission(
            alias = "audio",
            strings = [Manifest.permission.READ_MEDIA_AUDIO]
        ),
        Permission(
            alias = "audioLegacy",
            strings = [Manifest.permission.READ_EXTERNAL_STORAGE]
        )
    ]
)
class MediaStorePlugin : Plugin() {

    private fun permissionAlias(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "audio" else "audioLegacy"

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        val state = getPermissionState(permissionAlias())
        val ret = JSObject()
        ret.put("granted", state.toString() == "GRANTED")
        call.resolve(ret)
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        requestPermissionForAlias(permissionAlias(), call, "permissionCallback")
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        val state = getPermissionState(permissionAlias())
        val ret = JSObject()
        ret.put("granted", state.toString() == "GRANTED")
        call.resolve(ret)
    }

    /** Full library scan — id/title/artist/album/duration/uri for every audio row MediaStore knows about. */
    @PluginMethod
    fun scanAudio(call: PluginCall) {
        if (getPermissionState(permissionAlias()).toString() != "GRANTED") {
            call.reject("Permission not granted. Call requestPermission first.")
            return
        }

        val results = JSArray()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        try {
            context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = Uri.withAppendedPath(collection, id.toString())
                    val row = JSObject()
                    row.put("id", id.toString())
                    row.put("uri", contentUri.toString())
                    row.put("title", cursor.getString(titleCol) ?: "")
                    row.put("artist", cursor.getString(artistCol) ?: "")
                    row.put("album", cursor.getString(albumCol) ?: "")
                    row.put("durationMs", cursor.getLong(durationCol))
                    row.put("sizeBytes", cursor.getLong(sizeCol))
                    row.put("dateAddedSec", cursor.getLong(dateCol))
                    results.put(row)
                }
            }
            val ret = JSObject()
            ret.put("tracks", results)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("MediaStore query failed: ${e.message}")
        }
    }
}
