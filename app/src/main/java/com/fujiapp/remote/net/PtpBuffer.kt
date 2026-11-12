package com.fujiapp.remote.net

/** Little-endian writer for building PTP/IP packets. All multi-byte fields are little-endian. */
class PtpWriter {
    private val out = ArrayList<Byte>(64)

    fun u8(v: Int): PtpWriter { out.add((v and 0xFF).toByte()); return this }

    fun u16(v: Int): PtpWriter {
        out.add((v and 0xFF).toByte())
        out.add(((v ushr 8) and 0xFF).toByte())
        return this
    }

    fun u32(v: Int): PtpWriter {
        out.add((v and 0xFF).toByte())
        out.add(((v ushr 8) and 0xFF).toByte())
        out.add(((v ushr 16) and 0xFF).toByte())
        out.add(((v ushr 24) and 0xFF).toByte())
        return this
    }

    fun u32(v: Long): PtpWriter = u32(v.toInt())

    fun bytes(b: ByteArray): PtpWriter { b.forEach { out.add(it) }; return this }

    /** UTF-16LE string with a trailing null terminator (PTP string / Fuji device name). */
    fun utf16(s: String): PtpWriter {
        for (c in s) { out.add((c.code and 0xFF).toByte()); out.add(((c.code ushr 8) and 0xFF).toByte()) }
        out.add(0); out.add(0)
        return this
    }

    fun size(): Int = out.size
    fun toByteArray(): ByteArray = ByteArray(out.size) { out[it] }
}

/** Little-endian reader for parsing PTP/IP packets. */
class PtpReader(private val data: ByteArray, start: Int = 0) {
    var pos: Int = start
        private set

    val remaining: Int get() = data.size - pos
    fun hasRemaining(n: Int = 1): Boolean = remaining >= n
    fun skip(n: Int) { pos += n }
    fun seek(p: Int) { pos = p }

    fun u8(): Int = data[pos++].toInt() and 0xFF

    fun u16(): Int {
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): Long {
        val v = (data[pos].toLong() and 0xFF) or
            ((data[pos + 1].toLong() and 0xFF) shl 8) or
            ((data[pos + 2].toLong() and 0xFF) shl 16) or
            ((data[pos + 3].toLong() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun bytes(n: Int): ByteArray {
        val b = data.copyOfRange(pos, pos + n)
        pos += n
        return b
    }

    /**
     * Read a numeric value of the given PTP [Ptp.Type]. Returns the value widened to Long,
     * sign-extended for signed types. Returns null for non-numeric/variable types.
     */
    fun numeric(type: Int): Long? = when (type) {
        Ptp.Type.INT8 -> u8().toByte().toLong()
        Ptp.Type.UINT8 -> u8().toLong()
        Ptp.Type.INT16 -> i16().toLong()
        Ptp.Type.UINT16 -> u16().toLong()
        Ptp.Type.INT32 -> i32().toLong()
        Ptp.Type.UINT32 -> u32()
        Ptp.Type.INT64, Ptp.Type.UINT64 -> u64()
        else -> null
    }
}
