package com.fujiapp.remote.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Result of a PTP operation: the response code plus any data returned by a data-in phase. */
data class OpResult(
    val responseCode: Int,
    val data: ByteArray,
    val responseParams: ByteArray,
) {
    val ok: Boolean get() = responseCode == Ptp.Resp.OK || responseCode == Ptp.Resp.SESSION_ALREADY_OPEN

    /**
     * The data-in payload. Fuji bodies vary: some return a get's value in a separate data packet
     * ([data]), some inline it in the response packet's trailing field ([responseParams]). Prefer
     * [data] but fall back so callers read the value either way.
     */
    fun payload(): ByteArray = if (data.isNotEmpty()) data else responseParams

    fun reader(): PtpReader = PtpReader(payload())
}

/**
 * Low-level transport for the Fuji command/data channel (TCP 55740).
 *
 * Framing (verified against petabyt/libfuji and malc0mn/ptp-ip):
 *   request/response/data packet = [u32 length][u16 dataPhase][u16 code][u32 transactionId][payload]
 *   where `length` counts its own 4 bytes, and the payload starts at offset 12.
 *
 * The init handshake packet (82 bytes) is a different, one-off shape and is sent by [connectInit].
 *
 * Not thread-safe: all calls happen from a single IO coroutine in [FujiClient].
 */
class FujiTransport(
    private val host: String = Ptp.CAMERA_IP,
    private val port: Int = Ptp.PORT_CMD,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 15000,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** PTP transaction id. Fuji expects the first OpenSession at transaction id 1. */
    var transactionId: Int = 1
        private set

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun open() {
        val s = socketFactory.createSocket()
        s.tcpNoDelay = true
        s.soTimeout = readTimeoutMs
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null; input = null; output = null
    }

    /**
     * Send the Fuji PTP/IP init command request and read the ack.
     * @return the camera's advertised name, or throws [FujiProtocolException] on INIT_FAIL.
     */
    fun connectInit(deviceName: String, guid: ByteArray): String {
        // 82-byte packet: length, type, version, 16-byte guid, UTF-16LE name (fills to 82).
        val body = PtpWriter()
            .u32(0)                                   // length placeholder, patched below
            .u32(Ptp.PacketType.INIT_COMMAND_REQUEST)
            .u32(Ptp.FUJI_PROTOCOL_VERSION)
            .bytes(guid)
            .utf16(deviceName)
            .toByteArray()
        // libfuji uses a fixed 82-byte packet; pad/truncate the name region to match.
        val packet = fixedInitPacket(body)
        writeRaw(packet)

        val reply = readPacketRaw() // length prefix already stripped; body starts at `type`
        val r = PtpReader(reply)
        val type = r.u32().toInt()
        if (type != Ptp.PacketType.INIT_COMMAND_ACK) {
            throw FujiProtocolException("Camera refused connection (init type 0x%04X)".format(type))
        }
        // Remainder is the camera GUID + UTF-16LE camera name; parse best-effort.
        return parseCameraName(reply)
    }

    private fun fixedInitPacket(body: ByteArray): ByteArray {
        val target = 82
        val packet = if (body.size >= target) body.copyOf(target) else body.copyOf(target)
        // patch length field (whole packet, little-endian)
        packet[0] = (target and 0xFF).toByte()
        packet[1] = ((target ushr 8) and 0xFF).toByte()
        packet[2] = ((target ushr 16) and 0xFF).toByte()
        packet[3] = ((target ushr 24) and 0xFF).toByte()
        return packet
    }

    private fun parseCameraName(reply: ByteArray): String {
        // reply = [type(4)][guid(16)][name UTF-16LE...]; be defensive about length.
        val nameStart = 4 + 16
        if (reply.size <= nameStart) return ""
        val sb = StringBuilder()
        var i = nameStart
        while (i + 1 < reply.size) {
            val ch = (reply[i].toInt() and 0xFF) or ((reply[i + 1].toInt() and 0xFF) shl 8)
            if (ch == 0) break
            sb.append(ch.toChar())
            i += 2
        }
        return sb.toString()
    }

    /**
     * Run one PTP operation. For data-out ops (e.g. SetDevicePropValue) pass [dataOut] — the value
     * is sent in a second packet with DataPhase=DATA_OUT. Returns the collected data-in bytes and
     * final response code.
     */
    fun operation(
        opcode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
    ): OpResult {
        val tid = transactionId
        // Command packet always leads with DataPhase=NO_DATA_OR_DATA_IN; a data-out value follows
        // in a second packet with DataPhase=DATA_OUT (matches libfuji / ptp-ip SetDevicePropValue).
        writeRaw(buildOpPacket(Ptp.DataPhase.NO_DATA_OR_DATA_IN, opcode, tid, params, null))
        if (dataOut != null) {
            // second packet carries the host->camera data at the payload offset
            writeRaw(buildOpPacket(Ptp.DataPhase.DATA_OUT, opcode, tid, IntArray(0), dataOut))
        }

        val dataIn = ArrayList<Byte>()
        var responseCode = Ptp.Resp.OK
        var responseParams = ByteArray(0)
        // Read packets until we see the final response packet.
        while (true) {
            val pkt = readPacketRaw() // length stripped; body = [dataPhase(2)][code(2)][tid(4)][payload]
            if (pkt.size < 8) break
            val code = (pkt[2].toInt() and 0xFF) or ((pkt[3].toInt() and 0xFF) shl 8) // code OR data marker
            val payload = if (pkt.size > 8) pkt.copyOfRange(8, pkt.size) else ByteArray(0)
            if (Ptp.Resp.isResponseCode(code)) {
                responseCode = code
                responseParams = payload
                break
            } else {
                payload.forEach { dataIn.add(it) }
            }
        }
        transactionId++
        return OpResult(responseCode, ByteArray(dataIn.size) { dataIn[it] }, responseParams)
    }

    /**
     * A Fuji operation packet. `reply` packets omit the length prefix once read, so when PARSING we
     * treat offset 0 as the field after length. When BUILDING we include the 4-byte length.
     * Layout: [u32 len][u16 dataPhase][u16 opcode][u32 tid][u32 params... | raw data].
     */
    private fun buildOpPacket(dataPhase: Int, opcode: Int, tid: Int, params: IntArray, data: ByteArray?): ByteArray {
        val w = PtpWriter()
        w.u32(0)                 // length placeholder
        w.u16(dataPhase)
        w.u16(opcode)
        w.u32(tid)
        if (data != null) w.bytes(data) else params.forEach { w.u32(it) }
        val packet = w.toByteArray()
        val len = packet.size
        packet[0] = (len and 0xFF).toByte()
        packet[1] = ((len ushr 8) and 0xFF).toByte()
        packet[2] = ((len ushr 16) and 0xFF).toByte()
        packet[3] = ((len ushr 24) and 0xFF).toByte()
        return packet
    }

    // ---- raw socket I/O ----

    private fun writeRaw(bytes: ByteArray) {
        val o = output ?: throw IOException("not connected")
        o.write(bytes)
        o.flush()
    }

    /** Read one length-prefixed packet; returns the packet body WITHOUT the 4-byte length prefix. */
    private fun readPacketRaw(): ByteArray {
        val lenBytes = readExactly(4)
        val len = (lenBytes[0].toInt() and 0xFF) or
            ((lenBytes[1].toInt() and 0xFF) shl 8) or
            ((lenBytes[2].toInt() and 0xFF) shl 16) or
            ((lenBytes[3].toInt() and 0xFF) shl 24)
        val bodyLen = len - 4
        if (bodyLen <= 0 || bodyLen > MAX_PACKET) {
            throw FujiProtocolException("bad packet length $len")
        }
        return readExactly(bodyLen)
    }

    private fun readExactly(n: Int): ByteArray {
        val inp = input ?: throw IOException("not connected")
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = inp.read(buf, off, n - off)
            if (read < 0) throw IOException("connection closed (wanted $n, got $off)")
            off += read
        }
        return buf
    }

    companion object {
        private const val MAX_PACKET = 64 * 1024 * 1024
    }
}

class FujiProtocolException(message: String) : IOException(message)
