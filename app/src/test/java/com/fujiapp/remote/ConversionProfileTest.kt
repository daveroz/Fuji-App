package com.fujiapp.remote

import com.fujiapp.remote.recipe.ConversionProfile
import com.fujiapp.remote.recipe.DynamicRange
import com.fujiapp.remote.recipe.Effect
import com.fujiapp.remote.recipe.FilmSim
import com.fujiapp.remote.recipe.Grain
import com.fujiapp.remote.recipe.Recipe
import com.fujiapp.remote.recipe.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionProfileTest {
    /** A 625-byte read like an X-Processor 5 body's: count 29, IOPCode "FF189504" (length byte 8), 29 cells. */
    private fun read625(): ByteArray {
        val b = ByteArray(0x1FD + 29 * 4)
        b[0] = 29
        b[2] = 8
        "FF189504".forEachIndexed { i, ch -> b[3 + i * 2] = ch.code.toByte() }
        return b
    }

    private val recipe = Recipe(
        "KODACHROME 64", FilmSim.CLASSIC_CHROME, Grain.WEAK_SMALL, Effect.STRONG, Effect.WEAK,
        WhiteBalance.KELVIN, kelvin = 5600, wbRed = 2, wbBlue = -5, dynamicRange = DynamicRange.DR200,
        highlight = -0.5, shadow = 1.5, color = 2, sharpness = 1, noiseReduction = -4, clarity = 2,
    )

    @Test fun becomes_the_629_byte_host_write_with_the_recipes_cells() {
        val out = ConversionProfile.apply(read625(), recipe)
        assertEquals(629, out.size)
        assertEquals(29, out[0].toInt())
        assertEquals(9, out[2].toInt())
        assertEquals(30, ConversionProfile.cells(out))
        // Dynamic range sits at byte 533 and film simulation at 541 in every profile seen.
        assertEquals(200, out[533].toInt() and 0xFF or ((out[534].toInt() and 0xFF) shl 8))
        assertEquals(0x0B, out[541].toInt())
        assertEquals(7, ConversionProfile.get(out, ConversionProfile.FILE_TYPE))
        assertEquals(2, ConversionProfile.get(out, ConversionProfile.WB_SOURCE))
        assertEquals(0x8007, ConversionProfile.get(out, ConversionProfile.WB_MODE))
        assertEquals(5600, ConversionProfile.get(out, ConversionProfile.KELVIN))
        assertEquals(-5, ConversionProfile.get(out, ConversionProfile.WB_BLUE))
        assertEquals(-5, ConversionProfile.get(out, ConversionProfile.HIGHLIGHT))
        assertEquals(15, ConversionProfile.get(out, ConversionProfile.SHADOW))
        assertEquals(0x8000, ConversionProfile.get(out, ConversionProfile.NOISE_REDUCTION))
        assertEquals(20, ConversionProfile.get(out, ConversionProfile.CLARITY))
        assertEquals(2, ConversionProfile.get(out, ConversionProfile.COLOR_CHROME_BLUE))
        assertEquals(0, ConversionProfile.get(out, 29))
    }

    @Test fun auto_dynamic_range_keeps_the_rafs_own_and_grain_off_reads_back_as_seven() {
        val read = read625()
        read[533] = 100
        val r = recipe.copy(dynamicRange = DynamicRange.AUTO, grain = Grain.OFF)
        val out = ConversionProfile.apply(read, r)
        assertEquals(100, ConversionProfile.get(out, ConversionProfile.DYNAMIC_RANGE))
        val back = out.copyOf()
        back[0x1FD + 4 * ConversionProfile.GRAIN] = 7
        assertTrue(ConversionProfile.mismatches(out, back, r).isEmpty())
    }
}
