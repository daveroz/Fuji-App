package com.fujiapp.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.fujiapp.remote.ble.Bonding
import com.fujiapp.remote.ble.CameraBusyException
import com.fujiapp.remote.ble.CameraScanner
import com.fujiapp.remote.ble.FileTransferException
import com.fujiapp.remote.ble.FoundCamera
import com.fujiapp.remote.ble.FujiCamera
import com.fujiapp.remote.ble.GattClient
import com.fujiapp.remote.ble.GattException
import com.fujiapp.remote.ble.UnsupportedCameraException
import com.fujiapp.remote.data.BackupStore
import com.fujiapp.remote.model.BackupMeta
import com.fujiapp.remote.model.CameraInfo
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Connection state shown by the UI. */
sealed interface ConnState {
    data object Idle : ConnState
    /** Cameras seen so far; [active] while the scan is still running. */
    data class Scanning(val cameras: List<FoundCamera>, val active: Boolean) : ConnState
    data class Connecting(val message: String) : ConnState
    data class Connected(val info: CameraInfo, val supportsBackup: Boolean) : ConnState
    data class Error(val message: String) : ConnState
}

/** Progress of a settings backup. */
sealed interface BackupState {
    data object Idle : BackupState
    data class Running(val bytes: Int) : BackupState
    data class Done(val meta: BackupMeta) : BackupState
    data class Failed(val message: String) : BackupState
}

/**
 * Owns the Bluetooth connection to the camera: scanning, pairing, registration, camera info and
 * backups. One instance per process, held by [App].
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

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var backupJob: Job? = null
    private var gatt: GattClient? = null
    private var camera: FujiCamera? = null

    val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    val isConnected: Boolean get() = _state.value is ConnState.Connected

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
        prefs.edit().putStringSet(KEY_ADDRESSES, addresses).putStringSet(KEY_NAMES, names).apply()
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
                _state.value = ConnState.Connected(info, cam.supportsBackup)

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
        backupJob?.cancel()
        cleanup()
        _state.value = ConnState.Idle
    }

    private fun cleanup() {
        gatt?.close()
        gatt = null
        camera = null
    }

    /** Read the camera info again (power state etc. may have changed). */
    fun refreshInfo() {
        val cam = camera ?: return
        scope.launch {
            try {
                val info = cam.readInfo()
                _state.value = ConnState.Connected(info, cam.supportsBackup)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ProtocolLog.add("refresh failed: $t")
            }
        }
    }

    // ---- backup ----

    fun startBackup(name: String) {
        val cam = camera ?: return
        if (_backup.value is BackupState.Running) return
        backupJob = scope.launch {
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
    }

    fun clearBackupState() {
        if (_backup.value !is BackupState.Running) _backup.value = BackupState.Idle
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
