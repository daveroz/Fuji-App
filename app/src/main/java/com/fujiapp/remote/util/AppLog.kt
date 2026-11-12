package com.fujiapp.remote.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's recorder: every Bluetooth exchange, what the app did and every error, written to a
 * file in the app's storage so it survives restarts and crashes. The newest ~3 MB are kept (two
 * files of [MAX_FILE] bytes). [LogExporter] saves it as a .txt in Downloads.
 *
 * Holds protocol bytes, camera names and app events; never Wi-Fi passwords or locations.
 */
object AppLog {
    private const val MAX_FILE = 1_500_000L
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var current: File? = null
    private var previous: File? = null
    private var writer: Writer? = null

    /** Open the log file and note the app, Android version and phone. */
    @Synchronized
    fun init(context: Context) {
        if (current != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        current = File(dir, "fuji-remote.log")
        previous = File(dir, "fuji-remote.1.log")
        open()
        add("=== app started · ${header(context)}")
    }

    fun header(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName} ($code)"
        }.getOrDefault("?")
        return "Fuji Remote $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    @Synchronized
    fun add(message: String) {
        Log.d("FujiRemote", message)
        val w = writer ?: return
        runCatching {
            w.write(time.format(Date()))
            w.write(' '.code)
            w.write(message)
            w.write('\n'.code)
            w.flush()
            if ((current?.length() ?: 0) > MAX_FILE) rotate()
        }
    }

    /** Something the user did, or a screen change. */
    fun ui(event: String) = add("[ui] $event")

    /** An error with its stack trace. */
    fun error(where: String, t: Throwable) {
        val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
            .lineSequence().take(25).joinToString("\n    ")
        add("[error] $where: $t\n    $trace")
    }

    /** The whole recording, oldest first. */
    @Synchronized
    fun text(): String {
        runCatching { writer?.flush() }
        val old = previous?.takeIf { it.exists() }?.readText() ?: ""
        val now = current?.takeIf { it.exists() }?.readText() ?: ""
        return old + now
    }

    @Synchronized
    fun clear() {
        runCatching { writer?.close() }
        previous?.delete()
        current?.delete()
        open()
        add("=== log cleared")
    }

    private fun open() {
        writer = runCatching { FileWriter(current!!, true) }.getOrNull()
    }

    private fun rotate() {
        runCatching { writer?.close() }
        previous?.delete()
        current?.renameTo(previous!!)
        open()
    }
}
