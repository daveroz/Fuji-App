package com.fujiapp.remote

import com.fujiapp.remote.preview.PreviewSource
import com.fujiapp.remote.preview.RecipePreview
import com.fujiapp.remote.preview.SampleScene
import com.fujiapp.remote.preview.Vectorscope
import com.fujiapp.remote.recipe.FilmSim
import com.fujiapp.remote.recipe.Recipe
import com.fujiapp.remote.recipe.WhiteBalance
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTest {
    private val w = 240
    private val h = 160
    private val source = PreviewSource(w, h, SampleScene.pixels(w, h))

    private fun render(r: Recipe) = IntArray(w * h).also { RecipePreview.render(source, r, it) }
    private fun ch(p: Int, s: Int) = (p shr s) and 0xFF
    private fun saturation(px: IntArray) = px.map { maxOf(ch(it, 16), ch(it, 8), ch(it, 0)) - minOf(ch(it, 16), ch(it, 8), ch(it, 0)) }.average()
    private fun blueMinusRed(px: IntArray) = px.map { ch(it, 0) - ch(it, 16) }.average()

    @Test fun monochrome_without_toning_is_grey() {
        assertTrue(render(Recipe("A", FilmSim.ACROS)).all { ch(it, 16) == ch(it, 8) && ch(it, 8) == ch(it, 0) })
    }

    @Test fun velvia_is_more_saturated_than_eterna() {
        assertTrue(saturation(render(Recipe("V", FilmSim.VELVIA))) > saturation(render(Recipe("E", FilmSim.ETERNA))) * 1.5)
    }

    @Test fun low_kelvin_turns_the_picture_blue() {
        val neutral = blueMinusRed(render(Recipe("N", FilmSim.PROVIA)))
        val tungsten = blueMinusRed(render(Recipe("T", FilmSim.PROVIA, whiteBalance = WhiteBalance.KELVIN, kelvin = 3200)))
        assertTrue(tungsten > neutral + 20)
    }

    @Test fun skin_tones_sit_on_the_vectorscope_skin_line() {
        for (rgb in SampleScene.SKIN) {
            val r = ((rgb shr 16) and 0xFF) / 255.0
            val g = ((rgb shr 8) and 0xFF) / 255.0
            val b = (rgb and 0xFF) / 255.0
            val cb = -0.1146 * r - 0.3854 * g + 0.5 * b
            val cr = 0.5 * r - 0.4542 * g - 0.0458 * b
            val angle = Math.toDegrees(kotlin.math.atan2(cr, cb))
            assertTrue("skin #%06X at %.1f°".format(rgb, angle), kotlin.math.abs(angle - Vectorscope.SKIN_LINE_DEG) < 12)
        }
        val out = IntArray(64 * 64)
        Vectorscope.render(source.pixels, 64, out)
        assertTrue(out.any { (it ushr 24) != 0 })
    }
}
