package com.fujiapp.remote.usb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP (ISO 15740) containers as they travel over USB bulk endpoints, little-endian:
 * `u32 length, u16 type, u16 code, u32 transaction id, payload`. Pure, so it can be tested.
 */
object Ptp {
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4
    const val HEADER = 12

    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016

    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val PARAMETER_NOT_SUPPORTED = 0x2006
    const val INVALID_OBJECT = 0x2009
    const val DEVICE_BUSY = 0x2019
    const val PROP_NOT_SUPPORTED = 0x200A
    const val INVALID_VALUE = 0x201C
    const val SESSION_ALREADY_OPEN = 0x201E

    data class Container(val type: Int, val code: Int, val transaction: Int, val payload: ByteArray) {
        val params: List<Int>
            get() = (0 until payload.size / 4).map { i -> ByteBuffer.wrap(payload, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int }
    }

    fun command(code: Int, transaction: Int, params: List<Int> = emptyList()): ByteArray {
        require(params.size <= 5) { "PTP commands take at most five parameters" }
        val b = ByteBuffer.allocate(HEADER + 4 * params.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(HEADER + 4 * params.size).putShort(TYPE_COMMAND.toShort()).putShort(code.toShort()).putInt(transaction)
        params.forEach { b.putInt(it) }
        return b.array()
    }

    fun data(code: Int, transaction: Int, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(HEADER + payload.size).putShort(TYPE_DATA.toShort()).putShort(code.toShort()).putInt(transaction).put(payload)
        return b.array()
    }

    /** The total length a container announces in its first four bytes. */
    fun length(firstBytes: ByteArray): Int {
        if (firstBytes.size < 4) throw IOException("Short PTP container")
        return ByteBuffer.wrap(firstBytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun parse(bytes: ByteArray): Container {
        if (bytes.size < HEADER) throw IOException("Short PTP container (${bytes.size} bytes)")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val len = b.int
        val type = b.short.toInt() and 0xFFFF
        val code = b.short.toInt() and 0xFFFF
        val tid = b.int
        val end = minOf(len, bytes.size)
        return Container(type, code, tid, bytes.copyOfRange(HEADER, maxOf(HEADER, end)))
    }

    fun u16(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    fun readU16(payload: ByteArray): Int {
        if (payload.size < 2) throw IOException("Expected a 16-bit value, got ${payload.size} bytes")
        return (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
    }

    /** PTP string: a count of UCS-2 code units including the terminating NUL, then the units. Empty: a single 0. */
    fun string(s: String): ByteArray {
        if (s.isEmpty()) return byteArrayOf(0)
        val out = ByteArrayOutputStream()
        out.write(s.length + 1)
        for (ch in s) {
            out.write(ch.code and 0xFF)
            out.write((ch.code shr 8) and 0xFF)
        }
        out.write(0)
        out.write(0)
        return out.toByteArray()
    }

    fun readString(payload: ByteArray, offset: Int = 0): String {
        if (payload.size <= offset) return ""
        val count = payload[offset].toInt() and 0xFF
        val sb = StringBuilder()
        for (i in 0 until count) {
            val at = offset + 1 + i * 2
            if (at + 1 >= payload.size) break
            val unit = (payload[at].toInt() and 0xFF) or ((payload[at + 1].toInt() and 0xFF) shl 8)
            if (unit == 0) break
            sb.append(unit.toChar())
        }
        return sb.toString()
    }

    fun responseName(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "general error"
        SESSION_NOT_OPEN -> "session not open"
        OPERATION_NOT_SUPPORTED -> "operation not supported"
        PARAMETER_NOT_SUPPORTED -> "parameter not supported"
        INVALID_OBJECT -> "no such picture"
        PROP_NOT_SUPPORTED -> "property not supported"
        DEVICE_BUSY -> "device busy"
        INVALID_VALUE -> "value refused"
        SESSION_ALREADY_OPEN -> "session already open"
        else -> "0x%04X".format(code)
    }
}

/** A PTP operation the camera answered with something other than OK. */
class PtpException(val code: Int, operation: String) : IOException("$operation: ${Ptp.responseName(code)}")
