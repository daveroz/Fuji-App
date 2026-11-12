package com.fujiapp.remote

import com.fujiapp.remote.net.Ptp
import com.fujiapp.remote.net.PtpReader
import com.fujiapp.remote.net.PtpWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PtpBufferTest {

    @Test fun u16_is_little_endian() {
        val b = PtpWriter().u16(0x1234).toByteArray()
        assertArrayEquals(byteArrayOf(0x34, 0x12), b)
        assertEquals(0x1234, PtpReader(b).u16())
    }

    @Test fun u32_round_trips_including_high_bit() {
        val value = 0x8F53E4F2L
        val b = PtpWriter().u32(value).toByteArray()
        assertArrayEquals(byteArrayOf(0xF2.toByte(), 0xE4.toByte(), 0x53, 0x8F.toByte()), b)
        assertEquals(value, PtpReader(b).u32())
    }

    @Test fun utf16_name_is_null_terminated_le() {
        val b = PtpWriter().utf16("Hi").toByteArray()
        assertArrayEquals(byteArrayOf('H'.code.toByte(), 0, 'i'.code.toByte(), 0, 0, 0), b)
    }

    @Test fun numeric_reads_respect_type_and_sign() {
        // INT16 -1 then UINT16 0xFFFF
        val b = PtpWriter().u16(0xFFFF).u16(0xFFFF).toByteArray()
        val r = PtpReader(b)
        assertEquals(-1L, r.numeric(Ptp.Type.INT16))
        assertEquals(0xFFFFL, r.numeric(Ptp.Type.UINT16))
    }

    @Test fun type_sizes_are_correct() {
        assertEquals(1, Ptp.Type.sizeOf(Ptp.Type.UINT8))
        assertEquals(2, Ptp.Type.sizeOf(Ptp.Type.UINT16))
        assertEquals(4, Ptp.Type.sizeOf(Ptp.Type.UINT32))
        assertEquals(null, Ptp.Type.sizeOf(Ptp.Type.STR))
    }

    @Test fun response_code_discriminator() {
        assert(Ptp.Resp.isResponseCode(0x2001))
        assert(!Ptp.Resp.isResponseCode(0x1015)) // an opcode, not a response
        assert(!Ptp.Resp.isResponseCode(0xD001)) // a property code
    }
}
