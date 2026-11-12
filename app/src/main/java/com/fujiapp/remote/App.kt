package com.fujiapp.remote

import android.app.Application
import com.fujiapp.remote.util.AppLog

/** Holds the process-wide [CameraSession] and starts the log recorder. */
class App : Application() {
    val session: CameraSession by lazy { CameraSession(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // Record a crash before Android ends the app, so it's in the log the user sends.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { AppLog.error("crash on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun of(app: Application): CameraSession = (app as App).session
    }
}
