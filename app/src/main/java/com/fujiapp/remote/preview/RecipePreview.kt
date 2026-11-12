package com.fujiapp.remote.preview

import com.fujiapp.remote.recipe.DynamicRange
import com.fujiapp.remote.recipe.Effect
import com.fujiapp.remote.recipe.FilmSim
import com.fujiapp.remote.recipe.Grain
import com.fujiapp.remote.recipe.Recipe
import com.fujiapp.remote.recipe.WhiteBalance
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An image ready for previewing: its pixels (ARGB) plus what every render reuses: brightness, two
 * blurred copies of it (for clarity and sharpness) and two grain textures.
 */
class PreviewSource(val width: Int, val height: Int, val pixels: IntArray) {
    internal val luma = FloatArray(pixels.size)
    internal val blurLarge: FloatArray
    internal val blurSmall: FloatArray
    internal val grainFine = FloatArray(pixels.size)
    internal val grainCoarse: FloatArray

    init {
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        blurLarge = Blur.box(luma, width, height, max(2, max(width, height) / 70), passes = 3)
        blurSmall = Blur.box(luma, width, height, 1, passes = 2)
        val random = Random(2049)
        for (i in grainFine.indices) grainFine[i] = (random.nextGaussian().toFloat()).coerceIn(-2.5f, 2.5f) / 2.5f
        val coarse = Blur.box(grainFine, width, height, 1, passes = 1)
        var peak = 0.0001f
        for (v in coarse) peak = max(peak, kotlin.math.abs(v))
        grainCoarse = FloatArray(coarse.size) { coarse[it] / peak }
    }
}

/**
 * An approximation of how a recipe renders, for trying ideas on the phone. It mimics each film
 * simulation's character (saturation, contrast, color shifts, split toning) and applies the
 * recipe's white balance, tones, color, effects, clarity, sharpness and grain on top. It isn't
 * Fujifilm's processing: the camera's own result will differ.
 */
object RecipePreview {

    /** A film simulation's character, in the preview's simple terms. */
    private class Look(
        val saturation: Float,
        val contrast: Float,
        /** 3×3 color mix, row-major, applied before saturation. */
        val mix: FloatArray? = null,
        val shadowTint: FloatArray = ZERO,
        val highlightTint: FloatArray = ZERO,
        /** Monochrome: how much red, green and blue make the grey (the filter). */
        val grey: FloatArray? = null,
        val sepia: Boolean = false,
    )

    private val ZERO = floatArrayOf(0f, 0f, 0f)
    private fun t(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    private val PLAIN = t(0.299f, 0.587f, 0.114f)
    private val YELLOW = t(0.36f, 0.56f, 0.08f)
    private val RED = t(0.62f, 0.33f, 0.05f)
    private val GREEN = t(0.24f, 0.70f, 0.06f)

    private fun look(sim: FilmSim): Look = when (sim) {
        FilmSim.PROVIA -> Look(1.0f, 0.10f)
        FilmSim.VELVIA -> Look(1.45f, 0.24f, mix = floatArrayOf(1.02f, -0.03f, 0.01f, -0.02f, 1.04f, -0.02f, 0.01f, -0.04f, 1.03f))
        FilmSim.ASTIA -> Look(1.12f, 0.02f, highlightTint = t(0.008f, 0.004f, 0f))
        FilmSim.PRO_NEG_HI -> Look(0.95f, 0.10f)
        FilmSim.PRO_NEG_STD -> Look(0.85f, -0.03f)
        FilmSim.CLASSIC_CHROME -> Look(
            0.72f, 0.18f,
            mix = floatArrayOf(0.92f, 0.06f, 0.02f, 0.02f, 0.96f, 0.02f, 0.02f, 0.10f, 0.88f),
            shadowTint = t(-0.012f, 0f, 0.016f),
        )
        FilmSim.ETERNA -> Look(0.62f, -0.15f, shadowTint = t(-0.012f, 0.006f, 0.012f))
        FilmSim.CLASSIC_NEG -> Look(
            1.0f, 0.28f,
            mix = floatArrayOf(1.0f, -0.04f, 0.04f, 0.0f, 0.98f, 0.02f, 0.08f, 0.06f, 0.86f),
            shadowTint = t(-0.02f, 0.006f, 0.022f),
            highlightTint = t(0.022f, 0.01f, -0.022f),
        )
        FilmSim.BLEACH_BYPASS -> Look(0.35f, 0.38f)
        FilmSim.NOSTALGIC_NEG -> Look(0.95f, 0.0f, shadowTint = t(0.004f, 0f, -0.004f), highlightTint = t(0.035f, 0.015f, -0.03f))
        FilmSim.REALA_ACE -> Look(1.05f, 0.12f)
        FilmSim.MONOCHROME -> Look(0f, 0.06f, grey = PLAIN)
        FilmSim.MONOCHROME_YE -> Look(0f, 0.06f, grey = YELLOW)
        FilmSim.MONOCHROME_R -> Look(0f, 0.06f, grey = RED)
        FilmSim.MONOCHROME_G -> Look(0f, 0.06f, grey = GREEN)
        FilmSim.ACROS -> Look(0f, 0.16f, grey = PLAIN)
        FilmSim.ACROS_YE -> Look(0f, 0.16f, grey = YELLOW)
        FilmSim.ACROS_R -> Look(0f, 0.16f, grey = RED)
        FilmSim.ACROS_G -> Look(0f, 0.16f, grey = GREEN)
        FilmSim.SEPIA -> Look(0f, 0.04f, grey = PLAIN, sepia = true)
    }

    /** White balance relative to a daylight-balanced picture, as red, green and blue gains. */
    private fun whiteBalance(r: Recipe): FloatArray {
        val kelvin = when (r.whiteBalance) {
            WhiteBalance.KELVIN -> r.kelvin.toFloat()
            WhiteBalance.SHADE -> 7000f
            WhiteBalance.INCANDESCENT -> 3100f
            WhiteBalance.FLUORESCENT_1, WhiteBalance.FLUORESCENT_2, WhiteBalance.FLUORESCENT_3 -> 4200f
            WhiteBalance.UNDERWATER -> 8500f
            WhiteBalance.AUTO_AMBIENCE -> 5900f
            else -> 5500f
        }
        val k = ln(kelvin / 5500f)
        var red = exp(0.55f * k) * (1f + 0.03f * r.wbRed)
        var green = exp(0.04f * k) * (1f - 0.006f * (r.wbRed + r.wbBlue))
        var blue = exp(-0.75f * k) * (1f + 0.03f * r.wbBlue)
        if (r.whiteBalance in setOf(WhiteBalance.FLUORESCENT_1, WhiteBalance.FLUORESCENT_2, WhiteBalance.FLUORESCENT_3)) green *= 1.04f
        val norm = 0.299f * red + 0.587f * green + 0.114f * blue
        red /= norm; green /= norm; blue /= norm
        return floatArrayOf(red, green, blue)
    }

    private fun bell(v: Float, center: Float, width: Float): Float {
        val d = (v - center) / width
        return exp(-d * d)
    }

    /** Render [recipe] onto [out] (same size as the source). */
    fun render(src: PreviewSource, recipe: Recipe, out: IntArray) {
        val look = look(recipe.filmSim)
        val wb = whiteBalance(recipe)
        val mono = look.grey != null
        val saturation = if (mono) 0f else look.saturation * (1f + 0.12f * recipe.color)
        val contrast = look.contrast
        val compress = when (recipe.dynamicRange) {
            DynamicRange.DR400 -> 0.16f
            DynamicRange.DR200, DynamicRange.AUTO -> 0.08f
            DynamicRange.DR100 -> 0f
        }
        val hi = recipe.highlight.toFloat()
        val sh = recipe.shadow.toFloat()
        val clarity = recipe.clarity * 0.045f
        val sharp = recipe.sharpness * 0.22f
        val smooth = max(0, recipe.noiseReduction) * 0.06f
        val cce = when (recipe.colorChrome) { Effect.OFF -> 0f; Effect.WEAK -> 0.14f; Effect.STRONG -> 0.26f }
        val ccb = when (recipe.colorChromeBlue) { Effect.OFF -> 0f; Effect.WEAK -> 0.14f; Effect.STRONG -> 0.26f }
        val grainAmp = when (recipe.grain) {
            Grain.OFF -> 0f
            Grain.WEAK_SMALL, Grain.WEAK_LARGE -> 0.032f
            Grain.STRONG_SMALL, Grain.STRONG_LARGE -> 0.058f
        }
        val grainTex = if (recipe.grain == Grain.WEAK_LARGE || recipe.grain == Grain.STRONG_LARGE) src.grainCoarse else src.grainFine
        val wc = recipe.monoWarmCool * 0.0045f
        val mg = recipe.monoMagentaGreen * 0.004f
        val mix = look.mix
        val grey = look.grey

        fun curve(x: Float): Float {
            var v = x
            v += contrast * (v - 0.5f) * 4f * v * (1f - v)
            v += 0.045f * hi * bell(v, 0.80f, 0.22f) * (v - 0.45f) * 2f
            v -= 0.045f * sh * bell(v, 0.22f, 0.20f) * (0.55f - v) * 2f
            if (compress > 0f && v > 0.6f) {
                val over = (v - 0.6f) / 0.4f
                v -= compress * over * over * 0.4f
            }
            return v
        }

        for (i in src.pixels.indices) {
            val p = src.pixels[i]
            var r = ((p shr 16) and 0xFF) / 255f * wb[0]
            var g = ((p shr 8) and 0xFF) / 255f * wb[1]
            var b = (p and 0xFF) / 255f * wb[2]

            if (mix != null) {
                val nr = mix[0] * r + mix[1] * g + mix[2] * b
                val ng = mix[3] * r + mix[4] * g + mix[5] * b
                val nb = mix[6] * r + mix[7] * g + mix[8] * b
                r = nr; g = ng; b = nb
            }
            if (grey != null) {
                val l = grey[0] * r + grey[1] * g + grey[2] * b
                r = l; g = l; b = l
            }
            r = curve(r); g = curve(g); b = curve(b)

            var l = 0.299f * r + 0.587f * g + 0.114f * b
            if (!mono) {
                r = l + (r - l) * saturation
                g = l + (g - l) * saturation
                b = l + (b - l) * saturation
                val lo = (1f - l) * (1f - l)
                val up = l * l
                r += look.shadowTint[0] * lo + look.highlightTint[0] * up
                g += look.shadowTint[1] * lo + look.highlightTint[1] * up
                b += look.shadowTint[2] * lo + look.highlightTint[2] * up

                // Color Chrome: deepen strongly colored areas so they keep their tone and detail.
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                if (cce > 0f && chroma > 0.25f) {
                    val k = 1f - cce * ((chroma - 0.25f) / 0.75f).coerceIn(0f, 1f) * 0.35f
                    r = l + (r - l) * (2f - k) - (1f - k) * r * 0.5f
                    g = l + (g - l) * (2f - k) - (1f - k) * g * 0.5f
                    b = l + (b - l) * (2f - k) - (1f - k) * b * 0.5f
                }
                // FX Blue: the same for blues.
                if (ccb > 0f && b > r && b > g) {
                    val blueness = ((b - max(r, g)) / (b + 0.001f)).coerceIn(0f, 1f)
                    val k = ccb * blueness
                    r *= 1f - k * 0.45f
                    g *= 1f - k * 0.30f
                    b *= 1f - k * 0.10f
                }
            } else if (look.sepia) {
                r = l * 1.08f; g = l * 0.97f; b = l * 0.80f
            } else if (wc != 0f || mg != 0f) {
                r += wc - mg * 0.5f
                g += mg
                b += -wc - mg * 0.5f
            }

            l = 0.299f * r + 0.587f * g + 0.114f * b
            val detail = clarity * (src.luma[i] - src.blurLarge[i]) * 2.2f +
                sharp * (src.luma[i] - src.blurSmall[i]) * 2f -
                smooth * (src.luma[i] - src.blurSmall[i])
            val grain = if (grainAmp > 0f) grainTex[i] * grainAmp * (0.25f + 3f * l * (1f - l)) else 0f
            val add = detail + grain
            r += add; g += add; b += add

            out[i] = (0xFF shl 24) or (pack(r) shl 16) or (pack(g) shl 8) or pack(b)
        }
    }

    private fun pack(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)
}

/** Separable box blur on a float image (several passes approximate a Gaussian). */
internal object Blur {
    fun box(src: FloatArray, w: Int, h: Int, radius: Int, passes: Int): FloatArray {
        var a = src.copyOf()
        var b = FloatArray(src.size)
        repeat(passes) {
            horizontal(a, b, w, h, radius)
            vertical(b, a, w, h, radius)
        }
        return a
    }

    private fun horizontal(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = sum * norm
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun vertical(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += src[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum * norm
                sum += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }
}
