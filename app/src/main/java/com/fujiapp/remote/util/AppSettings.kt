package com.fujiapp.remote.util

import android.content.Context
import androidx.core.content.edit

/** The app's own settings (camera settings live on the camera). */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Answer the camera's time request with the phone's clock, as XApp does. */
    var syncTime: Boolean
        get() = prefs.getBoolean(KEY_SYNC_TIME, true)
        set(value) = prefs.edit { putBoolean(KEY_SYNC_TIME, value) }

    /**
     * Answer the camera's location requests with the phone's location (geotagging). Off by
     * default: when it's off the app doesn't listen to the requests at all, because unanswered
     * requests during a transfer made the camera drop the link.
     */
    var locationSync: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_SYNC, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCATION_SYNC, value) }

    private companion object {
        const val KEY_SYNC_TIME = "sync_time"
        const val KEY_LOCATION_SYNC = "location_sync"
    }
}
