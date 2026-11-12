package com.fujiapp.remote.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Joins the camera's own Wi-Fi for this app only: Android 10 and later ask the user once to
 * connect to the named network, and the app's sockets then go through it while the phone keeps
 * its normal connection for everything else. On older Android the user joins it in Settings.
 */
class WifiJoin(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    var network: Network? = null
        private set

    suspend fun join(ssid: String, password: String, timeoutMs: Long = 90_000): Network {
        release()
        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setIsHiddenSsid(true)
            if (password.isNotEmpty()) spec.setWpa2Passphrase(password)
            builder.setNetworkSpecifier(spec.build())
        }
        val request = builder.build()
        val net = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Network> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) {
                        if (cont.isActive) cont.resume(n)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resumeWithException(IOException("Android didn't join $ssid (cancelled, or the camera's Wi-Fi isn't on)."))
                    }
                }
                callback = cb
                cm.requestNetwork(request, cb)
                cont.invokeOnCancellation { release() }
            }
        } ?: run {
            release()
            throw IOException("Joining $ssid took too long.")
        }
        network = net
        return net
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }
}
