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

    /** Init, OpenSession. A camera just woken by Bluetooth sometimes ignores the first init: retry on a new socket. */
    private fun handshake(name: String) {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                open()
                write(initPacket(name))
                val reply = readPacket()
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
        lastError?.let { throw IOException("The camera didn't answer on Wi-Fi (${it.message}).", it) }
        Thread.sleep(SETTLE_MS) // OpenSession inside this window gets silence
        val r = transact(Ptp.OPEN_SESSION, listOf(1))
        if (!r.ok && r.code != Ptp.SESSION_ALREADY_OPEN) throw PtpException(r.code, "Open session")
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
                Ptp.TYPE_RESPONSE -> return Reply(c.code, data)
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
        if (d.size < 4) return emptyList()
        val n = u32(d, 0)
        if (n <= 0 || d.size < 4 + n * 4) return emptyList()
        return (0 until n).map { u32(d, 4 + it * 4) }
    }

    /** Name, size and date of a photo, from three object properties (fast on an X100VI). */
    fun describe(handle: Int): Photo? {
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
    fun thumbnail(handle: Int): ByteArray? = transact(GET_THUMB, listOf(handle)).takeIf { it.ok && it.data.isNotEmpty() }?.data

    /** Copy a photo in 1 MB windows from 512-byte-aligned offsets, as fuji-bridge does. */
    fun download(photo: Photo, sink: (ByteArray) -> Unit, progress: (Long) -> Unit) {
        var offset = 0L
        while (offset < photo.size) {
            val ask = minOf(WINDOW.toLong(), photo.size - offset).toInt()
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

    /** Shutter speed, aperture or exposure compensation one step up or down. */
    fun step(op: Int, up: Boolean): Int = transact(op, listOf(if (up) 1 else 0)).code

    /** A property's allowed values, from GetDevicePropDesc (enumeration form), or empty. */
    fun allowedValues(property: Int): List<Long> {
        val r = transact(GET_DEVICE_PROP_DESC, listOf(property))
        if (!r.ok || r.data.size < 6) return emptyList()
        val d = r.data
        val type = (d[2].toInt() and 0xFF) or ((d[3].toInt() and 0xFF) shl 8)
        val width = when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; 7, 8 -> 8; else -> return emptyList() }
        var at = 5 + 2 * width // code, type, get/set, default, current
        if (at >= d.size) return emptyList()
        if (d[at].toInt() != 2) return emptyList() // not an enumeration
        at += 1
        if (at + 2 > d.size) return emptyList()
        val n = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)
        at += 2
        return (0 until n).mapNotNull { i ->
            val o = at + i * width
            if (o + width > d.size) null else (0 until width).fold(0L) { acc, k -> acc or ((d[o + k].toLong() and 0xFF) shl (8 * k)) }
        }
    }

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
        runCatching { if (openCaptureTid != 0) transact(TERMINATE_OPEN_CAPTURE, listOf(openCaptureTid)) }
        openCaptureTid = 0
        runCatching { eventSocket?.close() }
        eventSocket = null
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    /** A photo on the camera's card. */
    data class Photo(val handle: Int, val name: String, val size: Long, val date: String?)

    companion object {
        const val HOST = "192.168.0.1"
        const val PORT = 55740
        const val EVENT_PORT = 55741
        const val VIDEO_PORT = 55742
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000
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
        const val EXPOSURE_BIAS = 0x5010
        const val FILM_SIMULATION = 0xD001

        const val MODE_IMAGE_VIEW_XAPP = 20
        const val MODE_LIVE_VIEW_XAPP = 22
        const val STATE_REMOTE_ACCESS = 6

        /** Connect over [factory] (the camera's Wi-Fi network) and open a session. */
        fun connect(factory: SocketFactory, name: String, log: (String) -> Unit): FujiWifi =
            FujiWifi(factory, log).also {
                try {
                    it.handshake(name)
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

        fun u32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    }
}
