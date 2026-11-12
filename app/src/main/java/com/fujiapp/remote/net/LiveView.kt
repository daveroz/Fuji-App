package com.fujiapp.remote.net

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Reads the live-view JPEG stream from the camera (TCP 55742).
 *
 * Frame layout (verified against petabyt/libfuji ptp_fuji_read_liveview_frame and
 * hkr/fuji-cam-wifi-tool): each frame is [u32 totalLength][14 more header bytes][JPEG...].
 * The JPEG image begins at byte offset 18; `totalLength` (offset 0, little-endian) counts the
 * whole frame including the 18-byte header. Frames stream continuously with no per-frame request.
 */
class LiveViewStream(
    private val host: String = Ptp.CAMERA_IP,
    private val port: Int = Ptp.PORT_LIVEVIEW,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 15000,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null

    fun open() {
        val s = socketFactory.createSocket()
        s.tcpNoDelay = true
        s.soTimeout = readTimeoutMs
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
        input = s.getInputStream()
    }

    fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null; input = null
    }

    val isOpen: Boolean get() = socket?.isClosed == false

    /**
     * Read one live-view JPEG frame. Returns the JPEG bytes, or null if the stream produced an
     * empty/keep-alive frame (caller should just loop again).
     */
    fun readFrame(): ByteArray? {
        val header = readExactly(HEADER_SIZE)
        val total = (header[0].toInt() and 0xFF) or
            ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 24)
        val imageLen = total - HEADER_SIZE
        if (imageLen <= 0) return null
        if (imageLen > MAX_FRAME) throw FujiProtocolException("live-view frame too large: $imageLen")
        return readExactly(imageLen)
    }

    private fun readExactly(n: Int): ByteArray {
        val inp = input ?: throw IOException("live view not open")
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = inp.read(buf, off, n - off)
            if (read < 0) throw IOException("live-view stream closed")
            off += read
        }
        return buf
    }

    companion object {
        const val HEADER_SIZE = 18
        private const val MAX_FRAME = 32 * 1024 * 1024
    }
}
