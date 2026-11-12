package com.fujiapp.remote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.net.SocketFactory
import kotlin.coroutines.resume

/**
 * Binds the app's sockets to the camera's WiFi network.
 *
 * The camera's access point has no internet, so by default Android keeps the system's "active"
 * network on cellular and our connections to 192.168.0.1 would go nowhere. We explicitly request
 * the WiFi transport (without requiring the INTERNET capability) and bind the whole process to it,
 * so every socket — command channel and live-view stream alike — routes to the camera.
 *
 * The user must already have joined the camera's WiFi (Settings ▸ WiFi, or the camera-connect
 * flow). Programmatic SSID join can be layered on top later via WifiNetworkSpecifier.
 */
class CameraNetwork(context: Context) {

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile var network: Network? = null
        private set

    /**
     * Request and bind to the camera's WiFi network. Returns a [SocketFactory] that creates
     * sockets on that network, or null if no suitable WiFi network appeared within [timeoutMs].
     */
    suspend fun bind(timeoutMs: Long = 10_000): SocketFactory? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val net = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Network> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) {
                        if (cont.isActive) cont.resume(n)
                    }
                }
                callback = cb
                cm.requestNetwork(request, cb)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        } ?: return null

        network = net
        // Route the whole process through the camera's WiFi.
        runCatching { cm.bindProcessToNetwork(net) }
        return net.socketFactory
    }

    /** Release the WiFi binding and restore normal routing. */
    fun release() {
        runCatching { cm.bindProcessToNetwork(null) }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }
}
