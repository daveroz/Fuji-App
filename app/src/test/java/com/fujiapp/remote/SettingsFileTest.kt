package com.fujiapp.remote

import com.fujiapp.remote.model.SettingsFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater

class SettingsFileTest {

    /** A settings file shaped like an X100VI 1.31 backup: header, filler, then seven slot records. */
    private fun fakeFile(names: List<String>, size: Int = 70_524, marks: Boolean = true): ByteArray {
        val b = ByteArray(size)
        "FUJIFILMX-BACKUP0100X100VI".toByteArray().copyInto(b, 0)
        val start = size - SettingsFile.SLOTS * SettingsFile.RECORD
        names.forEachIndexed { i, name ->
            val at = start + i * SettingsFile.RECORD
            if (marks) {
                b[at] = 0x5C; b[at + 1] = 0x01
                b[at + 4] = 0x5C; b[at + 5] = 0x01
            }
            name.toByteArray().copyInto(b, at + SettingsFile.NAME_AT)
            name.toByteArray().copyInto(b, at + SettingsFile.NAME_AT + 26)
        }
        return b
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION)
        d.setInput(raw)
        d.finish()
        val out = ByteArray(raw.size)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    private val names = listOf("KODACHROME", "PORTRA", "", "CLASSIC NEG", "ACROS", "ETERNA", "REALA")

    @Test fun reads_the_seven_custom_slot_names_from_a_downloaded_backup() {
        val compressed = deflate(fakeFile(names))
        assertEquals(0x78, compressed[0].toInt() and 0xFF) // zlib, as the camera sends it
        val f = SettingsFile.parse(compressed)!!
        assertEquals("X100VI", f.model)
        assertEquals("0100", f.version)
        assertEquals(70_524, f.size)
        assertEquals(names, f.customSlots.map { it.name })
        assertEquals(listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7"), f.customSlots.map { it.label })
    }

    @Test fun accepts_an_unpacked_file_too() {
        assertEquals(names, SettingsFile.parse(fakeFile(names))!!.customSlots.map { it.name })
    }

    @Test fun no_slots_when_the_records_are_not_where_expected() {
        val f = SettingsFile.parse(deflate(fakeFile(names, marks = false)))!!
        assertTrue(f.customSlots.isEmpty())
    }

    @Test fun not_a_settings_file() {
        assertNull(SettingsFile.parse(byteArrayOf(1, 2, 3)))
        assertNull(SettingsFile.parse(deflate("hello".toByteArray())))
    }
}
