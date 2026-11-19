package com.fujiapp.remote

import com.fujiapp.remote.recipe.DrPriority
import com.fujiapp.remote.recipe.DynamicRange
import com.fujiapp.remote.recipe.Effect
import com.fujiapp.remote.recipe.FilmSim
import com.fujiapp.remote.recipe.Grain
import com.fujiapp.remote.recipe.ShotSettings
import com.fujiapp.remote.recipe.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A camera JPEG's Exif, built byte by byte: IFD0 → Exif IFD → Fujifilm's maker note. */
class ShotSettingsTest {

    private class Tag(val tag: Int, val type: Int, val values: IntArray)

    private fun short(tag: Int, v: Int) = Tag(tag, 3, intArrayOf(v))
    private fun slong(tag: Int, vararg v: Int) = Tag(tag, 9, v)

    /** A TIFF directory at [at] (offsets from the TIFF start), its values after it. */
    private fun ifd(tags: List<Tag>, at: Int, little: Boolean, extra: ByteArray = ByteArray(0), extraTag: Int = -1): ByteArray {
        val out = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val dataStart = at + 2 + tags.size * 12 + 4
        fun w16(o: ByteArrayOutputStream, v: Int) { if (little) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) } else { o.write((v shr 8) and 0xFF); o.write(v and 0xFF) } }
        fun w32(o: ByteArrayOutputStream, v: Int) { if (little) { w16(o, v and 0xFFFF); w16(o, v ushr 16) } else { w16(o, v ushr 16); w16(o, v and 0xFFFF) } }
        w16(out, tags.size + if (extraTag >= 0) 1 else 0)
        for (t in tags.sortedBy { it.tag }) {
            w16(out, t.tag); w16(out, t.type); w32(out, t.values.size)
            val width = if (t.type == 3) 2 else 4
            val bytes = ByteArrayOutputStream().also { b -> t.values.forEach { if (width == 2) w16(b, it) else w32(b, it) } }.toByteArray()
            if (bytes.size <= 4) {
                out.write(bytes); repeat(4 - bytes.size) { out.write(0) }
            } else {
                w32(out, dataStart + (if (extraTag >= 0) 12 else 0) + data.size()); data.write(bytes)
            }
        }
        if (extraTag >= 0) {
            // An UNDEFINED blob (the maker note) right after the entries' data.
            w16(out, extraTag); w16(out, 7); w32(out, extra.size); w32(out, dataStart + 12 + data.size())
        }
        w32(out, 0)
        out.write(data.toByteArray())
        if (extraTag >= 0) out.write(extra)
        return out.toByteArray()
    }

    private fun makerNote(tags: List<Tag>): ByteArray {
        // Offsets in Fujifilm's note count from its start and it's always little-endian.
        val head = "FUJIFILM".toByteArray(Charsets.US_ASCII) + byteArrayOf(12, 0, 0, 0)
        return head + ifd(tags, 12, little = true)
    }

    private fun jpeg(maker: List<Tag>?, little: Boolean, orientation: Int = 1): ByteArray {
        fun w16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun w32(v: Int) = if (little) w16(v and 0xFFFF) + w16(v ushr 16) else w16(v ushr 16) + w16(v and 0xFFFF)
        val header = (if (little) "II" else "MM").toByteArray() + w16(42) + w32(8)
        // IFD0: orientation and the Exif IFD pointer (an IFD with 2 entries is 2 + 24 + 4 = 30 bytes).
        val exifAt = 8 + 30
        val ifd0 = ifd(listOf(short(0x0112, orientation), Tag(0x8769, 4, intArrayOf(exifAt))), 8, little)
        val exif = if (maker != null) ifd(emptyList(), exifAt, little, makerNote(maker), 0x927C) else ifd(emptyList(), exifAt, little)
        val tiff = header + ifd0 + exif
        val app1 = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = app1.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) +
            app1 + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())
    }

    private val classicChrome = listOf(
        short(0x1401, 0x600),
        short(0x1003, 0x400), // color −2
        slong(0x1041, -16), // highlight +1
        slong(0x1040, 24), // shadow −1.5
        short(0x1002, 0xFF0),
        short(0x1005, 4870),
        slong(0x100A, 40, -80), // R +2, B −4
        short(0x1402, 1),
        short(0x1403, 400),
        slong(0x1048, 32),
        slong(0x104E, 64),
        slong(0x1047, 32),
        slong(0x104C, 32),
        short(0x1001, 0x82),
        short(0x100E, 0x280),
        slong(0x100F, -2000),
    )

    @Test fun reads_a_classic_chrome_shot_little_and_big_endian() {
        for (little in listOf(true, false)) {
            val exif = ShotSettings.read(jpeg(classicChrome, little, orientation = 6))
            assertNotNull(exif)
            assertEquals(6, exif!!.orientation)
            val r = exif.recipe!!
            assertEquals(FilmSim.CLASSIC_CHROME, r.filmSim)
            assertEquals(-2, r.color)
            assertEquals(1.0, r.highlight, 0.0)
            assertEquals(-1.5, r.shadow, 0.0)
            assertEquals(WhiteBalance.KELVIN, r.whiteBalance)
            assertEquals(4870, r.kelvin)
            assertEquals(2, r.wbRed)
            assertEquals(-4, r.wbBlue)
            assertEquals(DynamicRange.DR400, r.dynamicRange)
            assertEquals(DrPriority.OFF, r.drPriority)
            assertEquals(Effect.WEAK, r.colorChrome)
            assertEquals(Effect.STRONG, r.colorChromeBlue)
            assertEquals(Grain.WEAK_LARGE, r.grain)
            assertEquals(-1, r.sharpness)
            assertEquals(-1, r.noiseReduction)
            assertEquals(-2, r.clarity)
            assertEquals(emptyList<String>(), r.problems().filter { "Name" !in it })
        }
    }

    @Test fun monochrome_comes_from_the_saturation_tag() {
        val r = ShotSettings.read(jpeg(listOf(short(0x1003, 0x501), short(0x1401, 0), slong(0x1049, 5), slong(0x104B, -3)), little = false))!!.recipe!!
        assertEquals(FilmSim.ACROS_R, r.filmSim)
        assertEquals(0, r.color)
        assertEquals(5, r.monoWarmCool)
        assertEquals(-3, r.monoMagentaGreen)
    }

    @Test fun d_range_priority_and_auto_dynamic_range() {
        val fixed = ShotSettings.read(jpeg(listOf(short(0x1401, 0), short(0x1443, 1), short(0x1445, 2), slong(0x1041, -32)), little = true))!!.recipe!!
        assertEquals(DrPriority.STRONG, fixed.drPriority)
        assertEquals(0.0, fixed.highlight, 0.0) // the camera set the tones
        val auto = ShotSettings.read(jpeg(listOf(short(0x1401, 0xB00), short(0x1402, 0)), little = true))!!.recipe!!
        assertEquals(FilmSim.REALA_ACE, auto.filmSim)
        assertEquals(DynamicRange.AUTO, auto.dynamicRange)
        assertEquals(DrPriority.OFF, auto.drPriority)
        assertEquals(WhiteBalance.AUTO, auto.whiteBalance)
    }

    @Test fun no_maker_note_or_no_film_simulation_gives_no_recipe() {
        val plain = ShotSettings.read(jpeg(null, little = true, orientation = 8))
        assertEquals(8, plain!!.orientation)
        assertNull(plain.recipe)
        assertNull(ShotSettings.read(jpeg(listOf(short(0x1002, 0x100)), little = true))!!.recipe)
        assertNull(ShotSettings.read(jpeg(listOf(short(0x1401, 0x7777)), little = true))!!.recipe)
    }

    @Test fun a_raf_s_embedded_jpeg_tells_how_it_was_shot() {
        // The Lab's path with no camera: RAF header → embedded JPEG → its Exif → the recipe it was shot with.
        val jpeg = jpeg(classicChrome, little = false, orientation = 8)
        val offset = 0x100
        val raf = ByteArray(offset + jpeg.size + 64)
        "FUJIFILMCCD-RAW 0201".toByteArray(Charsets.US_ASCII).copyInto(raf)
        java.nio.ByteBuffer.wrap(raf).putInt(84, offset).putInt(88, jpeg.size)
        jpeg.copyInto(raf, offset)
        val (start, length) = com.fujiapp.remote.usb.RafFile.jpegRange(raf.copyOf(com.fujiapp.remote.usb.RafFile.HEADER))!!
        val exif = ShotSettings.read(raf.copyOfRange(start, start + length))!!
        assertEquals(8, exif.orientation)
        assertEquals(FilmSim.CLASSIC_CHROME, exif.recipe!!.filmSim)
        assertEquals(DynamicRange.DR400, exif.recipe!!.dynamicRange)
    }

    @Test fun damaged_or_foreign_data_is_refused_quietly() {
        assertNull(ShotSettings.read(ByteArray(0)))
        assertNull(ShotSettings.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(ShotSettings.read("not a jpeg at all".toByteArray()))
        val good = jpeg(classicChrome, little = true)
        for (cut in listOf(10, 30, 60, good.size / 2, good.size - 8)) {
            // Cut short: either nothing or a partial read, never a crash.
            ShotSettings.read(good.copyOf(cut))
        }
    }
}
