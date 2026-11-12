package com.fujiapp.remote.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * The phone's location for the camera's geotag requests, from Android's own providers (no Google
 * Play services needed). Listens only while location sync is on and a camera is connected.
 */
@SuppressLint("MissingPermission")
class PhoneLocation(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    @Volatile private var latest: Location? = null
    private var listening = false

    // All four methods spelled out: before Android 11 the interface has no default methods, and a
    // missing one crashes when a provider is switched on or off.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = offer(location)
        @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        if (listening || manager == null || !hasPermission()) return
        for (provider in PROVIDERS) {
            if (runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) {
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { offer(it) }
                runCatching { manager.requestLocationUpdates(provider, 10_000L, 0f, listener, Looper.getMainLooper()) }
            }
        }
        listening = true
        AppLog.add("location: listening")
    }

    @Synchronized
    fun stop() {
        if (!listening) return
        runCatching { manager?.removeUpdates(listener) }
        listening = false
        AppLog.add("location: stopped")
    }

    /** The freshest fix from the last three hours (the camera ignores older ones), or null. */
    fun current(): Location? {
        val now = System.currentTimeMillis()
        return latest?.takeIf { now - it.time < MAX_AGE_MS }
    }

    private fun offer(location: Location) {
        val old = latest
        if (old == null || location.time >= old.time) latest = location
    }

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
    }
}
