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
    ASLEEP_STANDBY("Asleep · Bluetooth standby"),
    OFF_STANDBY("Off · Bluetooth standby"),
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

    // ---- time and location (layouts from GeoShutter's docs, applied by an X100VI) ----

    /**
     * The 12-byte UTC_AND_TIMEZONE packet: UTC year (u16), month, day, hour, minute, second, then
     * the zone's standard offset in hundredths of an hour (i32, +5:30 = 550) and a daylight-saving
     * flag. The camera applies it only when it asked for the time (DATE_SYNC_STATE = 1).
     */
    fun timePacket(instant: java.time.Instant, zone: java.time.ZoneId): ByteArray {
        val utc = instant.atZone(java.time.ZoneOffset.UTC)
        val rules = zone.rules
        val standardSeconds = rules.getStandardOffset(instant).totalSeconds
        val hundredths = Math.round(standardSeconds * 100.0 / 3600.0).toInt()
        val dst = if (rules.isDaylightSavings(instant)) 1 else 0
        return le16(utc.year) + byteArrayOf(
            utc.monthValue.toByte(), utc.dayOfMonth.toByte(), utc.hour.toByte(), utc.minute.toByte(), utc.second.toByte(),
        ) + le32(hundredths.toLong()) + byteArrayOf(dst.toByte())
    }

    /**
     * The 23-byte LOCATION_AND_SPEED packet: latitude and longitude × 10⁷ (i32), altitude in whole
     * metres (i32), speed in m/s × 100 (i32), then the fix time in UTC (u16 year, month, day, hour,
     * minute, second).
     */
    fun geotagPacket(latitude: Double, longitude: Double, altitudeM: Double, speedMps: Double, utc: java.time.Instant): ByteArray {
        val t = utc.atZone(java.time.ZoneOffset.UTC)
        return le32(Math.round(latitude * 1e7)) + le32(Math.round(longitude * 1e7)) +
            le32(Math.round(altitudeM)) + le32(Math.round(speedMps * 100)) +
            le16(t.year) + byteArrayOf(
                t.monthValue.toByte(), t.dayOfMonth.toByte(), t.hour.toByte(), t.minute.toByte(), t.second.toByte(),
            )
    }

    /** The identification number as a uint32 (little-endian), for storing between connections. */
    fun idValue(bytes: ByteArray): Long = u32(bytes) ?: 0L

    // ---- state decoding (value names from XApp 2.7.6) ----

    /** AP_STATE: the camera's Wi-Fi access point. XApp's values carry bit 15; it is masked off. */
    fun apState(value: ByteArray?): String? = when (u16(value)?.and(0x7FFF)) {
        0 -> "Off"
        1 -> "On"
        2 -> "Starting"
        3 -> "On, for reserved transfer"
        null -> null
        else -> "Code ${u16(value)}"
    }

    /** TRANSFER_STATE: whether pictures are waiting to be sent to the phone. */
    fun transferState(value: ByteArray?): String? = when (u16(value)?.and(0x7FFF)) {
        0 -> "Nothing waiting"
        1 -> "Pictures waiting"
        null -> null
        else -> "Code ${u16(value)}"
    }

    /** STATE_ERROR_DETAILS: why the camera can't serve a request right now (14 = nothing). */
    fun stateErrorDetails(value: ByteArray?): String? = when (val v = u16(value)) {
        null -> null
        0 -> "Other"
        1 -> "FTP in use"
        2 -> "Busy (short operation)"
        3 -> "Busy (long operation)"
        4 -> "Finishing an operation"
        5 -> "Initial setup"
        6 -> "USB in use"
        7 -> "Tethered shooting (auto)"
        8 -> "Tethered shooting (fixed)"
        9 -> "USB RAW conversion"
        10 -> "USB webcam"
        11 -> "Live remote"
        12 -> "Pictures not transferred"
        13 -> "Frame.io in use"
        14 -> "None"
        else -> "Code $v"
    }

    /** LOGGING_SETTING: one byte per activity-log item, in XApp's order. */
    val LOGGING_ITEMS: List<String> = listOf(
        "Model name", "Camera ID", "Power on/off", "Shooting", "Shooting location", "Shooting settings",
        "Shooting result", "Lens name", "Lens ID", "Camera status", "Fn button use", "Image transfer", "Thumbnails",
    )

    /** The activity-log items the camera records, or null if the value isn't one byte per item. */
    fun loggingItems(value: ByteArray?): List<String>? {
        if (value == null || value.size < LOGGING_ITEMS.size || value.any { it.toInt() !in 0..1 }) return null
        return LOGGING_ITEMS.filterIndexed { i, _ -> value[i].toInt() == 1 }
    }

    fun onOff(value: ByteArray?): String? = (u16(value) ?: u8(value))?.let { if (it != 0) "On" else "Off" }

    fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    fun le32(v: Long): ByteArray = ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }

    fun hex(bytes: ByteArray?): String =
        bytes?.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } ?: "null"

    /** Printable ASCII only (advertised serials are padded). */
    fun printable(bytes: ByteArray): String =
        String(bytes.filter { it in 0x20..0x7E }.toByteArray(), Charsets.US_ASCII)
}
