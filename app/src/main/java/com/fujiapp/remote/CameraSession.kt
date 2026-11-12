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
import com.fujiapp.remote.ble.GattClient
import com.fujiapp.remote.ble.GattException
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

/**
 * Owns the Bluetooth connection to the camera: scanning, pairing, registration, camera info,
 * shutter count, backups and restores. One instance per process, held by [App].
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

    /** One file transfer at a time: the camera runs a single transfer on its file service. */
    private val transferLock = Mutex()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var transferJob: Job? = null
    private var gatt: GattClient? = null
    private var camera: FujiCamera? = null

    val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    val isConnected: Boolean get() = _state.value is ConnState.Connected

    /** True while a backup, restore or shutter-count read is running. */
    val isTransferring: Boolean get() = transferLock.isLocked

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
        connectJob = scope.launch {
            val client = GattClient(app, device) { ProtocolLog.add(it) }
            gatt = client
            try {
                _state.value = ConnState.Connecting(
                    if (waitForCamera) "Waiting for the camera. Switch it on (or off and on again)." else "Connecting…"
                )
                client.connect(autoConnect = waitForCamera, timeoutMs = if (waitForCamera) 90_000 else 20_000)

                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    _state.value = ConnState.Connecting(PAIRING_MESSAGE)
                    if (!Bonding.bond(app, device)) {
                        throw GattException(
                            "Pairing didn't complete. Start PAIRING REGISTRATION on the camera, then try again, " +
                                "and confirm the code on both the camera and the phone."
                        )
                    }
                }

                _state.value = ConnState.Connecting("Reading the camera's services…")
                runCatching { client.requestMtu() }
                client.discoverServices()

                _state.value = ConnState.Connecting("Registering with the camera…")
                val cam = FujiCamera(client) { ProtocolLog.add(it) }
                cam.register(onWaitingForPairing = { _state.value = ConnState.Connecting(PAIRING_MESSAGE) })
                camera = cam

                _state.value = ConnState.Connecting("Reading camera info…")
                val info = cam.readInfo()
                remember(device, info.model)
                _vitals.value = VitalsState.Idle
                _state.value = ConnState.Connected(info, cam.capabilities)

                client.disconnected.first { it }
                if (gatt === client) {
                    cleanup()
                    _state.value = ConnState.Error("The camera disconnected.")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ProtocolLog.add("connect failed: $t")
                if (gatt === client) cleanup()
                _state.value = ConnState.Error(friendly(t))
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        transferJob?.cancel()
        cleanup()
        _state.value = ConnState.Idle
    }

    private fun cleanup() {
        gatt?.close()
        gatt = null
        camera = null
        _vitals.value = VitalsState.Idle
    }

    /** Read the camera info again (power state etc. may have changed). */
    fun refreshInfo() {
        val cam = camera ?: return
        scope.launch {
            try {
                val info = cam.readInfo()
                _state.value = ConnState.Connected(info, cam.capabilities)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ProtocolLog.add("refresh failed: $t")
            }
        }
    }

    // ---- file transfers ----

    /** Run [block] as the only file transfer, or return false if one is already running. */
    private fun transfer(block: suspend (FujiCamera) -> Unit): Boolean {
        val cam = camera ?: return false
        if (!transferLock.tryLock()) return false
        // invokeOnCompletion runs even if the job is cancelled before it starts.
        transferJob = scope.launch { block(cam) }.also { job -> job.invokeOnCompletion { transferLock.unlock() } }
        return true
    }

    /** Read the shutter count and usage data. */
    fun fetchVitals() {
        if (_vitals.value is VitalsState.Loading) return
        transfer { cam ->
            _vitals.value = VitalsState.Loading
            try {
                _vitals.value = VitalsState.Loaded(cam.readVitals())
            } catch (c: CancellationException) {
                _vitals.value = VitalsState.Idle
                throw c
            } catch (t: Throwable) {
                ProtocolLog.add("vitals failed: $t")
                _vitals.value = VitalsState.Failed(friendly(t))
            }
        }
    }

    /** Start a backup; false if another transfer is running. */
    fun startBackup(name: String): Boolean =
        transfer { cam ->
            _backup.value = BackupState.Running(0)
            try {
                val file = cam.downloadBackup { bytes -> _backup.value = BackupState.Running(bytes) }
                val info = (state.value as? ConnState.Connected)?.info ?: CameraInfo()
                val meta = withContext(Dispatchers.IO) { store.save(name, info, file) }
                _backup.value = BackupState.Done(meta)
            } catch (c: CancellationException) {
                _backup.value = BackupState.Idle
                throw c
            } catch (t: Throwable) {
                ProtocolLog.add("backup failed: $t")
                _backup.value = BackupState.Failed(friendly(t))
            }
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

    /** Start a restore; false if it can't run now (another transfer, or see [restoreBlocker]). */
    fun startRestore(meta: BackupMeta): Boolean {
        restoreBlocker(meta)?.let {
            _restore.value = RestoreState.Failed(it)
            return false
        }
        return transfer { cam ->
            _restore.value = RestoreState.Running(meta.name, 0, meta.size)
            try {
                val data = withContext(Dispatchers.IO) { store.dataFile(meta).readBytes() }
                cam.restore(data) { sent, total -> _restore.value = RestoreState.Running(meta.name, sent, total) }
                _restore.value = RestoreState.Done(meta.name)
            } catch (c: CancellationException) {
                _restore.value = RestoreState.Idle
                throw c
            } catch (t: Throwable) {
                ProtocolLog.add("restore failed: $t")
                _restore.value = RestoreState.Failed(friendly(t))
            }
        }
    }

    /** Forget finished backup/restore results (when the Backups screen opens). */
    fun clearTransferResults() {
        if (_backup.value !is BackupState.Running) _backup.value = BackupState.Idle
        if (_restore.value !is RestoreState.Running) _restore.value = RestoreState.Idle
    }

    private fun friendly(t: Throwable): String = when (t) {
        is UnsupportedCameraException, is CameraBusyException, is FileTransferException -> t.message ?: "Failed."
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
        const val PAIRING_MESSAGE = "Pairing: confirm the code on the camera (MENU/OK) and on this phone."
    }
}
