package com.fujiapp.remote

import com.fujiapp.remote.ble.FujiGatt
import com.fujiapp.remote.ble.FujiProtocol
import com.fujiapp.remote.model.CameraSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StatusTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun time_packet_matches_geoshutter_example() {
        // 2026-09-29 12:34:56 UTC in a UTC+1 zone during daylight saving time.
        val p = FujiProtocol.timePacket(Instant.parse("2026-09-29T12:34:56Z"), ZoneId.of("Europe/Berlin"))
        assertArrayEquals(bytes(0xEA, 0x07, 0x09, 0x1D, 0x0C, 0x22, 0x38, 0x64, 0x00, 0x00, 0x00, 0x01), p)
    }

    @Test fun time_packet_offsets_in_hundredths_of_an_hour() {
        val india = FujiProtocol.timePacket(Instant.parse("2026-01-15T00:00:00Z"), ZoneId.of("Asia/Kolkata"))
        assertArrayEquals(bytes(0x26, 0x02, 0x00, 0x00), india.copyOfRange(7, 11)) // +5:30 = 550
        assertEquals(0, india[11].toInt())
        val newfoundland = FujiProtocol.timePacket(Instant.parse("2026-01-15T00:00:00Z"), ZoneId.of("America/St_Johns"))
        assertArrayEquals(bytes(0xA2, 0xFE, 0xFF, 0xFF), newfoundland.copyOfRange(7, 11)) // −3:30 = −350
    }

    @Test fun geotag_packet_matches_geoshutter_example() {
        val p = FujiProtocol.geotagPacket(25.2048, 55.2708, 5.0, 0.0, Instant.parse("2026-09-28T12:34:56Z"))
        assertArrayEquals(
            bytes(
                0x80, 0xF2, 0x05, 0x0F, 0xA0, 0xA7, 0xF1, 0x20, 0x05, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0xEA, 0x07, 0x09, 0x1C, 0x0C, 0x22, 0x38,
            ),
            p,
        )
    }

    @Test fun geotag_packet_handles_south_and_west() {
        val p = FujiProtocol.geotagPacket(-33.8688, -151.2093, -2.0, 1.5, Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(23, p.size)
        assertEquals((-338688000L).toInt(), FujiProtocol.u32(p, 0)!!.toInt())
        assertEquals((-1512093000L).toInt(), FujiProtocol.u32(p, 4)!!.toInt())
        assertEquals(-2, FujiProtocol.u32(p, 8)!!.toInt())
        assertEquals(150L, FujiProtocol.u32(p, 12))
    }

    @Test fun stored_identification_number_round_trips() {
        val ack = FujiProtocol.ackStatus(bytes(0x65, 0x48, 0x26, 0x00))
        assertArrayEquals(ack, FujiProtocol.le32(FujiProtocol.idValue(ack)))
        assertEquals(0x20264865L, FujiProtocol.idValue(ack))
    }

    @Test fun states_decode_with_or_without_bit_15() {
        assertEquals("On", FujiProtocol.apState(bytes(0x01, 0x00)))
        assertEquals("On", FujiProtocol.apState(bytes(0x01, 0x80)))
        assertEquals("Off", FujiProtocol.apState(bytes(0x00, 0x80)))
        assertEquals("Pictures waiting", FujiProtocol.transferState(bytes(0x01, 0x00)))
        assertEquals("None", FujiProtocol.stateErrorDetails(bytes(0x0E, 0x00)))
        assertEquals("USB in use", FujiProtocol.stateErrorDetails(bytes(0x06, 0x00)))
        assertNull(FujiProtocol.apState(null))
    }

    @Test fun logging_setting_lists_recorded_items() {
        val all = ByteArray(13) { 1 }
        assertEquals(13, FujiProtocol.loggingItems(all)!!.size)
        val some = ByteArray(13).also { it[3] = 1; it[11] = 1 }
        assertEquals(listOf("Shooting", "Image transfer"), FujiProtocol.loggingItems(some))
        assertNull(FujiProtocol.loggingItems(bytes(5, 6)))
    }

    @Test fun snapshot_decodes_and_hides_binary_values() {
        val snapshot = CameraSnapshot()
            .with(FujiGatt.DEVICE_NAME, "X100VI\u0000\u0000".toByteArray())
            .with(FujiGatt.FIRMWARE_REVISION, "01.31".toByteArray())
            .with(FujiGatt.POWER_KEY_STATE, bytes(0x01, 0x02))
            .with(FujiGatt.LOCATION_SYNC_CYCLE, bytes(0x0A, 0x00))
            .with(FujiGatt.LOCATION_SYNC_SETTING, bytes(0x01, 0x00))
            // The X100VI returns binary data for the lens name: it must not show up as text.
            .with(FujiGatt.LENS_PRODUCT_NAME, bytes(0x00, 0x00, 0x08, 0x00, 0x1E, 0x00))
            .with(FujiGatt.LENS_FIRMWARE_VERSION, "00.00".toByteArray())

        assertEquals("X100VI", snapshot.info.model)
        assertNull(snapshot.info.lensName)
        val rows = snapshot.sections().flatMap { s -> s.rows.map { s.title to it.label to it.value } }.toMap()
        assertEquals("On", rows["State" to "Power"])
        assertEquals("10 s", rows["Connection" to "Location interval"])
        assertEquals("On", rows["Connection" to "Location sync"])
        assertNull(rows["Lens" to "Lens firmware"]) // 00.00 is the fixed lens placeholder
        assertEquals(true, snapshot.locationSyncOn)
    }
}
