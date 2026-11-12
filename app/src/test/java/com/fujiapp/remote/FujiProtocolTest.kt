package com.fujiapp.remote

import com.fujiapp.remote.ble.FujiAdvert
import com.fujiapp.remote.ble.FujiGatt
import com.fujiapp.remote.ble.FujiProtocol
import com.fujiapp.remote.ble.PowerState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FujiProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun status_ack_sets_last_byte_to_0x20() {
        // Read and written on an X100VI after registration (GeoShutter).
        assertArrayEquals(bytes(0x0c, 0x01, 0x00, 0x20), FujiProtocol.ackStatus(bytes(0x0c, 0x01, 0x00, 0x00)))
        assertArrayEquals(bytes(0xfc, 0xb1, 0x21, 0x20), FujiProtocol.ackStatus(bytes(0xfc, 0xb1, 0x21, 0x00)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun status_ack_rejects_wrong_length() {
        FujiProtocol.ackStatus(bytes(1, 2, 3))
    }

    @Test fun client_name_is_utf8_with_nul_within_30_bytes() {
        val name = FujiProtocol.clientName("Fuji Remote")
        assertEquals("Fuji Remote".length + 1, name.size)
        assertEquals(0, name.last().toInt())
        val long = FujiProtocol.clientName("x".repeat(64))
        assertEquals(30, long.size)
        assertEquals(0, long.last().toInt())
    }

    @Test fun power_state_decodes_switch_and_standby() {
        assertEquals(PowerState.ON, FujiProtocol.powerState(bytes(0x01, 0x02)))
        assertEquals(PowerState.OFF, FujiProtocol.powerState(bytes(0x00, 0x02)))
        assertEquals(PowerState.ASLEEP_STANDBY, FujiProtocol.powerState(bytes(0x01, 0x01)))
        assertEquals(PowerState.OFF_STANDBY, FujiProtocol.powerState(bytes(0x00, 0x01)))
        assertNull(FujiProtocol.powerState(bytes(0x07)))
    }

    @Test fun strings_stop_at_nul_and_trim() {
        assertEquals("X100VI", FujiProtocol.string("X100VI\u0000\u0000junk".toByteArray()))
        assertEquals("01.32", FujiProtocol.string("01.32".toByteArray()))
        assertNull(FujiProtocol.string(bytes(0, 0, 0)))
        assertNull(FujiProtocol.string(null))
    }

    @Test fun little_endian_integers() {
        assertEquals(0x0201, FujiProtocol.u16(bytes(0x01, 0x02)))
        assertEquals(0x827CL, FujiProtocol.u32(bytes(0x7c, 0x82, 0x00, 0x00)))
        assertEquals(0xFFFFFFFFL, FujiProtocol.u32(bytes(0xff, 0xff, 0xff, 0xff)))
        assertArrayEquals(bytes(120, 0, 0, 0), FujiProtocol.le32(120))
        assertArrayEquals(bytes(0x01, 0x00), FujiProtocol.le16(1))
        assertNull(FujiProtocol.u16(bytes(1)))
    }

    @Test fun secure_advert_carries_serial_and_pairing_mode() {
        val data = bytes(0x01) + "1A2B3".toByteArray()
        val pairing = FujiAdvert.parse(data, listOf(FujiGatt.ADVERTISED_PAIRING))!!
        assertEquals(FujiAdvert.Kind.SECURE, pairing.kind)
        assertEquals("1A2B3", pairing.tag)
        assertTrue(pairing.pairingMode)

        val bonded = FujiAdvert.parse(data, FujiGatt.ADVERTISED_BONDED)!!
        assertFalse(bonded.pairingMode)
    }

    @Test fun legacy_advert_is_recognised() {
        val advert = FujiAdvert.parse(bytes(0x02, 0xde, 0xad, 0xbe, 0xef))!!
        assertEquals(FujiAdvert.Kind.LEGACY, advert.kind)
        assertEquals("de ad be ef", advert.tag)
        assertNull(FujiAdvert.parse(ByteArray(0)))
    }
}
