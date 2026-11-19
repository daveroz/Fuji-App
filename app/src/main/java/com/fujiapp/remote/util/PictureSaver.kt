package com.fujiapp.remote.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Saves pictures the app makes in Pictures/Fuji Remote. */
object PictureSaver {
    /** Save [jpeg] as [name]; returns the name it was saved under, or null. */
    fun saveJpeg(context: Context, name: String, jpeg: ByteArray): String? {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Fuji Remote")
            }
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            app.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: return null
        } else {
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Fuji Remote").apply { mkdirs() }
            File(dir, name).writeBytes(jpeg)
        }
        AppLog.add("saved picture $name (${jpeg.size} bytes)")
        return name
    }

    /** A file name made of [parts]: no spaces or characters a file system might refuse. */
    fun fileName(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString("-") { p -> p.trim().replace(Regex("[^A-Za-z0-9._+-]+"), "-").trim('-') } + ".jpg"
}
