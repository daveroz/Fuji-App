package com.fujiapp.remote.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the [AppLog] recording as a .txt file the user can send. */
object LogExporter {

    fun fileName(): String = "fuji-remote-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

    /** Android 10 and later save straight into Downloads, no permission needed. */
    val canSaveToDownloads: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun content(context: Context): ByteArray =
        ("Fuji Remote log · saved ${Date()}\n${AppLog.header(context)}\n\n" + AppLog.text()).toByteArray(Charsets.UTF_8)

    /** Write the log into Downloads. Returns the file's uri and name. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context): Pair<Uri, String> {
        val name = fileName()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create the file in Downloads.")
        resolver.openOutputStream(uri)?.use { it.write(content(context)) } ?: throw IOException("Couldn't write the file.")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        AppLog.add("log saved to Downloads/$name")
        return uri to name
    }

    /** Write the log to a file the user picked (Android 9 and lower). */
    fun writeTo(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(content(context)) }
            ?: throw IOException("Couldn't write the file.")
        AppLog.add("log saved to $uri")
    }
}
