package com.fujiapp.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.edit
import com.fujiapp.remote.ble.Bonding
import com.fujiapp.remote.ble.CameraBusyException
import com.fujiapp.remote.ble.CameraScanner
import com.fujiapp.remote.ble.Capabilities
import com.fujiapp.remote.ble.FileTransferException
import com.fujiapp.remote.ble.FoundCamera
import com.fujiapp.remote.ble.FujiCamera
import com.fujiapp.remote.ble.FujiGatt
import com.fujiapp.remote.ble.GattClient
import com.fujiapp.remote.ble.GattException
import com.fujiapp.remote.ble.NoAnswerException
import com.fujiapp.remote.ble.UnsupportedCameraException
import com.fujiapp.remote.data.BackupStore
import com.fujiapp.remote.model.BackupMeta
import com.fujiapp.remote.model.CameraInfo
import com.fujiapp.remote.model.CameraVitals
import com.fujiapp.remote.util.ProtocolLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Connection state shown by the UI. */
sealed interface ConnState {
    data object Idle : ConnState
    /** Cameras seen so far; [active] while the scan is still running. */
    data class Scanning(val cameras: List<FoundCamera>, val active: Boolean) : ConnState
    data class Connecting(val message: String) : ConnState
    data class Connected(val info: CameraInfo, val capabilities: Capabilities) : ConnState
    data class Error(val message: String) : ConnState
}

/** Progress of a settings backup. */
sealed interface BackupState {
    data object Idle : BackupState
    data class Running(val bytes: Int) : BackupState
    data class Done(val meta: BackupMeta) : BackupState
    data class Failed(val message: String) : BackupState
}

/** Progress of a restore to the camera. */
sealed interface RestoreState {
    data object Idle : RestoreState
    data class Running(val name: String, val sent: Int, val total: Int) : RestoreState
    data class Done(val name: String) : RestoreState
    data class Failed(val message: String) : RestoreState
}

/** The camera's shutter count and usage data. */
sealed interface VitalsState {
    data object Idle : VitalsState
    data object Loading : VitalsState
    data class Loaded(val vitals: CameraVitals) : VitalsState
    data class Failed(val message: String) : VitalsState
}

/** Pairing didn't complete; retrying on our own won't help. */
private class PairingFailedException(message: String) : IOException(message)

/**
 * Owns the Bluetooth connection to the camera: scanning, pairing, registration, camera info,
 * shutter count, backups and restores. One instance per process, held by [App].
 *
 * The camera's Bluetooth link is flaky in practice (seen on an X100VI: refused writes right after
 * connecting, an empty service list after a quick reconnect, the camera dropping the link), and a
 * fresh connection reliably fixes it. So connecting retries, a dropped link reconnects on its own,
 * and backups / shutter-count reads retry once on a fresh connection.
 */
@SuppressLint("MissingPermission")
class CameraSession(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    val store = BackupStore(app)

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _backup = MutableStateFlow<BackupState>(BackupState.Idle)
    val backup: StateFlow<BackupState> = _backup.asStateFlow()

    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore.asStateFlow()

    private val _vitals = MutableStateFlow<VitalsState>(VitalsState.Idle)
    val vitals: StateFlow<VitalsState> = _vitals.asStateFlow()

    /**
     * One camera operation at a time (info read or file transfer): the camera runs a single
     * transfer on its file service, and interleaving reads with a transfer is asking for trouble.
     */
    private val opLock = Mutex()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var transferJob: Job? = null
    private var gatt: GattClient? = null
    private var camera: FujiCamera? = null
    private var userDisconnected = false

    val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    val isConnected: Boolean get() = _state.value is ConnState.Connected

    /** True while a camera operation (backup, restore, shutter count, info refresh) is running. */
    val isTransferring: Boolean get() = opLock.isLocked

    // ---- paired cameras ----

    /** Cameras this app registered with that are still bonded to the phone. */
    fun pairedCameras(): List<BluetoothDevice> {
        val addresses = prefs.getStringSet(KEY_ADDRESSES, emptySet()) ?: emptySet()
        val names = prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded.filter { d ->
            d.address in addresses || runCatching { d.name }.getOrNull()?.let { it in names } == true
        }
    }

    private fun remember(device: BluetoothDevice, model: String?) {
        val addresses = (prefs.getStringSet(KEY_ADDRESSES, emptySet()) ?: emptySet()) + device.address
        val names = (prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()) +
            listOfNotNull(model, runCatching { device.name }.getOrNull())
        prefs.edit {
            putStringSet(KEY_ADDRESSES, addresses)
            putStringSet(KEY_NAMES, names)
        }
    }

    // ---- scanning ----

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            _state.value = ConnState.Error("Turn Bluetooth on, then try again.")
            return
        }
        if (_state.value is ConnState.Connecting || _state.value is ConnState.Connected) return
        scanJob?.cancel()
        val found = LinkedHashMap<String, FoundCamera>()
        _state.value = ConnState.Scanning(emptyList(), active = true)
        scanJob = scope.launch {
            try {
                // Stop on its own: a camera advertises within seconds, longer only drains the battery.
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    CameraScanner(a).scan().collect { cam ->
                        found[cam.device.address] = cam
                        _state.value = ConnState.Scanning(found.values.toList(), active = true)
                    }
                }
                _state.value = ConnState.Scanning(found.values.toList(), active = false)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = ConnState.Error(t.message ?: "Bluetooth scan failed.")
            }
        }
    }

    /** Stop scanning; cameras already found stay listed so one can still be picked. */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        (_state.value as? ConnState.Scanning)?.let { _state.value = it.copy(active = false) }
    }

    // ---- connection ----

    /**
     * Connect to [device], pairing first if it isn't bonded yet. With [waitForCamera] (a paired
     * camera from [pairedCameras]) Android waits until the camera comes in range or is switched on.
     */
    fun connect(device: BluetoothDevice, waitForCamera: Boolean = false) {
        stopScan()
        connectJob?.cancel()
        cleanup()
        userDisconnected = false
        _vitals.value = VitalsState.Idle
        connectJob = scope.launch { connectLoop(device, waitForCamera) }
    }

    /**
     * Connect, retrying temporary failures, and stay connected: when the link drops, reconnect.
     * Returns when the user disconnects or retrying gives up.
     */
    private suspend fun connectLoop(device: BluetoothDevice, waitForCamera: Boolean) {
        var attempt = 0
        var reconnecting = false
        while (true) {
            attempt++
            val client = GattClient(app, device) { ProtocolLog.add(it) }
            gatt = client
            try {
                val wait = waitForCamera || reconnecting || attempt > 1
                _state.value = ConnState.Connecting(
                    when {
                        reconnecting -> "Connection lost. Reconnecting…"
                        attempt > 1 -> "Retrying ($attempt of $MAX_ATTEMPTS)…"
                        waitForCamera -> "Waiting for the camera. Switch it on (or off and on again)."
                        else -> "Connecting…"
                    }
                )
                setUp(client, device, autoConnect = wait, timeoutMs = if (waitForCamera && attempt == 1) 90_000 else 30_000)
                attempt = 0
                reconnecting = false

                client.disconnected.first { it }
                if (gatt !== client || userDisconnected) return
                ProtocolLog.add("connection lost, reconnecting")
                cleanup()
                reconnecting = true
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ProtocolLog.add("connect attempt $attempt failed: $t")
                if (gatt === client) cleanup()
                if (userDisconnected) return
                if (!isTemporary(t) || attempt >= MAX_ATTEMPTS) {
                    _state.value = ConnState.Error(if (reconnecting) "The camera disconnected." else friendly(t))
                    return
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /** Link, pair if needed, register and read the camera info. Ends in [ConnState.Connected]. */
    private suspend fun setUp(client: GattClient, device: BluetoothDevice, autoConnect: Boolean, timeoutMs: Long) {
        client.connect(autoConnect = autoConnect, timeoutMs = timeoutMs)

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _state.value = ConnState.Connecting(PAIRING_MESSAGE)
            if (!Bonding.bond(app, device)) {
                throw PairingFailedException(
                    "Pairing didn't complete. Start PAIRING REGISTRATION on the camera, then try again, " +
                        "and confirm the code on both the camera and the phone."
                )
            }
        }

        runCatching { client.requestMtu() }
        client.discoverServices(required = listOf(FujiGatt.SVC_CONNECTED_DEVICE, FujiGatt.SVC_LEGACY_PAIRING))

        val cam = FujiCamera(client) { ProtocolLog.add(it) }
        cam.register(onWaitingForPairing = { _state.value = ConnState.Connecting(PAIRING_MESSAGE) })
        val info = cam.readInfo()
        camera = cam
        remember(device, info.model)
        _state.value = ConnState.Connected(info, cam.capabilities)
    }

    fun disconnect() {
        userDisconnected = true
        connectJob?.cancel()
        transferJob?.cancel()
        cleanup()
        _vitals.value = VitalsState.Idle
        _state.value = ConnState.Idle
    }

    private fun cleanup() {
        gatt?.close()
        gatt = null
        camera = null
    }

    /** Errors worth another attempt on a fresh connection. */
    private fun isTemporary(t: Throwable): Boolean = when (t) {
        is PairingFailedException, is SecurityException -> false
        is UnsupportedCameraException -> t.message?.contains("older Bluetooth") != true
        else -> true
    }

    /** Drop the link on purpose; [connectLoop] then reconnects. */
    private fun forceReconnect() {
        ProtocolLog.add("reconnecting to recover")
        gatt?.close()
    }

    /** Wait until [connectLoop] has a new camera connection after [old], or null if it gave up. */
    private suspend fun awaitNewConnection(old: FujiCamera?, timeoutMs: Long = 60_000): FujiCamera? =
        withTimeoutOrNull(timeoutMs) {
            var fresh: FujiCamera? = null
            while (fresh == null) {
                val c = camera
                val s = _state.value
                if (c != null && c !== old && s is ConnState.Connected) {
                    fresh = c
                } else if (s is ConnState.Error || s is ConnState.Idle) {
                    break
                } else {
                    delay(200)
                }
            }
            fresh
        }

    // ---- camera operations ----

    /** Read the camera info again, then the shutter count. */
    fun refreshAll() {
        scope.launch {
            opLock.withLock {
                val cam = camera ?: return@withLock
                try {
                    val info = cam.readInfo()
                    _state.value = ConnState.Connected(info, cam.capabilities)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    ProtocolLog.add("refresh failed: $t")
                }
            }
            if ((_state.value as? ConnState.Connected)?.capabilities?.vitals == true) fetchVitals()
        }
    }

    /**
     * Run [op] as the only camera operation. With [retryOnFreshConnection], a failure reconnects
     * and runs it once more (only for operations that don't change the camera). Returns false if
     * another operation is running.
     */
    private fun <T> operation(
        retryOnFreshConnection: Boolean,
        op: suspend (FujiCamera) -> T,
        onResult: (Result<T>) -> Unit,
    ): Boolean {
        if (camera == null) return false
        if (!opLock.tryLock()) return false
        // invokeOnCompletion runs even if the job is cancelled before it starts.
        transferJob = scope.launch {
            val result = try {
                val cam = camera ?: throw GattException("Not connected.")
                try {
                    Result.success(op(cam))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (!retryOnFreshConnection || t is UnsupportedCameraException || userDisconnected) throw t
                    ProtocolLog.add("operation failed ($t), retrying on a fresh connection")
                    // If this connection is still up, drop it; if it already dropped, connectLoop is on it.
                    if (camera === cam) forceReconnect()
                    val fresh = awaitNewConnection(cam) ?: throw t
                    Result.success(op(fresh))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
            onResult(result)
        }.also { job -> job.invokeOnCompletion { opLock.unlock() } }
        return true
    }

    /** Read the shutter count and usage data. */
    fun fetchVitals() {
        val previous = _vitals.value
        if (previous is VitalsState.Loading) return
        // Set before starting: the operation may finish (fail) before operation() returns.
        _vitals.value = VitalsState.Loading
        val started = operation(retryOnFreshConnection = true, op = { cam -> cam.readVitals() }) { result ->
            result.onSuccess { _vitals.value = VitalsState.Loaded(it) }
            result.onFailure {
                ProtocolLog.add("vitals failed: $it")
                _vitals.value = VitalsState.Failed(friendly(it))
            }
        }
        if (!started) _vitals.value = previous
    }

    /** Start a backup; false if another operation is running. */
    fun startBackup(name: String): Boolean {
        val previous = _backup.value
        _backup.value = BackupState.Running(0)
        val started = operation(
            retryOnFreshConnection = true,
            op = { cam ->
                _backup.value = BackupState.Running(0)
                val file = cam.downloadBackup { bytes -> _backup.value = BackupState.Running(bytes) }
                val info = (state.value as? ConnState.Connected)?.info ?: CameraInfo()
                withContext(Dispatchers.IO) { store.save(name, info, file) }
            },
        ) { result ->
            result.onSuccess { _backup.value = BackupState.Done(it) }
            result.onFailure {
                ProtocolLog.add("backup failed: $it")
                _backup.value = BackupState.Failed(friendly(it))
            }
        }
        if (!started) _backup.value = previous
        return started
    }

    /**
     * Why [meta] can't be restored to the connected camera, or null if it can. Fujifilm only
     * restores a backup to the same camera model.
     */
    fun restoreBlocker(meta: BackupMeta): String? {
        val connected = state.value as? ConnState.Connected ?: return "Connect to the camera first."
        if (!connected.capabilities.restore) return "This camera doesn't accept a restore over Bluetooth."
        val model = connected.info.model ?: connected.info.modelNumber
        if (meta.cameraModel.isNotBlank() && model != null && !meta.cameraModel.equals(model, ignoreCase = true)) {
            return "This backup is from a ${meta.cameraModel}. It can only be restored to the same model."
        }
        return null
    }

    /**
     * Start a restore; false if it can't run now (another operation, or see [restoreBlocker]).
     * A restore changes the camera, so it never retries on its own.
     */
    fun startRestore(meta: BackupMeta): Boolean {
        restoreBlocker(meta)?.let {
            _restore.value = RestoreState.Failed(it)
            return false
        }
        val previous = _restore.value
        _restore.value = RestoreState.Running(meta.name, 0, meta.size)
        val started = operation(
            retryOnFreshConnection = false,
            op = { cam ->
                val data = withContext(Dispatchers.IO) { store.dataFile(meta).readBytes() }
                cam.restore(data) { sent, total -> _restore.value = RestoreState.Running(meta.name, sent, total) }
            },
        ) { result ->
            result.onSuccess { _restore.value = RestoreState.Done(meta.name) }
            result.onFailure {
                ProtocolLog.add("restore failed: $it")
                val message = if (it is GattException || it is NoAnswerException) {
                    "The connection dropped before the restore finished. Try again."
                } else {
                    friendly(it)
                }
                _restore.value = RestoreState.Failed(message)
            }
        }
        if (!started) _restore.value = previous
        return started
    }

    /** Forget finished backup/restore results (when the Backups screen opens). */
    fun clearTransferResults() {
        if (_backup.value !is BackupState.Running) _backup.value = BackupState.Idle
        if (_restore.value !is RestoreState.Running) _restore.value = RestoreState.Idle
    }

    private fun friendly(t: Throwable): String = when (t) {
        is UnsupportedCameraException, is CameraBusyException, is FileTransferException,
        is PairingFailedException -> t.message ?: "Failed."
        is NoAnswerException -> "The camera didn't respond. Make sure it's on and nearby, then try again."
        is GattException -> when (t.status) {
            19 -> "The camera closed the connection. If this keeps happening, delete this phone from the " +
                "camera's pairing list, remove the camera from the phone's Bluetooth settings, and pair again."
            else -> t.message ?: "Bluetooth error."
        }
        is SecurityException -> "Bluetooth permission is missing. Allow Nearby devices for this app in Settings."
        else -> t.message ?: t.javaClass.simpleName
    }

    private companion object {
        const val KEY_ADDRESSES = "addresses"
        const val KEY_NAMES = "names"
        const val SCAN_TIMEOUT_MS = 30_000L
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_500L
        const val PAIRING_MESSAGE = "Pairing: confirm the code on the camera (MENU/OK) and on this phone."
    }
}
