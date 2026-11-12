package com.fujiapp.remote

import android.app.Application
import com.fujiapp.remote.recipe.MyRecipes
import com.fujiapp.remote.usb.RecipeTransfer
import com.fujiapp.remote.util.AppLog

/** Holds the process-wide [CameraSession], the USB recipe transfers and saved recipes, and starts the log recorder. */
class App : Application() {
    val session: CameraSession by lazy { CameraSession(this) }
    val transfer: RecipeTransfer by lazy { RecipeTransfer(this, session.store) }
    val myRecipes: MyRecipes by lazy { MyRecipes(this) }
    val wifi: com.fujiapp.remote.wifi.WifiSession by lazy { com.fujiapp.remote.wifi.WifiSession(this, session) }

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
