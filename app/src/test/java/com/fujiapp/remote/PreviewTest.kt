package com.fujiapp.remote

import com.fujiapp.remote.preview.FilmLuts
import com.fujiapp.remote.preview.Lut3D
import com.fujiapp.remote.preview.PreviewSource
import com.fujiapp.remote.preview.RecipePreview
import com.fujiapp.remote.preview.SampleScene
import com.fujiapp.remote.preview.Vectorscope
import com.fujiapp.remote.recipe.DrPriority
import com.fujiapp.remote.recipe.DynamicRange
import com.fujiapp.remote.recipe.Effect
import com.fujiapp.remote.recipe.FilmSim
import com.fujiapp.remote.recipe.Grain
import com.fujiapp.remote.recipe.Recipe
import com.fujiapp.remote.recipe.WhiteBalance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PreviewTest {
    private val w = 240
    private val h = 160
    private val chart = SampleScene.pixels(w, h)
    private val source = PreviewSource(w, h, chart)

    private fun render(r: Recipe, src: PreviewSource = source) = IntArray(w * h).also { RecipePreview.render(src, r, it) }
    private fun ch(p: Int, s: Int) = (p shr s) and 0xFF
    private fun saturation(px: IntArray) = px.map { maxOf(ch(it, 16), ch(it, 8), ch(it, 0)) - minOf(ch(it, 16), ch(it, 8), ch(it, 0)) }.average()
    private fun blueMinusRed(px: IntArray) = px.map { ch(it, 0) - ch(it, 16) }.average()
    private fun diffs(a: IntArray, b: IntArray) = a.indices.flatMap { i -> listOf(16, 8, 0).map { s -> abs(ch(a[i], s) - ch(b[i], s)) } }

    /** The grey ramp's row on the chart, and its ends. */
    private val rampRow = h - (w * 0.025f).toInt() - 2
    private val rampMargin = (w * 0.025f).toInt()
    private fun rampEnds(px: IntArray) = px[rampRow * w + rampMargin] to px[rampRow * w + w - rampMargin - 1]

    @Test fun monochrome_without_toning_is_grey() {
        assertTrue(render(Recipe("A", FilmSim.ACROS)).all { ch(it, 16) == ch(it, 8) && ch(it, 8) == ch(it, 0) })
    }

    @Test fun velvia_is_more_saturated_than_eterna() {
        assertTrue(saturation(render(Recipe("V", FilmSim.VELVIA))) > saturation(render(Recipe("E", FilmSim.ETERNA))) * 1.5)
    }

    @Test fun low_kelvin_turns_the_picture_blue() {
        val neutral = blueMinusRed(render(Recipe("N", FilmSim.PROVIA)))
        val tungsten = blueMinusRed(render(Recipe("T", FilmSim.PROVIA, whiteBalance = WhiteBalance.KELVIN, kelvin = 3200)))
        assertTrue("tungsten $tungsten vs $neutral", tungsten > neutral + 20)
    }

    @Test fun white_balance_keeps_grey_as_bright() {
        // Gains in linear light, normalised on luminance: mid grey stays mid grey under a warm or cool balance.
        val grey = IntArray(w * h) { 0xFF777777.toInt() }
        val src = PreviewSource(w, h, grey)
        for (k in listOf(3200, 8000)) {
            val p = render(Recipe("K", FilmSim.PROVIA, whiteBalance = WhiteBalance.KELVIN, kelvin = k), src)[0]
            val luma = 0.299 * ch(p, 16) + 0.587 * ch(p, 8) + 0.114 * ch(p, 0)
            assertTrue("$k K: luma $luma", abs(luma - 0x77) < 8)
        }
    }

    @Test fun soft_tones_and_dr400_keep_black_black_and_white_white() {
        for (r in listOf(
            Recipe("S", FilmSim.PROVIA, highlight = -2.0, shadow = -2.0, dynamicRange = DynamicRange.DR400),
            Recipe("P", FilmSim.ETERNA, drPriority = DrPriority.STRONG, dynamicRange = DynamicRange.DR400),
        )) {
            val (black, white) = rampEnds(render(r))
            assertTrue("${r.name} black %06X".format(black and 0xFFFFFF), ch(black, 8) <= 2)
            assertTrue("${r.name} white %06X".format(white and 0xFFFFFF), ch(white, 8) >= 253)
        }
    }

    @Test fun dr400_flattens_the_highlights() {
        val dr100 = render(Recipe("A", FilmSim.PROVIA))
        val dr400 = render(Recipe("B", FilmSim.PROVIA, dynamicRange = DynamicRange.DR400))
        // Three quarters along the ramp: an upper mid tone, darker with more headroom.
        val x = rampMargin + (w - 2 * rampMargin) * 3 / 4
        assertTrue(ch(dr400[rampRow * w + x], 8) < ch(dr100[rampRow * w + x], 8))
    }

    @Test fun d_range_priority_sets_the_tones_itself() {
        val auto = render(Recipe("A", FilmSim.PROVIA, drPriority = DrPriority.WEAK))
        val hard = render(Recipe("B", FilmSim.PROVIA, drPriority = DrPriority.WEAK, highlight = 4.0, shadow = 4.0))
        assertArrayEquals(auto, hard)
    }

    @Test fun a_camera_jpeg_rendered_with_its_own_settings_comes_back_unchanged() {
        val shots = listOf(
            Recipe("CC", FilmSim.CLASSIC_CHROME, whiteBalance = WhiteBalance.KELVIN, kelvin = 4800, wbRed = 2, wbBlue = -4,
                dynamicRange = DynamicRange.DR400, highlight = -1.0, shadow = 1.5, color = -2),
            Recipe("CN", FilmSim.CLASSIC_NEG, whiteBalance = WhiteBalance.DAYLIGHT, wbRed = 3, wbBlue = -5, highlight = 0.5, shadow = 2.0, color = 3),
            Recipe("VE", FilmSim.VELVIA, dynamicRange = DynamicRange.DR200),
            Recipe("NN", FilmSim.NOSTALGIC_NEG, drPriority = DrPriority.STRONG),
            Recipe("AC", FilmSim.ACROS_R, highlight = 1.0, shadow = -1.0),
        )
        for (shot in shots) {
            val asShot = render(shot)
            val back = render(shot, PreviewSource(w, h, asShot, shot))
            val d = diffs(asShot, back)
            assertTrue("${shot.name}: mean ${d.average()}", d.average() < 0.6)
            assertTrue("${shot.name}: max ${d.max()}", d.max() <= 4)
        }
    }

    @Test fun a_camera_jpeg_takes_a_new_recipe_from_neutral() {
        // Shot in Classic Chrome; asked for Velvia: closer to Velvia on a neutral picture than Velvia stacked on Classic Chrome.
        val shot = Recipe("CC", FilmSim.CLASSIC_CHROME, color = -2)
        val asShot = render(shot)
        val velvia = Recipe("V", FilmSim.VELVIA)
        val target = render(velvia)
        val compensated = render(velvia, PreviewSource(w, h, asShot, shot))
        val stacked = render(velvia, PreviewSource(w, h, asShot))
        assertTrue(diffs(target, compensated).average() * 2 < diffs(target, stacked).average())
    }

    @Test fun effects_already_in_the_picture_aren_t_added_again() {
        val shot = Recipe("G", FilmSim.PROVIA, grain = Grain.STRONG_SMALL, colorChrome = Effect.STRONG, sharpness = 2, clarity = 3)
        val asShot = render(shot)
        val back = render(shot, PreviewSource(w, h, asShot, shot))
        // Grain, color chrome, sharpness and clarity are relative to the shot's: none added, so it comes back as it was.
        assertTrue(diffs(asShot, back).average() < 0.6)
    }

    @Test fun a_film_lut_replaces_the_parametric_look() {
        try {
            FilmLuts.put(FilmSim.PROVIA, Lut3D.identity(17))
            // An identity LUT at neutral settings changes nothing.
            val out = render(Recipe("L", FilmSim.PROVIA))
            assertTrue(diffs(chart, out).max() <= 1)
            // The rest of the recipe still applies on top.
            assertTrue(blueMinusRed(render(Recipe("T", FilmSim.PROVIA, whiteBalance = WhiteBalance.KELVIN, kelvin = 3200))) > blueMinusRed(out) + 20)
        } finally {
            FilmLuts.remove(FilmSim.PROVIA)
        }
        assertEquals(null, FilmLuts.get(FilmSim.PROVIA))
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

    @Test fun every_film_simulation_keeps_skin_near_the_skin_line() {
        // A sanity metric over the renderer: the skin row stays within reach of the line in every color look.
        val margin = Math.round(w * 0.025f)
        val gap = Math.round(w * 0.012f)
        val cellH = (Math.round(h * 0.62f) - margin - 3 * gap) / 4
        val skinRow = margin + 4 * (cellH + gap) + gap + Math.round(h * 0.17f) / 2
        for (sim in FilmSim.entries.filter { !it.mono }) {
            val px = render(Recipe("S", sim))
            val angles = (0 until w step 4).map { px[skinRow * w + it] }.filter { (maxOf(ch(it, 16), ch(it, 8), ch(it, 0)) - minOf(ch(it, 16), ch(it, 8), ch(it, 0))) > 20 }.map { p ->
                val r = ch(p, 16) / 255.0; val g = ch(p, 8) / 255.0; val b = ch(p, 0) / 255.0
                Math.toDegrees(kotlin.math.atan2(0.5 * r - 0.4542 * g - 0.0458 * b, -0.1146 * r - 0.3854 * g + 0.5 * b))
            }
            assertTrue("$sim: no skin found", angles.isNotEmpty())
            assertTrue("$sim: skin at ${angles.average()}°", abs(angles.average() - Vectorscope.SKIN_LINE_DEG) < 20)
        }
    }
}
