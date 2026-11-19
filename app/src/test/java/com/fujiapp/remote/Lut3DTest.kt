package com.fujiapp.remote

import com.fujiapp.remote.preview.FilmLuts
import com.fujiapp.remote.preview.Lut3D
import com.fujiapp.remote.recipe.FilmSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

class Lut3DTest {
    private fun lookup(lut: Lut3D, r: Float, g: Float, b: Float) = FloatArray(3).also { lut.apply(r, g, b, it) }

    @Test fun identity_changes_nothing() {
        val lut = Lut3D.identity(17)
        val random = Random(7)
        repeat(500) {
            val r = random.nextFloat(); val g = random.nextFloat(); val b = random.nextFloat()
            val out = lookup(lut, r, g, b)
            assertEquals(r, out[0], 1e-5f); assertEquals(g, out[1], 1e-5f); assertEquals(b, out[2], 1e-5f)
        }
    }

    @Test fun tetrahedral_interpolation_is_exact_for_an_affine_map() {
        // out = M·in + c, sampled on a coarse grid: tetrahedral interpolation reproduces it everywhere.
        fun f(r: Float, g: Float, b: Float) = floatArrayOf(
            0.8f * r + 0.1f * g + 0.05f * b + 0.02f,
            0.1f * r + 0.7f * g + 0.2f * b,
            0.3f * r - 0.1f * g + 0.75f * b + 0.04f,
        )
        val size = 5
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            f(r / 4f, g / 4f, b / 4f).forEach { data[i++] = it }
        }
        val lut = Lut3D(size, data)
        val random = Random(11)
        repeat(500) {
            val r = random.nextFloat(); val g = random.nextFloat(); val b = random.nextFloat()
            val want = f(r, g, b)
            val got = lookup(lut, r, g, b)
            for (k in 0..2) assertEquals(want[k], got[k], 1e-5f)
        }
    }

    @Test fun reads_a_cube_file() {
        val cube = """
            # a 2-point table that swaps red and blue
            TITLE "swap"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 1 1 1
            0 0 0
            0 0 1
            0 1 0
            0 1 1
            1 0 0
            1 0 1
            1 1 0
            1 1 1
        """.trimIndent()
        val lut = Lut3D.parseCube(cube)
        assertEquals(2, lut.size)
        val out = lookup(lut, 0.9f, 0.5f, 0.2f)
        assertEquals(0.2f, out[0], 1e-5f); assertEquals(0.5f, out[1], 1e-5f); assertEquals(0.9f, out[2], 1e-5f)
    }

    @Test fun refuses_what_isn_t_a_3d_cube() {
        assertThrows(IllegalArgumentException::class.java) { Lut3D.parseCube("LUT_1D_SIZE 4\n0 0 0") }
        assertThrows(IllegalArgumentException::class.java) { Lut3D.parseCube("0 0 0") }
        assertThrows(IllegalArgumentException::class.java) { Lut3D.parseCube("LUT_3D_SIZE 2\n0 0 0\n1 1 1") }
    }

    @Test fun asset_names_map_to_film_simulations() {
        assertEquals(FilmSim.CLASSIC_CHROME, FilmLuts.simFor("CLASSIC_CHROME.cube"))
        assertEquals(FilmSim.ACROS_YE, FilmLuts.simFor("acros_ye.CUBE"))
        assertNull(FilmLuts.simFor("classic chrome.cube"))
        assertNull(FilmLuts.simFor("PROVIA.png"))
    }
}
