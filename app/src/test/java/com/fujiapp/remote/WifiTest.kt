package com.fujiapp.remote

import com.fujiapp.remote.usb.Ptp
import com.fujiapp.remote.wifi.FujiWifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WifiTest {
    @Test
    fun shutterSpeedDecodesLikeXApp() {
        assertEquals("1/250", FujiWifi.shutterText(0x80000000L or 250_000L))
        assertEquals("1/4000", FujiWifi.shutterText(0x80000000L or 4_000_000L))
        assertEquals("1/2.5", FujiWifi.shutterText(0x80000000L or 2_500L))
        assertEquals("2s", FujiWifi.shutterText(2_000L))
        assertEquals("1.3s", FujiWifi.shutterText(1_300L))
        assertNull(FujiWifi.shutterText(0xFFFFFFFFL))
    }

    @Test
    fun objectInfoReadsTheWifiLayout() {
        val d = ByteArray(160)
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(4, 0x3801) // JPEG
        b.putInt(13, 12_345_678) // packed: the size is unaligned at 13
        val name = Ptp.string("DSCF0042.JPG")
        name.copyInto(d, 52)
        Ptp.string("20261112T131000").copyInto(d, 52 + name.size)
        val p = FujiWifi.objectInfo(42, d)!!
        assertEquals("DSCF0042.JPG", p.name)
        assertEquals(12_345_678L, p.size)
        assertEquals("20261112T131000", p.date)
    }

    @Test
    fun objectInfoSkipsFolders() {
        val d = ByteArray(120)
        ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 0x3001)
        Ptp.string("100_FUJI").copyInto(d, 52)
        assertNull(FujiWifi.objectInfo(1, d))
    }

    @Test
    fun handlesComeFromTheCountedList() {
        val d = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putInt(2).putInt(1).array()
        assertEquals(listOf(2, 1), FujiWifi.handles(d))
        assertEquals(emptyList<Int>(), FujiWifi.handles(ByteArray(3)))
    }

    @Test
    fun propDescReadsRangeAndEnumeration() {
        // 0xD17C, UINT32, get/set, default, current 0x03020504, range 0x00000101 … 0x00000D09, step 1
        val range = ByteBuffer.allocate(2 + 2 + 1 + 4 * 2 + 1 + 4 * 3).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xD17C.toShort()).putShort(6).put(1).putInt(0).putInt(0x03020504).put(1)
            .putInt(0x0101).putInt(0x0D09).putInt(1).array()
        val r = FujiWifi.parseDesc(range)!!
        assertEquals(0x03020504L, r.current)
        assertEquals(0x0D09L, r.max)
        // ISO, UINT32 enumeration of two values
        val enum = ByteBuffer.allocate(2 + 2 + 1 + 8 + 1 + 2 + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xD02A.toShort()).putShort(6).put(1).putInt(0).putInt(200).put(2)
            .putShort(2).putInt(200).putInt(400).array()
        assertEquals(listOf(200L, 400L), FujiWifi.parseDesc(enum)!!.values)
    }
}

class RafFileTest {
    @Test
    fun findsTheEmbeddedJpeg() {
        val head = ByteArray(com.fujiapp.remote.usb.RafFile.HEADER)
        "FUJIFILMCCD-RAW 0201".toByteArray(Charsets.US_ASCII).copyInto(head)
        ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN).putInt(84, 0x94).putInt(88, 1_234_567)
        assertEquals(0x94 to 1_234_567, com.fujiapp.remote.usb.RafFile.jpegRange(head))
        assertNull(com.fujiapp.remote.usb.RafFile.jpegRange(ByteArray(92)))
    }
}
