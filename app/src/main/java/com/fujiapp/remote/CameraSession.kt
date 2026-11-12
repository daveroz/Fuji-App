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
import com.fujiapp.remote.ble.DownloadedFile
import com.fujiapp.remote.ble.Capabilities
import com.fujiapp.remote.ble.FileTransferException
import com.fujiapp.remote.ble.FoundCamera
import com.fujiapp.remote.ble.FujiCamera
import com.fujiapp.remote.ble.FujiGatt
import com.fujiapp.remote.ble.FujiProtocol
import com.fujiapp.remote.ble.GattClient
import com.fujiapp.remote.ble.GattException
import com.fujiapp.remote.ble.NoAnswerException
import com.fujiapp.remote.ble.UnsupportedCameraException
import com.fujiapp.remote.data.BackupStore
import com.fujiapp.remote.model.BackupMeta
import com.fujiapp.remote.model.CameraInfo
import com.fujiapp.remote.model.CameraSnapshot
import com.fujiapp.remote.model.CameraVitals
import com.fujiapp.remote.model.SettingsFile
import com.fujiapp.remote.util.AppLog
import com.fujiapp.remote.util.AppSettings
import com.fujiapp.remote.util.PhoneLocation
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
import java.time.Instant
import java.time.ZoneId

/** Connection state shown by the UI. */
sealed interface ConnState {
    data object Idle : ConnState
    /** Cameras seen so far; [active] while the scan is still running. */
    data class Scanning(val cameras: List<FoundCamera>, val active: Boolean) : ConnState
    data class Connecting(val message: String) : ConnState
    data class Connected(
        val snapshot: CameraSnapshot,
        val capabilities: Capabilities,
        val hasShutter: Boolean,
    ) : ConnState {
        val info: CameraInfo get() = snapshot.info
    }
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
    data class Loaded(val vitals: CameraVitals, val readAt: Long) : VitalsState
    data class Failed(val message: String) : VitalsState
}

/** The custom settings (C1–C7) read from the camera's settings file. */
sealed interface RecipesState {
    data object Idle : RecipesState
    data object Loading : RecipesState
    data class Loaded(val file: SettingsFile, val download: DownloadedFile, val readAt: Long) : RecipesState
    data class Failed(val message: String) : RecipesState
}

/** Pairing didn't complete; retrying on our own won't help. */
private class PairingFailedException(message: String) : IOException(message)

/**
 * Owns the Bluetooth connection to the camera: scanning, pairing, registration, live status,
 * clock and location sync, camera settings, the remote shutter, shutter count, backups and
 * restores. One instance per process, held by [App].
 *
 * The camera's link is flaky in practice (refused writes right after connecting, an empty service
 * list after a quick reconnect, dropped links), and a fresh connection reliably fixes it. So
 * connecting retries, a dropped link reconnects on its own, and backups / shutter-count reads
 * retry once on a fresh connection.
 */
@SuppressLint("MissingPermission")
class CameraSession(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    val store = BackupStore(app)
    val settings = AppSettings(app)
    val location = PhoneLocation(app)

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _backup = MutableStateFlow<BackupState>(BackupState.Idle)
    val backup: StateFlow<BackupState> = _backup.asStateFlow()

    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore.asStateFlow()

    private val _vitals = MutableStateFlow<VitalsState>(VitalsState.Idle)
    val vitals: StateFlow<VitalsState> = _vitals.asStateFlow()

    private val _recipes = MutableStateFlow<RecipesState>(RecipesState.Idle)
    val recipes: StateFlow<RecipesState> = _recipes.asStateFlow()

    /** Short messages for the user (a setting written, a shot taken, a time sync). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** One camera operation at a time (info read, setting write or file transfer). */
    private val opLock = Mutex()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var transferJob: Job? = null
    private var monitorJob: Job? = null
    private var gatt: GattClient? = null
    private var camera: FujiCamera? = null
    private var userDisconnected = false
    /** The camera asked for the location without permission to answer: told the user once per connection. */
    private var toldAboutLocation = false

    val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    val isConnected: Boolean get() = _state.value is ConnState.Connected

    /** True while a camera operation (backup, restore, shutter count, refresh) is running. */
    val isTransferring: Boolean get() = opLock.isLocked

    fun clearNotice() { _notice.value = null }

    private fun notify(message: String) {
        AppLog.add("notice: $message")
        _notice.value = message
    }

    // ---- paired cameras and their identification numbers ----

    /** Cameras this app registered with that are still bonded to the phone. */
    fun pairedCameras(): List<BluetoothDevice> {
        val addresses = prefs.getStringSet(KEY_ADDRESSES, emptySet()) ?: emptySet()
        val names = prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded.filter { d ->
            d.address in addresses || runCatching { d.name }.getOrNull()?.let { it in names } == true
        }
    }

    private fun remember(device: BluetoothDevice, model: String?, id: Long) {
        val addresses = (prefs.getStringSet(KEY_ADDRESSES, emptySet()) ?: emptySet()) + device.address
        val names = (prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()) +
            listOfNotNull(model, runCatching { device.name }.getOrNull())
        prefs.edit {
            putStringSet(KEY_ADDRESSES, addresses)
            putStringSet(KEY_NAMES, names)
            putLong(KEY_ID_PREFIX + device.address, id)
            putLong(KEY_LAST_ID, id)
        }
    }

    /** The identification number this phone registered with, for [device] (or the last camera). */
    private fun storedId(device: BluetoothDevice): Long? = when {
        prefs.contains(KEY_ID_PREFIX + device.address) -> prefs.getLong(KEY_ID_PREFIX + device.address, 0)
        prefs.contains(KEY_LAST_ID) -> prefs.getLong(KEY_LAST_ID, 0)
        else -> null
    }?.takeIf { it != 0L }

    // ---- scanning ----

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            _state.value = ConnState.Error("Turn Bluetooth on, then try again.")
            return
        }
        if (_state.value is ConnState.Connecting || _state.value is ConnState.Connected) return
        scanJob?.cancel()
        AppLog.add("scan: start")
        val found = LinkedHashMap<String, FoundCamera>()
        _state.value = ConnState.Scanning(emptyList(), active = true)
        scanJob = scope.launch {
            try {
                // Stop on its own: a camera advertises within seconds, longer only drains the battery.
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    CameraScanner(a).scan().collect { cam ->
                        if (cam.device.address !in found) AppLog.add("scan: found ${cam.name} ${cam.advert}")
                        found[cam.device.address] = cam
                        _state.value = ConnState.Scanning(found.values.toList(), active = true)
                    }
                }
                _state.value = ConnState.Scanning(found.values.toList(), active = false)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.error("scan", t)
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
        _recipes.value = RecipesState.Idle
        connectJob = scope.launch { connectLoop(device, waitForCamera) }
    }

    /**
     * Connect, retrying temporary failures, and stay connected: when the link drops, reconnect.
     * Returns when the user disconnects or retrying gives up.
     */
    private suspend fun connectLoop(device: BluetoothDevice, waitForCamera: Boolean) {
        var attempt = 0
        var reconnecting = false
        var storedRejected = false
        while (true) {
            attempt++
            val client = GattClient(app, device) { AppLog.add(it) }
            gatt = client
            try {
                val wait = waitForCamera || reconnecting || attempt > 1
                _state.value = ConnState.Connecting(
                    when {
                        reconnecting -> "Connection lost. Reconnecting…"
                        attempt > 1 -> "Retrying ($attempt of $MAX_ATTEMPTS)…"
                        waitForCamera -> "Waiting for the camera. Switch it on, or off and on again."
                        else -> "Connecting…"
                    }
                )
                val usedStored = setUp(
                    client, device, autoConnect = wait,
                    timeoutMs = if (waitForCamera && attempt == 1) 90_000 else 30_000,
                    preferCameraValue = storedRejected,
                )
                if (usedStored) storedRejected = false
                attempt = 0
                reconnecting = false

                client.disconnected.first { it }
                if (gatt !== client || userDisconnected) return
                AppLog.add("connection lost, reconnecting")
                cleanup()
                reconnecting = true
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.add("connect attempt $attempt failed: $t")
                if (t is RegistrationRejected && t.usedStored) storedRejected = true
                if (gatt === client) cleanup()
                if (userDisconnected) return
                val cause = (t as? RegistrationRejected)?.cause ?: t
                if (!isTemporary(cause) || attempt >= MAX_ATTEMPTS) {
                    _state.value = ConnState.Error(if (reconnecting) "The camera disconnected." else friendly(cause))
                    return
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /** Registration failed; remembers whether the stored identification number was written. */
    private class RegistrationRejected(val usedStored: Boolean, override val cause: Throwable) : IOException(cause.message, cause)

    /**
     * Link, pair if needed, register, read everything and start listening. Ends in
     * [ConnState.Connected]. Returns whether the stored identification number was used.
     */
    private suspend fun setUp(
        client: GattClient,
        device: BluetoothDevice,
        autoConnect: Boolean,
        timeoutMs: Long,
        preferCameraValue: Boolean,
    ): Boolean {
        client.connect(autoConnect = autoConnect, timeoutMs = timeoutMs)

        var freshPairing = false
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _state.value = ConnState.Connecting(PAIRING_MESSAGE)
            if (!Bonding.bond(app, device)) {
                throw PairingFailedException(
                    "Pairing didn't complete. Start PAIRING REGISTRATION on the camera, then try again, " +
                        "and confirm the code on both the camera and the phone."
                )
            }
            freshPairing = true
            AppLog.add("paired with ${device.address}")
        }

        runCatching { client.requestMtu() }
        client.discoverServices(required = listOf(FujiGatt.SVC_CONNECTED_DEVICE, FujiGatt.SVC_LEGACY_PAIRING))

        val cam = FujiCamera(client) { AppLog.add(it) }
        val stored = if (preferCameraValue) null else storedId(device)
        val id = try {
            cam.register(stored, freshPairing) { _state.value = ConnState.Connecting(PAIRING_MESSAGE) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw RegistrationRejected(usedStored = stored != null && !freshPairing, cause = t)
        }
        val snapshot = cam.readSnapshot()
        camera = cam
        remember(device, snapshot.info.model, id)
        _state.value = ConnState.Connected(snapshot, cam.capabilities, cam.hasShutter)
        AppLog.add("connected: ${snapshot.info.displayName} fw ${snapshot.info.firmware}")
        toldAboutLocation = false
        startMonitor(client, cam)
        updateLocationListening()
        // The shutter count is the first thing people look for: read it once per connection.
        if (cam.capabilities.vitals && _vitals.value !is VitalsState.Loaded) {
            scope.launch {
                delay(AUTO_READ_DELAY_MS)
                // Let an operation the user started first finish.
                withTimeoutOrNull(AUTO_READ_WAIT_MS) { while (opLock.isLocked) delay(250) }
                val v = _vitals.value
                if (camera === cam && v !is VitalsState.Loaded && v !is VitalsState.Loading) fetchVitals(user = false)
            }
        }
        return stored != null && !freshPairing
    }

    /** React to what the camera pushes: time and location requests, and live status values. */
    private fun startMonitor(client: GattClient, cam: FujiCamera) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            client.values.collect { (uuid, value) ->
                when (uuid) {
                    FujiGatt.DATE_SYNC_STATE.uuid -> if (FujiProtocol.u16(value) == 1 && settings.syncTime) launch {
                        try {
                            cam.sendTime(Instant.now(), ZoneId.systemDefault())
                            AppLog.add("time sync: sent the phone's clock")
                        } catch (t: Throwable) {
                            if (t !is CancellationException) AppLog.add("time sync failed: $t")
                        }
                    }
                    FujiGatt.LOCATION_SYNC_STATE.uuid -> if (FujiProtocol.u16(value) == 1) launch {
                        answerLocationRequest(cam)
                    }
                }
                FujiGatt.READABLE.firstOrNull { it.uuid == uuid }?.let { c -> updateSnapshot { it.with(c, value) } }
                if (uuid == FujiGatt.LOCATION_SYNC_SETTING.uuid) updateLocationListening()
            }
        }
    }

    /**
     * Answer the camera's location request. The camera only asks while its location sync is on,
     * and requests left unanswered during a transfer made it drop the link, so answer every one.
     */
    private suspend fun answerLocationRequest(cam: FujiCamera) {
        if (!location.hasPermission()) {
            AppLog.add("location request: no location permission")
            if (!toldAboutLocation) {
                toldAboutLocation = true
                notify("The camera is asking for your location to geotag pictures. Turn on Location sync in Settings, or turn it off there to stop the requests.")
            }
            return
        }
        location.start()
        val fix = location.current()
        if (fix == null) {
            AppLog.add("location request: no recent fix yet")
            return
        }
        try {
            cam.sendLocation(
                FujiProtocol.geotagPacket(
                    fix.latitude, fix.longitude,
                    if (fix.hasAltitude()) fix.altitude else 0.0,
                    if (fix.hasSpeed()) fix.speed.toDouble() else 0.0,
                    Instant.now(),
                )
            )
            AppLog.add("location request: answered (fix ${(System.currentTimeMillis() - fix.time) / 1000} s old)")
        } catch (t: Throwable) {
            if (t !is CancellationException) AppLog.add("location answer failed: $t")
        }
    }

    /** Listen to the phone's location while the camera may ask for it (its location sync is on). */
    private fun updateLocationListening() {
        val on = (_state.value as? ConnState.Connected)?.snapshot?.locationSyncOn == true && location.hasPermission()
        if (on) location.start() else location.stop()
    }

    private fun updateSnapshot(change: (CameraSnapshot) -> CameraSnapshot) {
        val s = _state.value as? ConnState.Connected ?: return
        _state.value = s.copy(snapshot = change(s.snapshot))
    }

    fun disconnect() {
        AppLog.add("disconnect (user)")
        userDisconnected = true
        connectJob?.cancel()
        transferJob?.cancel()
        cleanup()
        _vitals.value = VitalsState.Idle
        _recipes.value = RecipesState.Idle
        _state.value = ConnState.Idle
    }

    private fun cleanup() {
        monitorJob?.cancel()
        monitorJob = null
        location.stop()
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
        AppLog.add("reconnecting to recover")
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

    // ---- app settings ----

    fun setSyncTime(on: Boolean) {
        settings.syncTime = on
        AppLog.ui("setting: sync time = $on")
    }

    /**
     * Location sync is the camera's own setting: while it's on, the camera asks for the phone's
     * location every few seconds and the app answers. The caller has asked for the location
     * permission before turning it on.
     */
    fun setLocationSync(on: Boolean) =
        writeSetting("location sync ${if (on) "on" else "off"}", FujiGatt.LOCATION_SYNC_SETTING) {
            it.setLocationSyncSetting(on)
        }

    // ---- camera operations ----

    /** Read everything the camera reports again, then the shutter count. */
    fun refreshAll() {
        AppLog.ui("refresh")
        scope.launch {
            opLock.withLock {
                val cam = camera ?: return@withLock
                try {
                    val fresh = cam.readSnapshot()
                    updateSnapshot { old -> CameraSnapshot(old.values + fresh.values) }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    AppLog.add("refresh failed: $t")
                }
            }
            if ((_state.value as? ConnState.Connected)?.capabilities?.vitals == true) fetchVitals()
        }
    }

    /** Write a camera setting, then read it back so the screen shows what the camera holds. */
    private fun writeSetting(what: String, readBack: com.fujiapp.remote.ble.GattChar, write: suspend (FujiCamera) -> Unit) {
        AppLog.ui("camera setting: $what")
        scope.launch {
            opLock.withLock {
                val cam = camera ?: return@withLock
                try {
                    write(cam)
                    cam.read(readBack)?.let { v -> updateSnapshot { it.with(readBack, v) } }
                    updateLocationListening()
                    notify("Camera updated: $what")
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    AppLog.add("setting failed ($what): $t")
                    notify("Couldn't change $what: ${friendly(t)}")
                }
            }
        }
    }

    fun setCameraLocationInterval(seconds: Int) =
        writeSetting("location interval $seconds s", FujiGatt.LOCATION_SYNC_CYCLE) { it.setLocationSyncInterval(seconds) }

    fun setCameraConnectWhileOff(on: Boolean) =
        writeSetting("connect while off ${if (on) "on" else "off"}", FujiGatt.REMOTE_BOOT_SETTING) { it.setConnectWhilePowerOff(on) }

    /** Remote release: press = half press (focus), release with [shoot] = full press, then let go. */
    fun shutterPress() {
        val cam = camera ?: return
        AppLog.ui("shutter: half press")
        scope.launch { runCatching { cam.shutter(FujiCamera.SHUTTER_HALF) }.onFailure { AppLog.add("shutter failed: $it") } }
    }

    fun shutterRelease(shoot: Boolean) {
        val cam = camera ?: return
        AppLog.ui(if (shoot) "shutter: full press" else "shutter: cancelled")
        scope.launch {
            try {
                if (shoot) {
                    cam.shutter(FujiCamera.SHUTTER_FULL)
                    delay(120)
                }
                cam.shutter(FujiCamera.SHUTTER_RELEASE)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.add("shutter failed: $t")
                notify("The camera didn't take the shot: ${friendly(t)}")
            }
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
                    awaitReady()
                    Result.success(op(cam))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (!retryOnFreshConnection || t is UnsupportedCameraException || userDisconnected) throw t
                    AppLog.add("operation failed ($t), retrying on a fresh connection")
                    // If this connection is still up, drop it; if it already dropped, connectLoop is on it.
                    if (camera === cam) forceReconnect()
                    val fresh = awaitNewConnection(cam) ?: throw t
                    awaitReady()
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

    /**
     * The camera reports its power switch once it has finished its side of the connection; until
     * then it ignores transfer requests. Wait a few seconds for that, then go ahead regardless.
     */
    private suspend fun awaitReady() {
        fun ready() = (_state.value as? ConnState.Connected)?.snapshot?.info?.power != null
        if (ready()) return
        AppLog.add("waiting for the camera to finish connecting")
        val ok = withTimeoutOrNull(READY_TIMEOUT_MS) { state.first { ready() } } != null
        if (!ok) AppLog.add("the camera didn't report its power switch; trying anyway")
    }

    /** Read the shutter count and usage data. */
    fun fetchVitals(user: Boolean = true) {
        if (user) AppLog.ui("read counters")
        val previous = _vitals.value
        if (previous is VitalsState.Loading) return
        // Set before starting: the operation may finish (fail) before operation() returns.
        _vitals.value = VitalsState.Loading
        val started = operation(retryOnFreshConnection = true, op = { cam -> cam.readVitals() }) { result ->
            result.onSuccess {
                AppLog.add("vitals: ${it.totalShots} shots, ${it.mechanicalShutter} actuations")
                _vitals.value = VitalsState.Loaded(it, System.currentTimeMillis())
            }
            result.onFailure {
                AppLog.add("vitals failed: $it")
                _vitals.value = VitalsState.Failed(friendly(it))
            }
        }
        if (!started) _vitals.value = previous
    }

    /** Read the C1–C7 custom settings from the camera's settings file (a backup download, kept in memory). */
    fun readRecipes(user: Boolean = true) {
        if (user) AppLog.ui("read recipes")
        val previous = _recipes.value
        if (previous is RecipesState.Loading) return
        _recipes.value = RecipesState.Loading
        val started = operation(
            retryOnFreshConnection = true,
            op = { cam ->
                val file = cam.downloadBackup { }
                val parsed = withContext(Dispatchers.Default) { SettingsFile.parse(file.data) }
                    ?: throw FileTransferException("The camera's settings file couldn't be read.")
                parsed to file
            },
        ) { result ->
            result.onSuccess { (parsed, file) ->
                AppLog.add("recipes: ${parsed.customSlots.joinToString { "${it.label} ${it.name}" }}")
                _recipes.value = RecipesState.Loaded(parsed, file, System.currentTimeMillis())
            }
            result.onFailure {
                AppLog.add("recipes failed: $it")
                _recipes.value = RecipesState.Failed(friendly(it))
            }
        }
        if (!started) _recipes.value = previous
    }

    /** The slots changed over USB: what was read over Bluetooth is out of date. */
    fun forgetRecipes() {
        if (_recipes.value !is RecipesState.Loading) _recipes.value = RecipesState.Idle
    }

    /** Keep the settings file read for the recipes as a backup, without downloading it again. */
    fun saveRecipesAsBackup(name: String): BackupMeta? {
        val loaded = _recipes.value as? RecipesState.Loaded ?: return null
        AppLog.ui("recipes saved as backup \"$name\"")
        val info = (state.value as? ConnState.Connected)?.info ?: CameraInfo(model = loaded.file.model)
        return store.save(name, info, loaded.download).also { notify("Saved \"${it.name}\" to Backups.") }
    }

    /** Start a backup; false if another operation is running. */
    fun startBackup(name: String): Boolean {
        AppLog.ui("backup: start \"$name\"")
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
            result.onSuccess {
                AppLog.add("backup saved: ${it.name}, ${it.size} bytes")
                _backup.value = BackupState.Done(it)
            }
            result.onFailure {
                AppLog.add("backup failed: $it")
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
        AppLog.ui("restore: start \"${meta.name}\"")
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
            result.onSuccess {
                AppLog.add("restore done: ${meta.name}")
                _restore.value = RestoreState.Done(meta.name)
            }
            result.onFailure {
                AppLog.add("restore failed: $it")
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
        const val KEY_ID_PREFIX = "id:"
        const val KEY_LAST_ID = "id:last"
        const val SCAN_TIMEOUT_MS = 30_000L
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_500L
        const val PAIRING_MESSAGE = "Pairing: confirm the code on the camera (MENU/OK) and on this phone."
        const val AUTO_READ_DELAY_MS = 1_500L
        const val READY_TIMEOUT_MS = 5_000L
        const val AUTO_READ_WAIT_MS = 60_000L
    }
}
