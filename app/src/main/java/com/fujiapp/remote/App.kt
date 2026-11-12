package com.fujiapp.remote

import android.app.Application

/** Holds the process-wide [CameraSession]. */
class App : Application() {
    val session: CameraSession by lazy { CameraSession() }

    companion object {
        fun of(app: Application): CameraSession = (app as App).session
    }
}
