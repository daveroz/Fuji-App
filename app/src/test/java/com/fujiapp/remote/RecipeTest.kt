package com.fujiapp.remote

import com.fujiapp.remote.recipe.BankCodec
import com.fujiapp.remote.recipe.DrPriority
import com.fujiapp.remote.recipe.DynamicRange
import com.fujiapp.remote.recipe.Effect
import com.fujiapp.remote.recipe.FilmSim
import com.fujiapp.remote.recipe.Grain
import com.fujiapp.remote.recipe.Recipe
import com.fujiapp.remote.recipe.WhiteBalance
import com.fujiapp.remote.usb.Ptp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun ptp_command_and_data_containers() {
        assertArrayEquals(
            bytes(0x10, 0, 0, 0, 0x01, 0, 0x15, 0x10, 0x05, 0, 0, 0, 0x92, 0xD1, 0, 0),
            Ptp.command(Ptp.GET_DEVICE_PROP_VALUE, 5, listOf(BankCodec.FILM_SIM)),
        )
        assertArrayEquals(
            bytes(0x0E, 0, 0, 0, 0x02, 0, 0x16, 0x10, 0x06, 0, 0, 0, 0x0B, 0x00),
            Ptp.data(Ptp.SET_DEVICE_PROP_VALUE, 6, Ptp.u16(0x0B)),
        )
        val response = Ptp.parse(bytes(0x0C, 0, 0, 0, 0x03, 0, 0x01, 0x20, 0x06, 0, 0, 0))
        assertEquals(Ptp.TYPE_RESPONSE, response.type)
        assertEquals(Ptp.OK, response.code)
        assertEquals(6, response.transaction)
    }

    @Test fun ptp_strings() {
        assertArrayEquals(bytes(0x03, 0x41, 0, 0x42, 0, 0, 0), Ptp.string("AB"))
        assertArrayEquals(bytes(0), Ptp.string(""))
        assertEquals("KODACHROME 64", Ptp.readString(Ptp.string("KODACHROME 64")))
        assertEquals("", Ptp.readString(bytes(0x01, 0, 0))) // an unnamed slot
    }

    private val classicChromeSlot = mapOf(
        BankCodec.DYNAMIC_RANGE to 0xFFFF, BankCodec.DR_PRIORITY to 0, BankCodec.FILM_SIM to 0x0B,
        BankCodec.GRAIN to 6, BankCodec.COLOR_CHROME to 3, BankCodec.COLOR_CHROME_BLUE to 2,
        BankCodec.WB_MODE to 0x8021, BankCodec.WB_RED to BankCodec.u16(2), BankCodec.WB_BLUE to BankCodec.u16(-4),
        BankCodec.KELVIN to 5500, BankCodec.HIGHLIGHT to BankCodec.u16(-10), BankCodec.SHADOW to 5,
        BankCodec.COLOR to 0x14, BankCodec.SHARPNESS to BankCodec.u16(-20), BankCodec.NOISE_REDUCTION to 0x8000,
        BankCodec.CLARITY to BankCodec.u16(-30),
    )

    @Test fun decodes_a_slot() {
        val r = BankCodec.decode("STREET", classicChromeSlot)!!
        assertEquals(FilmSim.CLASSIC_CHROME, r.filmSim)
        assertEquals(DynamicRange.AUTO, r.dynamicRange)
        assertEquals(Grain.OFF, r.grain) // read back as 6
        assertEquals(Effect.STRONG, r.colorChrome)
        assertEquals(Effect.WEAK, r.colorChromeBlue)
        assertEquals(WhiteBalance.AUTO_AMBIENCE, r.whiteBalance)
        assertEquals(2, r.wbRed)
        assertEquals(-4, r.wbBlue)
        assertEquals(-1.0, r.highlight, 0.0)
        assertEquals(0.5, r.shadow, 0.0)
        assertEquals(2, r.color)
        assertEquals(-2, r.sharpness)
        assertEquals(-4, r.noiseReduction)
        assertEquals(-3, r.clarity)
    }

    @Test fun plan_writes_only_changes_in_the_cameras_order() {
        val target = BankCodec.decode("STREET", classicChromeSlot)!!.copy(color = 3, whiteBalance = WhiteBalance.KELVIN, kelvin = 6200)
        val plan = BankCodec.plan(classicChromeSlot, target).map { it.property }
        assertEquals(listOf(BankCodec.WB_MODE, BankCodec.KELVIN, BankCodec.WB_RED, BankCodec.WB_BLUE, BankCodec.COLOR), plan)
        assertTrue(BankCodec.plan(classicChromeSlot, BankCodec.decode("STREET", classicChromeSlot)!!).isEmpty())
    }

    @Test fun priority_is_turned_off_before_dynamic_range() {
        val slot = classicChromeSlot + (BankCodec.DR_PRIORITY to DrPriority.STRONG.code)
        val target = Recipe("X", FilmSim.CLASSIC_CHROME, dynamicRange = DynamicRange.DR400)
        val plan = BankCodec.plan(slot, target)
        assertEquals(BankCodec.DR_PRIORITY, plan[0].property)
        assertEquals(0, plan[0].value)
        assertEquals(BankCodec.DYNAMIC_RANGE, plan[1].property)
        assertEquals(400, plan[1].value)
    }

    @Test fun monochrome_skips_color_and_writes_toning() {
        val target = Recipe("NOIR", FilmSim.ACROS_R, monoWarmCool = 3, monoMagentaGreen = -1, highlight = 1.0)
        val plan = BankCodec.plan(classicChromeSlot, target)
        val props = plan.map { it.property }
        assertFalse(BankCodec.COLOR in props)
        assertEquals(30, plan.first { it.property == BankCodec.MONO_WC }.value)
        assertEquals(BankCodec.u16(-10), plan.first { it.property == BankCodec.MONO_MG }.value)
        assertTrue(props.indexOf(BankCodec.FILM_SIM) < props.indexOf(BankCodec.MONO_WC))
        assertTrue(props.indexOf(BankCodec.FILM_SIM) < props.indexOf(BankCodec.HIGHLIGHT))
    }

    @Test fun verification_compares_by_meaning() {
        val target = BankCodec.decode("STREET", classicChromeSlot)!!
        assertTrue(BankCodec.mismatches(classicChromeSlot + (BankCodec.GRAIN to 7), target).isEmpty())
        assertEquals(listOf("Color"), BankCodec.mismatches(classicChromeSlot + (BankCodec.COLOR to 0x0A), target))
    }

    @Test fun undo_puts_grain_off_back_as_one() {
        val now = classicChromeSlot + (BankCodec.GRAIN to 2) + (BankCodec.FILM_SIM to 0x14)
        val writes = BankCodec.undo(classicChromeSlot, now)
        assertEquals(listOf(BankCodec.FILM_SIM to 0x0B, BankCodec.GRAIN to 1), writes.map { it.property to it.value })
    }

    @Test fun recipe_values_are_checked() {
        assertTrue(Recipe("OK", FilmSim.PROVIA, highlight = -1.5, shadow = 4.0).problems().isEmpty())
        assertFalse(Recipe("BAD", FilmSim.PROVIA, highlight = 0.3).problems().isEmpty())
        assertFalse(Recipe("BAD", FilmSim.PROVIA, monoWarmCool = 2).problems().isEmpty())
        assertEquals("KODAK PORTRA 400", Recipe("Kodak Portra 400", FilmSim.PROVIA).cameraName)
    }

    @Test fun every_library_recipe_fits_the_camera() {
        val all = com.fujiapp.remote.recipe.RecipeLibrary.all
        assertTrue(all.size >= 25)
        for (r in all) {
            assertEquals("${r.title}: ${r.recipe.problems()}", emptyList<String>(), r.recipe.problems())
            assertTrue(r.title, r.recipe.cameraName.length in 1..Recipe.NAME_MAX)
            assertTrue(r.title, r.palette.isNotEmpty() && r.look.isNotBlank() && r.how.isNotBlank())
        }
        assertEquals(all.size, all.map { it.recipe.cameraName }.toSet().size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }
}
