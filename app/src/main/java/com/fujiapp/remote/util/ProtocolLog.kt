package com.fujiapp.remote.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last Bluetooth exchanges, kept in memory so they can be shared from the app when something
 * doesn't work on a camera. Holds protocol bytes and camera names only, nothing else.
 */
object ProtocolLog {
    private const val MAX_LINES = 2000
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(message: String) {
        Log.d("FujiBLE", message)
        lines.addLast("${time.format(Date())} $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
