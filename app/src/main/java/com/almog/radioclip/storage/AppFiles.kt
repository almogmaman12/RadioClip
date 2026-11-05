package com.almog.radioclip.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Where RadioClip keeps files, in one place. */
object AppFiles {
    /** Files received from other devices (app-specific external storage, so no storage permission is needed). */
    fun receivedDir(context: Context): File =
        (context.getExternalFilesDir("received") ?: File(context.filesDir, "received")).apply { mkdirs() }

    /** Temporary copies of files shared into RadioClip, so a transfer doesn't depend on the sharing app's access. */
    fun outgoingDir(context: Context): File = File(context.cacheDir, "outgoing").apply { mkdirs() }

    /** Display name and size (0 if unknown) of a content Uri. */
    fun queryMeta(context: Context, uri: Uri): Pair<String, Long> {
        var name = "shared_file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }
}
