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

class DownloadWindowTest {
    private val mb = 1024 * 1024

    @Test
    fun fullWindowsAreKept() {
        assertEquals(mb, FujiWifi.keepOf(0, mb, mb, 11_649_721))
    }

    @Test
    fun aShortWindowMidFileKeepsWholeBlocksOnly() {
        // 700,000 bytes came back at offset 0 of an 11.6 MB file: keep 699,904 (1367 × 512), ask again from there.
        assertEquals(699_904, FujiWifi.keepOf(0, 700_000, mb, 11_649_721))
        // From an aligned offset as well.
        assertEquals(512 * 100, FujiWifi.keepOf(mb.toLong(), 512 * 100 + 37, mb, 11_649_721))
    }

    @Test
    fun theLastWindowIsKeptWhole() {
        val total = 11_649_721L
        val offset = 11L * mb
        assertEquals((total - offset).toInt(), FujiWifi.keepOf(offset, (total - offset).toInt(), (total - offset).toInt(), total))
    }

    @Test
    fun aTinyWindowStillMovesOn() {
        assertEquals(100, FujiWifi.keepOf(0, 100, mb, 11_649_721))
    }
}

class X100viObjectInfoTest {
    /** The head of an X100VI's ObjectInfo over Wi-Fi (from a log): standard layout, the size at 8. */
    @Test
    fun theSizeIsTheStandardField() {
        val head = "01 00 00 10 01 38 00 00 ee a6 91 01 01 b9 42 8a 00 00 80 02 00 00 e0 01 00 00 30 1e 00 00 20 14 00 00"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val d = ByteArray(120)
        head.copyInto(d)
        Ptp.string("SRGC2157.JPG").copyInto(d, 52)
        val p = FujiWifi.objectInfo(6, d)!!
        assertEquals("SRGC2157.JPG", p.name)
        assertEquals(0x0191A6EEL, p.size) // 26.3 MB, not the 9 MB read from offset 13
    }

    @Test
    fun deviceInfoListsEachSetting() {
        // Two entries: D001 (UINT16, enum of 3) and 5012 (UINT16, enum of 3).
        fun entry(code: Int, current: Int, values: List<Int>): ByteArray {
            val desc = ByteBuffer.allocate(2 + 2 + 1 + 2 + 2 + 1 + 2 + 2 * values.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(code.toShort()).putShort(4).put(1).putShort(0).putShort(current.toShort()).put(2).putShort(values.size.toShort())
            values.forEach { desc.putShort(it.toShort()) }
            val body = desc.array()
            return ByteBuffer.allocate(4 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + body.size).put(body).array()
        }
        val a = entry(0xD001, 0x11, listOf(1, 0x11, 0x14))
        val b = entry(0x5012, 0, listOf(0, 2, 4))
        val all = ByteBuffer.allocate(4 + a.size + b.size).order(ByteOrder.LITTLE_ENDIAN).putInt(2).put(a).put(b).array()
        val descs = FujiWifi.parseDeviceDescs(all)
        assertEquals(listOf(1L, 0x11L, 0x14L), descs[0xD001]!!.values)
        assertEquals(0x11L, descs[0xD001]!!.current)
        assertEquals(listOf(0L, 2L, 4L), descs[0x5012]!!.values)
    }
}
