package com.fujiapp.remote.wifi

import com.fujiapp.remote.usb.Ptp
import com.fujiapp.remote.usb.PtpException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory

/**
 * Fujifilm's PTP over the camera's own Wi-Fi (TCP 192.168.0.1:55740): the same containers as over
 * USB, after an 82-byte init. Then OpenSession, an OK on the camera's screen, and either the photo
 * gallery (import) or remote shooting with a live view on TCP 55742.
 *
 * The sequences follow fuji-bridge (`Session.swift`, tested on an X100VI) for import and libfuji
 * (`lib/fuji.c`) for remote mode; both MIT. All calls block: run them off the main thread.
 */
class FujiWifi private constructor(
    private val factory: SocketFactory,
    private val log: (String) -> Unit,
) : Closeable {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var transaction = 1
    private var openCaptureTid = 0
    private var sessionOpen = false

    class Reply(val code: Int, val data: ByteArray) {
        val ok: Boolean get() = code == Ptp.OK
    }

    private fun open() {
        close()
        val s = factory.createSocket()
        s.tcpNoDelay = true
        s.soTimeout = READ_TIMEOUT_MS
        s.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    /**
     * Init, OpenSession. After a Bluetooth wake the camera can hold the init's answer until someone
     * presses OK on its screen (libfuji: "Waiting on the camera… Make sure you pressed OK"), and it
     * drops its Wi-Fi about 45 s after starting it. So the first init waits up to 40 s while the user
     * is asked to press OK; a silent socket then gets one fresh try, as XApp does.
     */
    private fun handshake(name: String, onWaiting: () -> Unit) {
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            try {
                open()
                write(initPacket(name))
                log("wifi: init sent (attempt $attempt), waiting for the camera")
                // A camera that knows this phone answers at once; only ask for OK when it's silent.
                val limit = if (attempt == 1) FIRST_INIT_TIMEOUT_MS else RETRY_INIT_TIMEOUT_MS
                val reply = try {
                    socket?.soTimeout = OK_PROMPT_AFTER_MS
                    readPacket()
                } catch (_: java.net.SocketTimeoutException) {
                    log("wifi: the camera is waiting (press OK on it)")
                    onWaiting()
                    socket?.soTimeout = limit - OK_PROMPT_AFTER_MS
                    readPacket()
                }
                socket?.soTimeout = READ_TIMEOUT_MS
                val type = if (reply.size >= 8) u32(reply, 4) else 0
                if (type == INIT_FAIL) throw IOException("The camera refused the connection")
                log("wifi: init ack (type $type)")
                lastError = null
                break
            } catch (t: IOException) {
                lastError = t
                log("wifi: init attempt $attempt failed: ${t.message}")
                Thread.sleep(600)
            }
        }
        lastError?.let { throw IOException("The camera didn't answer on Wi-Fi. Press OK on the camera when it asks, then try again (${it.message}).", it) }
        Thread.sleep(SETTLE_MS) // OpenSession inside this window gets silence
        val r = transact(Ptp.OPEN_SESSION, listOf(1))
        if (!r.ok && r.code != Ptp.SESSION_ALREADY_OPEN) throw PtpException(r.code, "Open session")
        sessionOpen = true
        log("wifi: session open")
    }

    /** One transaction: command (and data) in one write, then data in and the response. */
    @Synchronized
    fun transact(code: Int, params: List<Int> = emptyList(), dataOut: ByteArray? = null): Reply {
        val tid = transaction++
        val cmd = Ptp.command(code, tid, params)
        write(if (dataOut != null) cmd + Ptp.data(code, tid, dataOut) else cmd)
        var data = ByteArray(0)
        while (true) {
            val c = Ptp.parse(readPacket())
            when (c.type) {
                Ptp.TYPE_DATA -> data = c.payload
                Ptp.TYPE_RESPONSE -> {
                    if (c.code != Ptp.OK) log("wifi: 0x%04X %s -> %s".format(code, params.joinToString(",") { "0x%X".format(it) }, Ptp.responseName(c.code)))
                    return Reply(c.code, data)
                }
                else -> log("wifi: ignored container type ${c.type}")
            }
        }
    }

    fun get(property: Int): ByteArray? = transact(Ptp.GET_DEVICE_PROP_VALUE, listOf(property)).takeIf { it.ok }?.data

    fun set16(property: Int, value: Int): Boolean = transact(Ptp.SET_DEVICE_PROP_VALUE, listOf(property), Ptp.u16(value)).ok

    fun set32(property: Int, value: Int): Boolean = transact(Ptp.SET_DEVICE_PROP_VALUE, listOf(property), le32(value)).ok

    fun setRaw(property: Int, value: ByteArray): Boolean = transact(Ptp.SET_DEVICE_PROP_VALUE, listOf(property), value).ok

    /** The camera's event list (0xD212): property code → value, for what changed. */
    fun events(): Map<Int, Long> {
        val d = get(EVENTS) ?: return emptyMap()
        if (d.size < 2) return emptyMap()
        val n = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
        val out = LinkedHashMap<Int, Long>()
        var at = 2
        repeat(n) {
            if (at + 6 > d.size) return out
            val code = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)
            out[code] = u32(d, at + 2).toLong() and 0xFFFFFFFFL
            at += 6
        }
        return out
    }

    /** Wait while the camera asks "accept the connection?" (camera state 0). */
    fun waitForAccess(timeoutMs: Long, onWaiting: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var told = false
        while (true) {
            val state = runCatching { events()[CAMERA_STATE] }.getOrNull()
            if (state != null && state != 0L) {
                log("wifi: camera state $state")
                return
            }
            if (!told) { onWaiting(); told = true }
            if (System.currentTimeMillis() > deadline) throw IOException("Nobody pressed OK on the camera.")
            Thread.sleep(400)
        }
    }

    /** Version properties, read as XApp does; returns the remote version (DF24), or null. */
    private fun versions(): Int? {
        get(OBJECT_VERSION); get(REMOTE_OBJECT_VERSION); get(IMAGE_GET_VERSION)
        return get(REMOTE_VERSION)?.takeIf { it.size >= 4 }?.let { u32(it, 0) }
    }

    // ---- photos ----

    /** Photo browsing: client state 20 and XApp's gallery setup. Returns the photos' handles, oldest first. */
    fun startGallery(): List<Int> {
        versions()
        set16(CLIENT_STATE, MODE_IMAGE_VIEW_XAPP)
        get(REMOTE_PHOTO_VIEW)?.let { setRaw(REMOTE_PHOTO_VIEW, it) }
        set16(COMPRESS_SMALL, 0)
        set16(CORRECT_SIZE, 0)
        runCatching { transact(GET_EXTENSION_INFO, listOf(0x10000001)) }
        runCatching { transact(GET_EXTENSION_THUMB, listOf(0x10000001)) }
        runCatching { transact(GET_FOLDERS) }
        get(UNKNOWN_D22B)
        runCatching { transact(GET_DATES, listOf(0, 30000)) }
        get(IMPORT_COUNT)
        val d = get(IMPORT_HANDLES) ?: throw IOException("The camera didn't list its pictures.")
        val count = runCatching { events()[OBJECT_COUNT] }.getOrNull()
        var list = handles(d)
        log("wifi: picture list ${d.size} bytes, ${list.size} handles, object count $count")
        if (list.isEmpty() && count != null && count in 1..20_000) list = (1..count.toInt()).toList()
        // XApp turns on "send the originals" (D226 = 2) when an import starts; fuji-bridge reads the
        // object properties only after that.
        set16(COMPRESS_SMALL, ORIGINALS)
        // The X100VI lists newest first; handles count up in shooting order.
        return list.distinct().sorted()
    }

    /**
     * Name, size and date of a photo: three object properties (fast), or the camera's ObjectInfo
     * when it doesn't answer those (fuji-bridge does the same; ObjectInfo is honest about the size
     * once D227 is 1, as libfuji sets it).
     */
    fun describe(handle: Int): Photo? {
        if (propertiesWork) {
            byProperties(handle)?.let { return it }
            propertiesWork = false
            log("wifi: no name and size by object property; using ObjectInfo")
        }
        if (!honestSizes) {
            honestSizes = true
            set16(CORRECT_SIZE, 1)
        }
        val r = transact(Ptp.GET_OBJECT_INFO, listOf(handle))
        if (!r.ok) return null
        lastInfo = handle
        if (!loggedInfo) {
            loggedInfo = true
            log("wifi: ObjectInfo #$handle ${r.data.size} bytes: ${r.data.take(64).joinToString(" ") { "%02x".format(it) }}")
        }
        return objectInfo(handle, r.data) ?: run { log("wifi: ObjectInfo #$handle unreadable"); null }
    }

    private var propertiesWork = true
    private var honestSizes = false
    private var loggedInfo = false
    /** The handle whose ObjectInfo was asked last. */
    private var lastInfo = -1

    /**
     * Newer bodies only answer GetThumb (and serve a file) for the handle whose ObjectInfo was
     * asked just before: otherwise GetThumb blocks for good (libfuji: "get_thumb blocks forever
     * unless get_object_info is called on newer cameras"; seen on an X100VI).
     */
    private fun ensureInfo(handle: Int) {
        if (lastInfo == handle) return
        val r = transact(Ptp.GET_OBJECT_INFO, listOf(handle))
        if (r.ok) lastInfo = handle
    }

    private fun byProperties(handle: Int): Photo? {
        val sizeData = transact(GET_OBJECT_PROP_VALUE, listOf(handle, OBJECT_SIZE)).takeIf { it.ok }?.data ?: return null
        val size = when {
            sizeData.size >= 8 -> (u32(sizeData, 0).toLong() and 0xFFFFFFFFL) or (u32(sizeData, 4).toLong() shl 32)
            sizeData.size >= 4 -> u32(sizeData, 0).toLong() and 0xFFFFFFFFL
            else -> return null
        }
        val name = transact(GET_OBJECT_PROP_VALUE, listOf(handle, OBJECT_FILE_NAME)).takeIf { it.ok }?.data
            ?.let { Ptp.readString(it).substringAfterLast('/').substringAfterLast('\\') }?.takeIf { it.isNotBlank() } ?: return null
        val date = transact(GET_OBJECT_PROP_VALUE, listOf(handle, DATE_CREATED)).takeIf { it.ok }?.data?.let { Ptp.readString(it) }
        return Photo(handle, name, size, date)
    }

    /** The camera's own 160×120 JPEG thumbnail. */
    fun thumbnail(handle: Int): ByteArray? {
        ensureInfo(handle)
        return transact(GET_THUMB, listOf(handle)).takeIf { it.ok && it.data.size >= 100 }?.data
    }

    /** Copy a photo in 1 MB windows from 512-byte-aligned offsets, as fuji-bridge does. */
    fun download(photo: Photo, sink: (ByteArray) -> Unit, progress: (Long) -> Unit) {
        ensureInfo(photo.handle)
        var offset = 0L
        val known = photo.size > 0
        while (!known || offset < photo.size) {
            val ask = if (known) minOf(WINDOW.toLong(), photo.size - offset).toInt() else WINDOW
            val r = transact(GET_PARTIAL_OBJECT, listOf(photo.handle, offset.toInt(), ask))
            if (!r.ok) throw PtpException(r.code, "Reading ${photo.name}")
            if (r.data.isEmpty()) break
            sink(r.data)
            offset += r.data.size
            progress(offset)
            if (r.data.size < ask) break // the camera's end of file
        }
    }

    // ---- remote ----

    /** Remote shooting: XApp's live-view setup, then open the event and video sockets. */
    fun startRemote(): LiveView {
        val remote = versions()
        set16(CLIENT_STATE, MODE_IMAGE_VIEW_XAPP)
        events()
        set16(CAMERA_STATE, STATE_REMOTE_ACCESS)
        set16(CLIENT_STATE, MODE_LIVE_VIEW_XAPP)
        get(UNKNOWN_DF2A)?.let { setRaw(UNKNOWN_DF2A, it) }
        log("wifi: remote version ${remote?.let { "0x%08X".format(it) }}")
        openCaptureTid = transaction
        val r = transact(INITIATE_OPEN_CAPTURE, listOf(0, 0))
        if (!r.ok) throw PtpException(r.code, "Remote mode")
        events()
        Thread.sleep(SETTLE_MS)
        eventSocket = runCatching { factory.createSocket().apply { connect(InetSocketAddress(HOST, EVENT_PORT), CONNECT_TIMEOUT_MS) } }.getOrNull()
        val video = factory.createSocket()
        video.tcpNoDelay = true
        video.soTimeout = READ_TIMEOUT_MS
        video.connect(InetSocketAddress(HOST, VIDEO_PORT), CONNECT_TIMEOUT_MS)
        return LiveView(video)
    }

    private var eventSocket: Socket? = null

    /** Take a picture: the standard capture command. */
    fun capture(): Int = transact(INITIATE_CAPTURE, listOf(0, 0)).code

    /**
     * Half press (focus and lock), as XApp's touch AF: LockS1Lock with a focus point packed as
     * aspect width, aspect height, column, row (one byte each; columns and rows count from 1 on the
     * camera's grid). Without a point, the camera's current one (property 0xD17C) is used.
     */
    fun focus(at: Pair<Float, Float>? = null): Int {
        val current = get(S1_LOCK_POINT)?.takeIf { it.size >= 4 }?.let { u32(it, 0) }
        var point = current ?: 0
        if (at != null && current != null) {
            val (cols, rows) = focusGrid ?: grid(current).also { focusGrid = it }
            if (cols > 0 && rows > 0) {
                val col = (at.first * cols).toInt().coerceIn(0, cols - 1) + 1
                val row = (at.second * rows).toInt().coerceIn(0, rows - 1) + 1
                point = (current and 0xFFFF0000.toInt()) or (col shl 8) or row
            }
        }
        log("wifi: focus at 0x%08X".format(point))
        return transact(LOCK_S1, listOf(point)).code
    }

    private var focusGrid: Pair<Int, Int>? = null

    /**
     * The camera's focus grid, columns × rows: the low two bytes of 0xD17C's maximum, as XApp reads
     * it. If the description doesn't say, assume the current point is the middle one (the X100VI's
     * 13 × 9 grid has its centre at 7, 5).
     */
    private fun grid(current: Int): Pair<Int, Int> {
        val desc = transact(GET_DEVICE_PROP_DESC, listOf(S1_LOCK_POINT))
        log("wifi: focus point description ${desc.data.size} bytes: ${desc.data.take(48).joinToString(" ") { "%02x".format(it) }}")
        val d = if (desc.ok) parseDesc(desc.data) else null
        val max = (d?.max ?: d?.values?.maxOrNull())?.toInt()
        val fromDesc = max?.let { ((it shr 8) and 0xFF) to (it and 0xFF) }?.takeIf { it.first in 2..60 && it.second in 2..60 }
        val col = (current shr 8) and 0xFF
        val row = current and 0xFF
        val guessed = (2 * col - 1 to 2 * row - 1).takeIf { col > 1 && row > 1 }
        val g = fromDesc ?: guessed ?: (0 to 0)
        log("wifi: focus grid ${g.first} × ${g.second}${if (fromDesc == null) " (from the current point)" else ""}")
        return g
    }

    /** Set a property to [value], written at [width] bytes (from its description). */
    fun setValue(property: Int, value: Long, width: Int): Boolean = when (width) {
        1 -> setRaw(property, byteArrayOf(value.toByte()))
        2 -> set16(property, value.toInt())
        8 -> setRaw(property, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        else -> set32(property, value.toInt())
    }

    /** Let go of the half press. */
    fun releaseFocus(): Int = transact(UNLOCK_S1).code

    /** A property's description: current value and its range or allowed values. */
    fun propDesc(property: Int): PropDesc? {
        val r = transact(GET_DEVICE_PROP_DESC, listOf(property))
        return if (r.ok) parseDesc(r.data) else null
    }

    /** Shutter speed, aperture or exposure compensation one step up or down. */
    fun step(op: Int, up: Boolean): Int = transact(op, listOf(if (up) 1 else 0)).code

    /** A property's allowed values, from GetDevicePropDesc (enumeration form), or empty. */
    fun allowedValues(property: Int): List<Long> = propDesc(property)?.values.orEmpty()

    /** The video stream: each frame is an 18-byte header (u32 total length first) and a JPEG. */
    class LiveView(private val socket: Socket) : Closeable {
        private val input = socket.getInputStream()

        fun frame(): ByteArray? {
            val header = readExactly(18)
            val total = u32(header, 0)
            val length = total - 18
            if (length <= 0 || length > 16 * 1024 * 1024) return null
            return readExactly(length)
        }

        private fun readExactly(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw IOException("The live view stopped")
                off += r
            }
            return buf
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    // ---- framing ----

    private fun write(bytes: ByteArray) {
        val o = output ?: throw IOException("Not connected")
        o.write(bytes)
        o.flush()
    }

    private fun readPacket(): ByteArray {
        val inp = input ?: throw IOException("Not connected")
        val head = readExactly(inp, 4)
        val len = u32(head, 0)
        if (len < 8 || len > MAX_PACKET) throw IOException("Bad packet length $len")
        val out = ByteArrayOutputStream(len)
        out.write(head)
        out.write(readExactly(inp, len - 4))
        return out.toByteArray()
    }

    private fun readExactly(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw IOException("The camera closed the connection")
            off += r
        }
        return buf
    }

    override fun close() {
        runCatching { socket?.soTimeout = CLOSE_TIMEOUT_MS }
        runCatching { if (openCaptureTid != 0) transact(TERMINATE_OPEN_CAPTURE, listOf(openCaptureTid)) }
        openCaptureTid = 0
        // Say goodbye: a camera left with an open session takes longer to free its Wi-Fi.
        if (sessionOpen) runCatching { transact(Ptp.CLOSE_SESSION) }
        sessionOpen = false
        runCatching { eventSocket?.close() }
        eventSocket = null
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    /** A photo on the camera's card. */
    data class Photo(val handle: Int, val name: String, val size: Long, val date: String?)

    /** What GetDevicePropDesc says: the current value and a range (min, max) or a list. */
    data class PropDesc(val current: Long, val min: Long?, val max: Long?, val values: List<Long>, val width: Int = 4)

    companion object {
        const val HOST = "192.168.0.1"
        const val PORT = 55740
        const val EVENT_PORT = 55741
        const val VIDEO_PORT = 55742
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val CLOSE_TIMEOUT_MS = 2_000
        private const val FIRST_INIT_TIMEOUT_MS = 40_000
        private const val RETRY_INIT_TIMEOUT_MS = 12_000
        /** Silence this long after the init means the camera is asking for OK on its screen. */
        private const val OK_PROMPT_AFTER_MS = 1_500
        private const val SETTLE_MS = 60L
        private const val MAX_PACKET = 64 * 1024 * 1024
        private const val WINDOW = 1024 * 1024
        private const val INIT_FAIL = 5
        private const val VERSION = 0x8f53e4f2.toInt()

        // operations
        const val GET_THUMB = 0x100A
        const val INITIATE_CAPTURE = 0x100E
        const val GET_DEVICE_PROP_DESC = 0x1014
        const val TERMINATE_OPEN_CAPTURE = 0x1018
        const val GET_PARTIAL_OBJECT = 0x101B
        const val INITIATE_OPEN_CAPTURE = 0x101C
        const val GET_OBJECT_PROP_VALUE = 0x9803
        const val GET_FOLDERS = 0x9050
        const val GET_DATES = 0x9053
        const val GET_EXTENSION_INFO = 0x9054
        const val GET_EXTENSION_THUMB = 0x9055
        const val STEP_SHUTTER_SPEED = 0x902C
        const val STEP_APERTURE = 0x902D
        const val STEP_EXPOSURE_BIAS = 0x902E
        const val LOCK_S1 = 0x9026
        const val UNLOCK_S1 = 0x9027

        // properties
        const val EVENTS = 0xD212
        const val CAMERA_STATE = 0xDF00
        const val CLIENT_STATE = 0xDF01
        const val IMAGE_GET_VERSION = 0xDF21
        const val OBJECT_VERSION = 0xDF22
        const val REMOTE_VERSION = 0xDF24
        const val REMOTE_OBJECT_VERSION = 0xDF25
        const val REMOTE_PHOTO_VIEW = 0xDF28
        const val UNKNOWN_DF2A = 0xDF2A
        const val COMPRESS_SMALL = 0xD226
        const val CORRECT_SIZE = 0xD227
        const val UNKNOWN_D22B = 0xD22B
        const val IMPORT_COUNT = 0xD620
        const val IMPORT_HANDLES = 0xD621
        const val OBJECT_SIZE = 0xDC04
        const val OBJECT_FILE_NAME = 0xDC07
        const val DATE_CREATED = 0xDC08
        const val BATTERY = 0x5001
        const val F_NUMBER = 0x5007
        const val EXPOSURE_TIME = 0x500D
        const val ISO = 0xD02A
        /** Fujifilm's shutter speed, the one XApp's remote shows (PROPERTY_SHUTTER_SPEED_EX). */
        const val SHUTTER_SPEED = 0xD240
        const val BATTERY_LEVELS = 0xD242
        const val SHOOTING_MODE = 0x500E
        const val AF_STATUS = 0xD209
        const val S1_LOCK_POINT = 0xD17C
        const val OBJECT_COUNT = 0xD222
        const val ORIGINALS = 2
        const val EXPOSURE_BIAS = 0x5010
        const val FILM_SIMULATION = 0xD001
        const val WHITE_BALANCE = 0x5005
        const val FOCUS_MODE = 0x500A
        const val IMAGE_QUALITY = 0xD018
        const val SELF_TIMER = 0x5012

        const val MODE_IMAGE_VIEW_XAPP = 20
        const val MODE_LIVE_VIEW_XAPP = 22
        const val STATE_REMOTE_ACCESS = 6

        /** Connect over [factory] (the camera's Wi-Fi network) and open a session; [onWaiting] while the camera may want OK. */
        fun connect(factory: SocketFactory, name: String, log: (String) -> Unit, onWaiting: () -> Unit = {}): FujiWifi =
            FujiWifi(factory, log).also {
                try {
                    it.handshake(name, onWaiting)
                } catch (t: Throwable) {
                    it.close()
                    throw t
                }
            }

        /** 82 bytes: length, type 1, version, a 16-byte client id (XApp's), then the name in UTF-16. */
        fun initPacket(name: String): ByteArray {
            val b = ByteBuffer.allocate(82).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(82).putInt(1).putInt(VERSION)
            b.putInt(0x5d48a5ad).putInt(0x0b7fb287).putInt(0xd0ded5d3.toInt()).putInt(0)
            for (ch in name.take(26)) b.putShort(ch.code.toShort())
            return b.array()
        }

        /** A PTP DevicePropDesc: code, type, get/set, default, current, then a range or an enumeration. */
        fun parseDesc(d: ByteArray): PropDesc? {
            if (d.size < 6) return null
            val type = (d[2].toInt() and 0xFF) or ((d[3].toInt() and 0xFF) shl 8)
            val width = when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; 7, 8 -> 8; else -> return null }
            fun num(at: Int): Long? = if (at + width > d.size) null else (0 until width).fold(0L) { acc, k -> acc or ((d[at + k].toLong() and 0xFF) shl (8 * k)) }
            val current = num(5 + width) ?: return null
            var at = 5 + 2 * width
            if (at >= d.size) return PropDesc(current, null, null, emptyList(), width)
            val form = d[at].toInt()
            at += 1
            return when (form) {
                1 -> PropDesc(current, num(at), num(at + width), emptyList(), width)
                2 -> {
                    if (at + 2 > d.size) return PropDesc(current, null, null, emptyList(), width)
                    val n = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)
                    PropDesc(current, null, null, (0 until n).mapNotNull { num(at + 2 + it * width) }, width)
                }
                else -> PropDesc(current, null, null, emptyList(), width)
            }
        }

        /** Fujifilm's device-prop array: u32 count, then that many u32 handles. */
        fun handles(d: ByteArray): List<Int> {
            if (d.size < 4) return emptyList()
            val n = u32(d, 0)
            if (n <= 0 || n > 20_000 || d.size < 4 + n * 4) return emptyList()
            return (0 until n).map { u32(d, 4 + it * 4) }
        }

        /**
         * Fujifilm's ObjectInfo over Wi-Fi (packed, as libfuji's `PtpFujiObjectInfo`): format u16 at 4
         * (0x3001 a folder), the size an unaligned u32 at 13, the file name a PTP string at 52 and the
         * capture date the string after it.
         */
        fun objectInfo(handle: Int, d: ByteArray): Photo? {
            if (d.size <= NAME_AT) return null
            if ((d[4].toInt() and 0xFF) or ((d[5].toInt() and 0xFF) shl 8) == 0x3001) return null
            val name = Ptp.readString(d, NAME_AT).substringAfterLast('/').substringAfterLast('\\').trim()
            if (name.isEmpty()) return null
            val size = u32(d, 13).toLong() and 0xFFFFFFFFL
            val dateAt = NAME_AT + 1 + (d[NAME_AT].toInt() and 0xFF) * 2
            val date = Ptp.readString(d, dateAt).takeIf { it.length >= 15 && it[0].isDigit() }
            return Photo(handle, name, size, date)
        }

        private const val NAME_AT = 52

        /**
         * Fujifilm's shutter speed (0xD240), as XApp shows it: -1 none; top bits 10 = a fraction,
         * 1/(value / 1000); otherwise seconds × 1000.
         */
        fun shutterText(raw: Long): String? {
            val v = raw.toInt()
            if (v == -1 || raw == 0L) return null
            return if (v and 0xC0000000.toInt() == Int.MIN_VALUE) {
                val tenths = (v and Int.MAX_VALUE) / 100
                if (tenths % 10 == 0) "1/${tenths / 10}" else "1/${tenths / 10}.${tenths % 10}"
            } else {
                val tenths = v / 100
                if (tenths % 10 == 0) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
            }
        }

        fun u32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    }
}
