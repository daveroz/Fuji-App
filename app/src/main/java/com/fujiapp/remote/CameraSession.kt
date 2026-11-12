package com.fujiapp.remote

import android.content.Context
import android.util.Base64
import com.fujiapp.remote.model.Recipe
import com.fujiapp.remote.model.RecipeProperty
import com.fujiapp.remote.net.CameraNetwork
import com.fujiapp.remote.net.FujiClient
import com.fujiapp.remote.net.LiveViewStream
import com.fujiapp.remote.net.PropDesc
import com.fujiapp.remote.net.Ptp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-wide connection state. */
sealed interface ConnState {
    data object Disconnected : ConnState
    data class Connecting(val message: String) : ConnState
    data class Connected(val cameraName: String) : ConnState
    data class Error(val message: String) : ConnState
}

/**
 * Owns the camera connection, the live-view loop and recipe operations. One instance per process
 * (held by [App]). The command channel is serialised with [cmdLock]; live view runs on its own
 * socket so it can stream while commands are issued.
 */
class CameraSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cmdLock = Mutex()

    private var network: CameraNetwork? = null
    private var client: FujiClient? = null
    private var liveStream: LiveViewStream? = null
    private var liveJob: Job? = null

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    /** Latest live-view JPEG frame, or null when not streaming. */
    private val _frame = MutableStateFlow<ByteArray?>(null)
    val frame: StateFlow<ByteArray?> = _frame.asStateFlow()

    /** Properties enumerated from the camera (cached after connect). */
    @Volatile var properties: List<PropDesc> = emptyList()
        private set

    val isConnected: Boolean get() = state.value is ConnState.Connected

    // ---- connection ----

    fun connect(context: Context) {
        if (_state.value is ConnState.Connecting) return
        scope.launch {
            try {
                _state.value = ConnState.Connecting("Looking for the camera's WiFi…")
                val net = CameraNetwork(context).also { network = it }
                val factory = net.bind()
                    ?: throw IllegalStateException(
                        "Couldn't reach the camera's WiFi. Join the camera's network in Settings, " +
                            "then try again."
                    )
                val guid = loadOrCreateGuid(context)
                val c = FujiClient(socketFactory = factory, clientGuid = guid, deviceName = "Fuji Remote")
                client = c
                cmdLock.withLock {
                    c.connect(progress = { msg -> _state.value = ConnState.Connecting(msg) })
                    properties = c.enumerateProperties()
                }
                _state.value = ConnState.Connected(c.cameraName.ifEmpty { "Fujifilm camera" })
            } catch (t: Throwable) {
                cleanup()
                _state.value = ConnState.Error(t.message ?: "Connection failed")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            stopLiveViewInternal()
            cmdLock.withLock { runCatching { client?.disconnect() } }
            cleanup()
            _state.value = ConnState.Disconnected
        }
    }

    private fun cleanup() {
        runCatching { liveStream?.close() }
        liveStream = null
        client = null
        network?.release()
        network = null
        properties = emptyList()
        _frame.value = null
    }

    // ---- live view ----

    fun startLiveView(context: Context) {
        val c = client ?: return
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            try {
                cmdLock.withLock { c.startLiveView() }
                val factory = network?.network?.socketFactory
                val stream = LiveViewStream(
                    socketFactory = factory ?: javax.net.SocketFactory.getDefault()
                ).also { liveStream = it }
                stream.open()
                while (isActive) {
                    val jpeg = runCatching { stream.readFrame() }.getOrNull() ?: continue
                    _frame.value = jpeg
                }
            } catch (_: Throwable) {
                // Stream ended or errored; leave the last frame up.
            }
        }
    }

    fun stopLiveView() {
        scope.launch { stopLiveViewInternal() }
    }

    private suspend fun stopLiveViewInternal() {
        liveJob?.cancel()
        liveJob = null
        runCatching { liveStream?.close() }
        liveStream = null
        _frame.value = null
        cmdLock.withLock { runCatching { client?.stopLiveView() } }
    }

    // ---- remote control ----

    suspend fun capture(): Boolean = onCmd { it.capture() }
    suspend fun stepShutter(up: Boolean): Boolean = onCmd { it.stepShutter(up) }
    suspend fun stepAperture(up: Boolean): Boolean = onCmd { it.stepAperture(up) }
    suspend fun stepExposureComp(up: Boolean): Boolean = onCmd { it.stepExposureComp(up) }

    /** Set a single property (used by live controls and recipe restore). */
    suspend fun setProperty(code: Int, value: Long, sizeBytes: Int): Boolean =
        onCmd { it.setDeviceProp(code, value, sizeBytes) }

    /** Re-read all property descriptors (refreshes current values). */
    suspend fun refreshProperties(): List<PropDesc> = onCmd {
        it.enumerateProperties().also { list -> properties = list }
    } ?: properties

    private suspend fun <T> onCmd(block: (FujiClient) -> T): T? {
        val c = client ?: return null
        return withContext(Dispatchers.IO) { cmdLock.withLock { runCatching { block(c) }.getOrNull() } }
    }

    // ---- recipes ----

    /** Build a recipe from the camera's current recipe-relevant, writable properties. */
    suspend fun buildRecipe(name: String): Recipe? {
        val descs = refreshProperties()
        if (descs.isEmpty()) return null
        val cameraName = (state.value as? ConnState.Connected)?.cameraName ?: ""
        val props = descs
            .filter { it.code !in Ptp.KnownProps.volatile }
            .filter { Ptp.KnownProps.isRecipeProperty(it.code) || it.writable }
            .map { RecipeProperty.fromDesc(it) }
        return Recipe(name = name, camera = cameraName, savedAt = System.currentTimeMillis(), properties = props)
    }

    /** Write a recipe's properties back to the camera. Returns #applied / #total. */
    suspend fun applyRecipe(recipe: Recipe): Pair<Int, Int> {
        var applied = 0
        for (p in recipe.properties) {
            val size = Ptp.Type.sizeOf(p.dataType) ?: 4
            if (setProperty(p.code, p.value, size)) applied++
        }
        refreshProperties()
        return applied to recipe.properties.size
    }

    // ---- guid persistence ----

    private fun loadOrCreateGuid(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("fuji", Context.MODE_PRIVATE)
        prefs.getString("guid", null)?.let { return Base64.decode(it, Base64.DEFAULT) }
        val guid = FujiClient.randomGuid()
        prefs.edit().putString("guid", Base64.encodeToString(guid, Base64.DEFAULT)).apply()
        return guid
    }
}
