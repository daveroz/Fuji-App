package com.fujiapp.remote.wifi

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.ble.FujiCamera
import com.fujiapp.remote.ble.FujiProtocol
import com.fujiapp.remote.usb.Ptp
import com.fujiapp.remote.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import kotlin.math.roundToInt

/** Where the Wi-Fi connection stands. */
sealed interface WifiPhase {
    data object Idle : WifiPhase
    data class Working(val message: String) : WifiPhase
    data object Photos : WifiPhase
    data object Remote : WifiPhase
    data class Failed(val message: String) : WifiPhase
}

/** A photo on the card, with its thumbnail once loaded. */
data class CardPhoto(val photo: FujiWifi.Photo, val thumb: Bitmap?, val imported: Boolean)

/**
 * Photos and remote shooting over the camera's Wi-Fi, started from Bluetooth like XApp: wake the
 * camera's Wi-Fi, join it, connect, wait for OK on the camera, then browse/import or shoot.
 */
class WifiSession(context: Context, private val camera: CameraSession) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val join = WifiJoin(app)
    private val io = Mutex()
    private val prefs = app.getSharedPreferences("imported", Context.MODE_PRIVATE)
    private var wifi: FujiWifi? = null
    private var live: FujiWifi.LiveView? = null
    private var job: Job? = null
    private var liveJob: Job? = null
    private var handles: List<Int> = emptyList()
    private var listed = 0

    private val _phase = MutableStateFlow<WifiPhase>(WifiPhase.Idle)
    val phase: StateFlow<WifiPhase> = _phase.asStateFlow()
    private val _photos = MutableStateFlow<List<CardPhoto>>(emptyList())
    val photos: StateFlow<List<CardPhoto>> = _photos.asStateFlow()
    private val _progress = MutableStateFlow<String?>(null)
    /** What an import or a listing is doing, or null. */
    val progress: StateFlow<String?> = _progress.asStateFlow()
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()
    private val _readings = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Remote readings: shutter, aperture, ISO, exposure compensation, battery. */
    val readings: StateFlow<Map<String, String>> = _readings.asStateFlow()
    var isoChoices: List<Long> = emptyList()
        private set
    private val _focused = MutableStateFlow(false)
    /** True while the remote holds a half press (focus locked). */
    val focused: StateFlow<Boolean> = _focused.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    /** The remote's last word: a shot taken, a refusal. */
    val note: StateFlow<String?> = _note.asStateFlow()
    private var pollJob: Job? = null

    val totalOnCard: Int get() = handles.size
    val canLoadMore: Boolean get() = listed < handles.size

    fun openPhotos() = start(FujiCamera.WIFI_VIEW_IMAGES, "photos") { w ->
        handles = io.withLock { withContext(Dispatchers.IO) { w.startGallery() } }
        AppLog.add("wifi: ${handles.size} pictures on the card")
        listed = 0
        _photos.value = emptyList()
        _phase.value = WifiPhase.Photos
        listMore(w)
    }

    fun loadMore() {
        val w = wifi ?: return
        if (_phase.value != WifiPhase.Photos || job?.isActive == true) return
        job = scope.launch { runCatching { listMore(w) }.onFailure { fail("list", it) } }
    }

    /** List the next page, newest first, then fetch the thumbnails. */
    private suspend fun listMore(w: FujiWifi) {
        val page = handles.reversed().drop(listed).take(PAGE)
        listed += page.size
        var unreadable = 0
        for ((i, h) in page.withIndex()) {
            _progress.value = "Reading the card… ${i + 1} of ${page.size}"
            val p = io.withLock { withContext(Dispatchers.IO) { w.describe(h) } }
            if (p == null) {
                unreadable++
                continue
            }
            _photos.value = _photos.value + CardPhoto(p, null, isImported(p))
        }
        for (cp in _photos.value.filter { it.thumb == null }) {
            val bytes = io.withLock { withContext(Dispatchers.IO) { runCatching { w.thumbnail(cp.photo.handle) }.getOrNull() } } ?: continue
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: continue
            _photos.value = _photos.value.map { if (it.photo.handle == cp.photo.handle) it.copy(thumb = bmp) else it }
        }
        _progress.value = if (unreadable > 0) "The camera didn't describe $unreadable of ${page.size} pictures." else null
    }

    /** Copy [selected] into the phone's Pictures (Downloads for RAW and video). */
    fun importPhotos(selected: List<FujiWifi.Photo>) {
        val w = wifi ?: return
        if (job?.isActive == true || selected.isEmpty()) return
        AppLog.ui("wifi: import ${selected.size}")
        job = scope.launch {
            var done = 0
            try {
                for (p in selected) {
                    if (isImported(p)) { done++; continue }
                    io.withLock {
                        withContext(Dispatchers.IO) {
                            save(p) { out ->
                                w.download(p, { out.write(it) }) { got ->
                                    _progress.value = "Importing ${p.name} (${done + 1} of ${selected.size}) · ${(got * 100 / maxOf(1, p.size))}%"
                                }
                            }
                        }
                    }
                    markImported(p)
                    done++
                    _photos.value = _photos.value.map { if (it.photo.handle == p.handle) it.copy(imported = true) else it }
                }
                _progress.value = "Imported $done ${if (done == 1) "picture" else "pictures"} to Pictures/Fuji Remote."
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.error("wifi import", t)
                _progress.value = "Import stopped after $done: ${t.message}"
            }
        }
    }

    private fun save(p: FujiWifi.Photo, write: (OutputStream) -> Unit) {
        val ext = p.name.substringAfterLast('.', "").lowercase(Locale.US)
        val image = ext in setOf("jpg", "jpeg", "heif", "hif", "heic")
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "heif", "hif", "heic" -> "image/heif"
            "mov" -> "video/quicktime"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val dir = if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, p.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Fuji Remote")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create ${p.name}")
            try {
                resolver.openOutputStream(uri)?.use(write) ?: throw IOException("Couldn't write ${p.name}")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Fuji Remote").apply { mkdirs() }
            File(dir, p.name).outputStream().use(write)
        }
        AppLog.add("wifi: imported ${p.name} (${p.size} bytes)")
    }

    private fun key(p: FujiWifi.Photo) = "${p.name}:${p.size}"
    private fun isImported(p: FujiWifi.Photo) = prefs.getBoolean(key(p), false)
    private fun markImported(p: FujiWifi.Photo) = prefs.edit { putBoolean(key(p), true) }

    // ---- remote ----

    fun openRemote() = start(FujiCamera.WIFI_REMOTE, "remote") { w ->
        val view = io.withLock { withContext(Dispatchers.IO) { w.startRemote() } }
        live = view
        isoChoices = io.withLock { withContext(Dispatchers.IO) { runCatching { w.allowedValues(FujiWifi.ISO) }.getOrDefault(emptyList()) } }
        values.clear()
        _focused.value = false
        _note.value = null
        _phase.value = WifiPhase.Remote
        refreshReadings(full = true)
        liveJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val jpeg = view.frame() ?: continue
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { _frame.value = it }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.add("wifi: live view ended: ${t.message}")
            }
        }
        // The camera's dials win over the app: follow them as they turn (XApp polls the same list).
        pollJob = scope.launch {
            var n = 0
            while (isActive && _phase.value == WifiPhase.Remote) {
                delay(POLL_MS)
                runCatching { refreshReadings(full = ++n % FULL_EVERY == 0) }.onFailure { AppLog.add("remote: poll failed: ${it.message}") }
            }
        }
    }

    /** Take a picture over Wi-Fi; if the camera won't, use the Bluetooth release. */
    fun shoot() = remote("shoot") { w ->
        val code = w.capture()
        AppLog.add("wifi: capture -> ${Ptp.responseName(code)}")
        if (_focused.value) {
            runCatching { w.releaseFocus() }
            _focused.value = false
        }
        if (code == Ptp.OK) {
            _note.value = "Picture taken."
        } else if (camera.isConnected) {
            withContext(Dispatchers.Main) { camera.shutterPress(); camera.shutterRelease(shoot = true) }
            _note.value = "Taken with the Bluetooth release."
        } else {
            _note.value = "The camera didn't take the picture (${Ptp.responseName(code)})."
        }
    }

    /** Half press: focus (at a point of the live view, 0–1 across and down) and hold it. */
    fun focus(at: Pair<Float, Float>? = null) = remote(if (at == null) "focus" else "focus at %.2f,%.2f".format(Locale.US, at.first, at.second)) { w ->
        if (_focused.value) runCatching { w.releaseFocus() }
        val code = w.focus(at)
        AppLog.add("wifi: focus -> ${Ptp.responseName(code)}")
        _focused.value = code == Ptp.OK
        _note.value = if (code == Ptp.OK) "Focus locked. Tap the shutter to shoot, or Focus again to let go." else "The camera didn't focus (${Ptp.responseName(code)})."
    }

    fun releaseFocus() = remote("release focus") { w ->
        val code = w.releaseFocus()
        AppLog.add("wifi: release focus -> ${Ptp.responseName(code)}")
        _focused.value = false
        _note.value = null
    }

    fun stepShutter(up: Boolean) = remote("shutter ${if (up) "+" else "-"}") { step(it, FujiWifi.STEP_SHUTTER_SPEED, up, "shutter speed") }
    fun stepAperture(up: Boolean) = remote("aperture ${if (up) "+" else "-"}") { step(it, FujiWifi.STEP_APERTURE, up, "aperture") }
    fun stepExposure(up: Boolean) = remote("exposure ${if (up) "+" else "-"}") { step(it, FujiWifi.STEP_EXPOSURE_BIAS, up, "exposure") }
    fun setIso(value: Long) = remote("iso $value") {
        if (!it.set32(FujiWifi.ISO, value.toInt())) _note.value = "The camera kept its ISO: its ISO dial is probably set to a value."
    }

    private fun step(w: FujiWifi, op: Int, up: Boolean, what: String) {
        val code = w.step(op, up)
        _note.value = if (code == Ptp.OK) null else "The camera didn't change the $what: its dial is set to a value."
    }

    private fun remote(what: String, action: suspend (FujiWifi) -> Unit) {
        val w = wifi ?: return
        if (_phase.value != WifiPhase.Remote) return
        AppLog.ui("remote: $what")
        scope.launch {
            try {
                io.withLock { withContext(Dispatchers.IO) { action(w) } }
                refreshReadings(full = false)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.error("remote $what", t)
            }
        }
    }

    /** What the camera reported, by property code: its event list fills in what changed. */
    private val values = HashMap<Int, Long>()
    private var loggedEvents = false

    /** Read the camera's event list (and with [full], each shown property) and update the readings. */
    private suspend fun refreshReadings(full: Boolean) {
        val w = wifi ?: return
        val changed = io.withLock {
            withContext(Dispatchers.IO) {
                val events = runCatching { w.events() }.getOrDefault(emptyMap())
                if (!loggedEvents && events.isNotEmpty()) {
                    loggedEvents = true
                    AppLog.add("remote: camera reports ${events.entries.joinToString { "%04X=%X".format(it.key, it.value) }}")
                }
                val fresh = HashMap(events)
                if (full || values.isEmpty()) {
                    for (p in SHOWN) if (p !in fresh) w.get(p)?.let { d -> number(d)?.let { fresh[p] = it } }
                }
                val diff = fresh.filter { (k, v) -> values[k] != v }
                values.putAll(fresh)
                diff to readings()
            }
        }
        val (diff, now) = changed
        if (diff.isEmpty() && _readings.value.isNotEmpty()) return
        if (diff.isNotEmpty() && _readings.value.isNotEmpty()) {
            AppLog.add("remote: camera changed ${diff.entries.joinToString { "%04X=%X".format(it.key, it.value) }}")
        }
        _readings.value = now
    }

    private fun number(d: ByteArray): Long? = when {
        d.size >= 4 -> FujiWifi.u32(d, 0).toLong() and 0xFFFFFFFFL
        d.size >= 2 -> (FujiProtocol.u16(d) ?: 0).toLong()
        else -> null
    }

    private fun readings(): Map<String, String> {
        val v = values
        return mapOf(
            "Shutter" to (v[FujiWifi.SHUTTER_SPEED]?.let(FujiWifi::shutterText) ?: v[FujiWifi.EXPOSURE_TIME]?.let(::shutterText)),
            "Aperture" to v[FujiWifi.F_NUMBER]?.let { if (it in 1..0xFFFE) "f/" + trim(it / 100.0) else null },
            "ISO" to v[FujiWifi.ISO]?.let(::isoText),
            "Exposure" to v[FujiWifi.EXPOSURE_BIAS]?.let { ev -> val e = ev.toInt().toShort() / 1000.0; (if (e > 0) "+" else "") + trim(e) },
            "Mode" to v[FujiWifi.SHOOTING_MODE]?.let(::modeText),
            "Battery" to (v[FujiWifi.BATTERY_LEVELS]?.let(::batteryText) ?: v[FujiWifi.BATTERY]?.let { if (it in 1..3) "${it * 100 / 3}%" else null }),
        ).filterValues { it != null }.mapValues { it.value!! }
    }

    /** Standard PTP exposure program. */
    private fun modeText(v: Long): String? = when (v) {
        1L -> "M"
        2L -> "P"
        3L -> "A"
        4L -> "S"
        else -> null
    }

    /** Fujifilm's five-bar battery: 6–11 (6 and 7 both the lowest bar), as fuji-bridge reads it. */
    private fun batteryText(v: Long): String? = if (v in 6..11) "${(maxOf(7L, v) - 6) * 20}%" else null

    // ---- connection ----

    private fun start(function: Int, what: String, then: suspend (FujiWifi) -> Unit) {
        if (job?.isActive == true) return
        close()
        AppLog.ui("wifi: open $what")
        job = scope.launch {
            try {
                _phase.value = WifiPhase.Working("Asking the camera to start its Wi-Fi…")
                // The camera closes Bluetooth once its Wi-Fi is up; reconnecting then would pull it off Wi-Fi.
                camera.setWifiActive(true)
                val net = camera.wakeWifi(
                    function,
                    onQueued = { _phase.value = WifiPhase.Working("Waiting for the camera to finish over Bluetooth…") },
                    onStarted = { _phase.value = WifiPhase.Working("Asking the camera to start its Wi-Fi…") },
                    onBusy = { _phase.value = WifiPhase.Working("The camera is still closing its last Wi-Fi connection…") },
                )
                AppLog.add("wifi: camera network ${net.ssid}")
                _phase.value = WifiPhase.Working("Joining ${net.ssid}… Allow the connection when Android asks.")
                val joinStarted = System.currentTimeMillis()
                val network = try {
                    join.join(net.ssid, net.password)
                } catch (e: IOException) {
                    // A quick "unavailable" usually means Android scanned before the camera's network was up.
                    if (System.currentTimeMillis() - joinStarted > JOIN_RETRY_WITHIN_MS) throw e
                    AppLog.add("wifi: join failed (${e.message}); trying once more")
                    _phase.value = WifiPhase.Working("Joining ${net.ssid} again…")
                    delay(2_000)
                    join.join(net.ssid, net.password)
                }
                AppLog.add("wifi: joined ${net.ssid}")
                _phase.value = WifiPhase.Working("Connecting to the camera…")
                val w = withContext(Dispatchers.IO) {
                    FujiWifi.connect(network.socketFactory, CLIENT_NAME, AppLog::add) {
                        _phase.value = WifiPhase.Working("On the camera, press OK to allow the connection.")
                    }
                }
                wifi = w
                withContext(Dispatchers.IO) {
                    w.waitForAccess(ACCESS_TIMEOUT_MS) { _phase.value = WifiPhase.Working("Press OK on the camera to allow the connection.") }
                }
                then(w)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(what, t)
            }
        }
    }

    private fun fail(what: String, t: Throwable) {
        AppLog.error("wifi $what", t)
        _phase.value = WifiPhase.Failed(t.message ?: t.javaClass.simpleName)
        cleanup()
        camera.setWifiActive(false)
    }

    /** Leave Wi-Fi: close the sockets and let Android drop the camera's network. */
    fun close() {
        if (_phase.value != WifiPhase.Idle) AppLog.ui("wifi: close")
        job?.cancel()
        job = null
        cleanup()
        camera.setWifiActive(false)
        _phase.value = WifiPhase.Idle
        _progress.value = null
    }

    private fun cleanup() {
        pollJob?.cancel()
        pollJob = null
        liveJob?.cancel()
        liveJob = null
        _focused.value = false
        _note.value = null
        values.clear()
        loggedEvents = false
        live?.close()
        live = null
        val w = wifi
        wifi = null
        if (w != null) scope.launch(Dispatchers.IO) { runCatching { w.close() } }
        join.release()
        _frame.value = null
    }

    private fun trim(v: Double): String = if (v == v.roundToInt().toDouble()) "${v.roundToInt()}" else String.format(Locale.US, "%.1f", v)

    /** Standard PTP exposure time: seconds × 10000. */
    private fun shutterText(v: Long): String = when {
        v <= 0 -> "—"
        v >= 10_000 -> "${trim(v / 10_000.0)}s"
        else -> "1/${(10_000.0 / v).roundToInt()}"
    }

    private fun isoText(v: Long): String {
        val iso = v and 0x00FFFFFF
        return if (v and 0x80000000L != 0L || iso == 0L) "Auto" + (if (iso > 0) " $iso" else "") else "$iso"
    }

    companion object {
        private const val PAGE = 48
        /** The name the camera shows for this phone, as over Bluetooth. */
        private const val CLIENT_NAME = "Fuji Remote"
        private const val ACCESS_TIMEOUT_MS = 120_000L
        private const val POLL_MS = 600L
        private const val JOIN_RETRY_WITHIN_MS = 20_000L
        /** Every this many polls, read the shown properties directly too. */
        private const val FULL_EVERY = 10
        private val SHOWN = listOf(
            FujiWifi.SHUTTER_SPEED, FujiWifi.EXPOSURE_TIME, FujiWifi.F_NUMBER, FujiWifi.ISO,
            FujiWifi.EXPOSURE_BIAS, FujiWifi.SHOOTING_MODE, FujiWifi.BATTERY_LEVELS, FujiWifi.BATTERY,
        )
    }
}
