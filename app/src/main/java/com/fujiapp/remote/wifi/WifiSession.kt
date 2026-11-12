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
    private var listJob: Job? = null
    private var importJob: Job? = null
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
    private var eventJob: Job? = null
    /** When the last picture was taken in remote mode. */
    private var lastCapture = 0L

    val totalOnCard: Int get() = handles.size
    val canLoadMore: Boolean get() = listed < handles.size

    fun openPhotos() = start(FujiCamera.WIFI_VIEW_IMAGES, "photos") { w ->
        handles = io.withLock { withContext(Dispatchers.IO) { w.startGallery() } }
        AppLog.add("wifi: ${handles.size} pictures on the card")
        listed = 0
        _photos.value = emptyList()
        _phase.value = WifiPhase.Photos
        // Its own job, so importing can start while the list is still loading.
        listJob = scope.launch { runCatching { listMore(w) }.onFailure { fail("list", it) } }
    }

    fun loadMore() {
        val w = wifi ?: return
        if (_phase.value != WifiPhase.Photos || listJob?.isActive == true) return
        listJob = scope.launch { runCatching { listMore(w) }.onFailure { fail("list", it) } }
    }

    /**
     * List the next page, newest first: each picture's details, then right away its thumbnail (the
     * camera serves a thumbnail only for the picture it just described).
     */
    private suspend fun listMore(w: FujiWifi) {
        val page = handles.reversed().drop(listed).take(PAGE)
        listed += page.size
        var unreadable = 0
        var thumbs = true
        for ((i, h) in page.withIndex()) {
            _progress.value = "Reading the card… ${i + 1} of ${page.size}"
            val (p, bytes) = io.withLock {
                withContext(Dispatchers.IO) {
                    val p = w.describe(h) ?: return@withContext null to null
                    val t = if (!thumbs) null else try {
                        w.thumbnail(h)
                    } catch (e: IOException) {
                        // A thumbnail that never comes leaves the connection out of step: stop asking.
                        AppLog.add("wifi: thumbnail #$h failed: ${e.message}; no more thumbnails")
                        thumbs = false
                        null
                    }
                    p to t
                }
            }
            if (p == null) {
                unreadable++
                continue
            }
            val bmp = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            _photos.value = _photos.value + CardPhoto(p, bmp, isImported(p))
        }
        _progress.value = if (unreadable > 0) "The camera didn't describe $unreadable of ${page.size} pictures." else null
    }

    /**
     * Copy [selected] into the phone's Pictures (Downloads for RAW and video). With [again], pictures
     * imported before are imported once more (their earlier copy replaced); otherwise they're skipped.
     */
    fun importPhotos(selected: List<FujiWifi.Photo>, again: Boolean = false) {
        val w = wifi ?: return
        if (selected.isEmpty()) return
        if (importJob?.isActive == true) {
            _progress.value = "Already importing. Wait for it to finish."
            return
        }
        AppLog.ui("wifi: import ${selected.size}")
        _progress.value = "Importing ${selected.size} ${if (selected.size == 1) "picture" else "pictures"}…"
        importJob = scope.launch {
            var done = 0
            try {
                for (p in selected) {
                    if (!again && isImported(p)) { done++; continue }
                    val bytes = io.withLock {
                        withContext(Dispatchers.IO) {
                            save(p) { out ->
                                w.download(p, { out.write(it) }) { got ->
                                    _progress.value = "Importing ${p.name} (${done + 1} of ${selected.size}) · ${(got * 100 / maxOf(1, p.size))}%"
                                }
                            }
                        }
                    }
                    markImported(p)
                    AppLog.add("wifi: imported ${p.name}, $bytes bytes, checked")
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

    /**
     * Write [p] with [write] (which returns the bytes it wrote), check it's whole (its full size;
     * a JPEG ends with FF D9), and only then keep it. An earlier copy of the same name is replaced.
     */
    private fun save(p: FujiWifi.Photo, write: (OutputStream) -> Long): Long {
        var written = 0L
        val tail = ByteArray(2)
        val counting = { out: OutputStream ->
            object : OutputStream() {
                override fun write(b: Int) { out.write(b); written++; tail[0] = tail[1]; tail[1] = b.toByte() }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    written += len
                    if (len >= 2) { tail[0] = b[off + len - 2]; tail[1] = b[off + len - 1] } else if (len == 1) { tail[0] = tail[1]; tail[1] = b[off] }
                }
            }
        }
        val jpeg = p.name.substringAfterLast('.', "").lowercase(Locale.US) in setOf("jpg", "jpeg")
        val check = {
            if (p.size > 0 && written != p.size) throw IOException("${p.name} came in incomplete ($written of ${p.size} bytes).")
            if (jpeg && !(tail[0] == 0xFF.toByte() && tail[1] == 0xD9.toByte())) throw IOException("${p.name} came in damaged (it doesn't end like a JPEG).")
        }
        saveFile(p) { out -> write(counting(out)); check() }
        return written
    }

    private fun saveFile(p: FujiWifi.Photo, write: (OutputStream) -> Unit) {
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
            // An earlier (perhaps damaged) copy made by this app goes first, so the name stays the same.
            runCatching {
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(p.name, "$dir/Fuji Remote/"),
                )
            }
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
            val file = File(dir, p.name)
            try {
                file.outputStream().use(write)
            } catch (t: Throwable) {
                file.delete()
                throw t
            }
        }
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
        // Whatever the camera pushes on its event socket is read (and logged), so it never waits on it.
        eventJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val (code, params) = w.nextEvent() ?: break
                AppLog.add("remote: event 0x%04X %s".format(code, params.joinToString(",") { "0x%X".format(it) }))
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
        // The shot ends the half press on the camera's side; letting go of it again mid-shot can
        // leave the camera stuck, so only the app's state changes here.
        _focused.value = false
        if (code == Ptp.OK) {
            lastCapture = System.currentTimeMillis()
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
        _note.value = when {
            code != Ptp.OK -> "The camera didn't change the $what: its dial is set to a value."
            what == "shutter speed" && values[FujiWifi.SHOOTING_MODE] in setOf(2L, 3L) ->
                "In mode ${modeText(values[FujiWifi.SHOOTING_MODE]!!)} the camera picks the shutter speed: set the shutter dial to a speed (or T) to choose it."
            what == "aperture" && values[FujiWifi.SHOOTING_MODE] in setOf(2L, 4L) ->
                "In mode ${modeText(values[FujiWifi.SHOOTING_MODE]!!)} the camera picks the aperture: set the aperture ring to a value to choose it."
            else -> null
        }
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
    private val values = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** The camera's current value of [property], as last reported. */
    fun currentValue(property: Int): Long? = values[property]

    /**
     * A setting's allowed values and width: from the camera, or (when it doesn't list them, as an
     * X100VI doesn't for its film simulation) the values this setting is known to take.
     */
    suspend fun describeSetting(property: Int): FujiWifi.PropDesc? {
        val w = wifi ?: return null
        val desc = io.withLock { withContext(Dispatchers.IO) { runCatching { w.propDesc(property) }.getOrNull() } }
        AppLog.add("remote: %04X offers %s".format(property, desc?.values?.joinToString(",") { "%X".format(it) } ?: "nothing"))
        if (desc != null && desc.values.isNotEmpty()) return desc
        val known = KNOWN_VALUES[property] ?: return desc
        return FujiWifi.PropDesc(values[property] ?: known.first(), null, null, known, KNOWN_WIDTHS[property] ?: 2)
    }

    /** Change one of the camera's settings (film simulation, white balance, focus mode…). */
    fun setSetting(property: Int, value: Long, width: Int, label: String) = remote("set %04X = %X".format(property, value)) { w ->
        val ok = w.setValue(property, value, width)
        AppLog.add("wifi: set %04X = %X -> %s".format(property, value, if (ok) "OK" else "refused"))
        _note.value = if (ok) "$label set." else "The camera kept its setting (a dial or menu may decide it)."
    }
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
    fun modeText(v: Long): String? = when (v) {
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
                if (usbPlugged()) throw IOException(USB_PLUGGED)
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
                    AppLog.add("wifi: join failed (${e.message}); trying again")
                    _phase.value = WifiPhase.Working("Joining ${net.ssid} again…")
                    delay(3_000)
                    try {
                        join.join(net.ssid, net.password)
                    } catch (e2: IOException) {
                        AppLog.add("wifi: join failed again (${e2.message}); one last try")
                        delay(3_000)
                        join.join(net.ssid, net.password)
                    }
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

    private fun usbPlugged(): Boolean =
        app.getSystemService(android.hardware.usb.UsbManager::class.java)?.let { com.fujiapp.remote.usb.UsbCamera.find(it) } != null

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
        listJob?.cancel()
        listJob = null
        importJob?.cancel()
        importJob = null
        pollJob?.cancel()
        pollJob = null
        liveJob?.cancel()
        liveJob = null
        val wasFocused = _focused.value
        _focused.value = false
        _note.value = null
        values.clear()
        loggedEvents = false
        val view = live
        live = null
        val w = wifi
        wifi = null
        val events = eventJob
        eventJob = null
        _frame.value = null
        // A camera left in the middle of a shot (or cut off before it heard goodbye) can hang until
        // its battery comes out: let a fresh shot finish, end the session over the network, and
        // only then let Android drop the camera's Wi-Fi.
        val wait = (lastCapture + CAPTURE_SETTLE_MS - System.currentTimeMillis()).coerceIn(0, CAPTURE_SETTLE_MS)
        lastCapture = 0L
        val network = join
        scope.launch(Dispatchers.IO) {
            if (w != null) {
                if (wait > 0) {
                    AppLog.add("wifi: letting the last shot finish (${wait / 1000.0} s) before closing")
                    delay(wait)
                }
                if (wasFocused) runCatching { w.releaseFocus() }
                runCatching { w.close() }
                AppLog.add("wifi: session closed")
            }
            events?.cancel()
            view?.close()
            withContext(Dispatchers.Main) { if (wifi == null) network.release() }
        }
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
        /** How long a picture takes to finish (write, review) before the session may end. */
        private const val CAPTURE_SETTLE_MS = 6_000L
        const val USB_PLUGGED = "The camera is connected by USB, and won't start its Wi-Fi like that. Unplug the cable, then try again."
        /** Every this many polls, read the shown properties directly too. */
        private const val FULL_EVERY = 10
        /** Values each setting is known to take, for when the camera doesn't list them. */
        private val KNOWN_VALUES: Map<Int, List<Long>> = mapOf(
            FujiWifi.FILM_SIMULATION to com.fujiapp.remote.recipe.FilmSim.entries.map { it.code.toLong() },
            FujiWifi.WHITE_BALANCE to listOf(0x0002L, 0x0004L, 0x8006L, 0x8001L, 0x8002L, 0x8003L, 0x0006L, 0x0008L),
            FujiWifi.IMAGE_QUALITY to listOf(2L, 3L, 1L, 4L, 5L),
            FujiWifi.SELF_TIMER to listOf(0L, 2_000L, 10_000L),
            FujiWifi.FOCUS_MODE to listOf(0x8001L, 0x8002L, 1L),
        )
        private val KNOWN_WIDTHS = mapOf(FujiWifi.SELF_TIMER to 4)

        private val SHOWN = listOf(
            FujiWifi.SHUTTER_SPEED, FujiWifi.EXPOSURE_TIME, FujiWifi.F_NUMBER, FujiWifi.ISO,
            FujiWifi.EXPOSURE_BIAS, FujiWifi.SHOOTING_MODE, FujiWifi.BATTERY_LEVELS, FujiWifi.BATTERY,
            FujiWifi.FILM_SIMULATION, FujiWifi.WHITE_BALANCE, FujiWifi.FOCUS_MODE, FujiWifi.IMAGE_QUALITY, FujiWifi.SELF_TIMER,
        )
    }
}
