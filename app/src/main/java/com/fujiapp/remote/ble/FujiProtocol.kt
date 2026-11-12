package com.fujiapp.remote.ble

import java.util.UUID

/** What a Fujifilm advertisement says about the camera. */
data class FujiAdvert(
    val kind: Kind,
    /** The short serial a secure camera advertises (e.g. "1A2B3"), or a legacy token in hex. */
    val tag: String,
    /** True while the camera is in PAIRING REGISTRATION and accepts a new phone. */
    val pairingMode: Boolean,
) {
    enum class Kind {
        /** Firmware with bonded ("secure") Bluetooth, e.g. X100VI 1.30 and later. */
        SECURE,
        /** Older token-pairing firmware. Not supported: it lacks the backup service layout used here. */
        LEGACY,
        UNKNOWN,
    }

    companion object {
        /**
         * Parse Fujifilm manufacturer data. [data] is the payload after the 2-byte company ID (what
         * Android's `ScanRecord.getManufacturerSpecificData(0x04D8)` returns): a type byte, then
         * 5 ASCII serial characters (type 0x01, secure) or a 4-byte token (type 0x02, legacy).
         */
        fun parse(data: ByteArray?, serviceUuids: Collection<UUID> = emptyList()): FujiAdvert? {
            if (data == null || data.isEmpty()) return null
            val type = data[0].toInt() and 0xFF
            val rest = data.copyOfRange(1, data.size)
            val pairing = FujiGatt.ADVERTISED_PAIRING in serviceUuids
            return when (type) {
                0x02 -> FujiAdvert(Kind.LEGACY, FujiProtocol.hex(rest.copyOf(minOf(4, rest.size))), pairing)
                0x01 -> FujiAdvert(Kind.SECURE, FujiProtocol.printable(rest), pairing)
                else -> FujiAdvert(Kind.UNKNOWN, FujiProtocol.printable(rest), pairing)
            }
        }
    }
}

/** The camera's power switch and whether it is running normally or in Bluetooth standby. */
enum class PowerState(val label: String) {
    ON("On"),
    OFF("Off"),
    ASLEEP_STANDBY("Asleep (Bluetooth standby)"),
    OFF_STANDBY("Off (Bluetooth standby)"),
}

/** Byte-level helpers for the Fujifilm BLE messages. Pure Kotlin so it is unit-tested on the JVM. */
object FujiProtocol {

    /** Name the camera stores for this phone (CONNECTED_DEVICE_NAME holds at most 30 bytes). */
    const val CLIENT_NAME = "Fuji Remote"
    private const val CLIENT_NAME_MAX = 30

    /**
     * The status acknowledgement: the 4 bytes read from DEVICE_IDENTIFICATION_NUMBER with the last
     * byte set to 0x20, the marker XApp uses (Camera Remote uses 0x10). Seen on an X100VI:
     * read `0c 01 00 00`, write `0c 01 00 20`.
     */
    fun ackStatus(status: ByteArray): ByteArray {
        require(status.size == 4) { "status must be 4 bytes, got ${status.size}" }
        return status.copyOf().also { it[3] = 0x20 }
    }

    /** The client name as XApp writes it: UTF-8 followed by a NUL, within the 30-byte field. */
    fun clientName(name: String = CLIENT_NAME): ByteArray {
        var utf8 = name.toByteArray(Charsets.UTF_8)
        if (utf8.size > CLIENT_NAME_MAX - 1) utf8 = utf8.copyOf(CLIENT_NAME_MAX - 1)
        return utf8 + byteArrayOf(0)
    }

    /** CAMERA_POWER_KEY_STATE: first byte is the switch (1 on), second 2 = normal, 1 = standby. */
    fun powerState(value: ByteArray?): PowerState? = when (u16(value)) {
        0x0201 -> PowerState.ON
        0x0200 -> PowerState.OFF
        0x0101 -> PowerState.ASLEEP_STANDBY
        0x0100 -> PowerState.OFF_STANDBY
        else -> null
    }

    /** A NUL-terminated (or NUL-padded) ASCII/UTF-8 string, as the camera's string characteristics are. */
    fun string(value: ByteArray?): String? {
        if (value == null) return null
        val end = value.indexOf(0).let { if (it < 0) value.size else it }
        return String(value, 0, end, Charsets.UTF_8).trim().ifEmpty { null }
    }

    fun u8(value: ByteArray?): Int? = value?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF }

    fun u16(value: ByteArray?): Int? =
        value?.takeIf { it.size >= 2 }?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }

    fun u32(value: ByteArray?, offset: Int = 0): Long? {
        if (value == null || value.size < offset + 4) return null
        var v = 0L
        for (i in 0 until 4) v = v or ((value[offset + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    fun le32(v: Long): ByteArray = ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }

    fun hex(bytes: ByteArray?): String =
        bytes?.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } ?: "null"

    /** Printable ASCII only (advertised serials are padded). */
    fun printable(bytes: ByteArray): String =
        String(bytes.filter { it in 0x20..0x7E }.toByteArray(), Charsets.US_ASCII)
}
