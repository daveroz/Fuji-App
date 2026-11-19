package com.fujiapp.remote

import android.app.Application
import com.fujiapp.remote.preview.FilmLuts
import com.fujiapp.remote.preview.Lut3D
import com.fujiapp.remote.recipe.MyRecipes
import com.fujiapp.remote.usb.RecipeTransfer
import com.fujiapp.remote.util.AppLog

/** Holds the process-wide [CameraSession], the USB recipe transfers and saved recipes, and starts the log recorder. */
class App : Application() {
    val session: CameraSession by lazy { CameraSession(this) }
    val transfer: RecipeTransfer by lazy { RecipeTransfer(this, session.store) }
    val myRecipes: MyRecipes by lazy { MyRecipes(this) }
    val wifi: com.fujiapp.remote.wifi.WifiSession by lazy { com.fujiapp.remote.wifi.WifiSession(this, session) }
    val developer: com.fujiapp.remote.usb.RawDeveloper by lazy { com.fujiapp.remote.usb.RawDeveloper(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // Record a crash before Android ends the app, so it's in the log the user sends.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { AppLog.error("crash on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
        loadFilmLuts()
    }

    /** Measured film simulation LUTs shipped as assets/luts/<FILM_SIM>.cube, if any (the Lab uses them when present). */
    private fun loadFilmLuts() = kotlin.concurrent.thread(name = "film-luts", isDaemon = true) {
        val files = runCatching { assets.list("luts") }.getOrNull().orEmpty()
        for (file in files) {
            val sim = FilmLuts.simFor(file) ?: continue
            runCatching { assets.open("luts/$file").bufferedReader().use { Lut3D.parseCube(it.readText()) } }
                .onSuccess { FilmLuts.put(sim, it); AppLog.add("film LUT: $file (${it.size}³)") }
                .onFailure { AppLog.error("film LUT $file", it) }
        }
    }

    companion object {
        fun of(app: Application): CameraSession = (app as App).session
    }
}
