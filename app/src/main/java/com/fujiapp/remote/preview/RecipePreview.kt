package com.fujiapp.remote.preview

import com.fujiapp.remote.recipe.DrPriority
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
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * An image ready for previewing: its pixels (ARGB) plus what renders reuse: brightness, two
 * blurred copies of it (for clarity and sharpness) and two grain textures, each made the first
 * time a recipe needs it.
 */
class PreviewSource(val width: Int, val height: Int, val pixels: IntArray) {
    internal val luma: FloatArray by lazy {
        FloatArray(pixels.size) { i ->
            val p = pixels[i]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
    }
    internal val blurLarge: FloatArray by lazy { Blur.box(luma, width, height, max(2, max(width, height) / 70), passes = 3) }
    internal val blurSmall: FloatArray by lazy { Blur.box(luma, width, height, 1, passes = 2) }
    internal val grainFine: FloatArray by lazy {
        val random = Random(2049)
        FloatArray(pixels.size) { (random.nextGaussian().toFloat()).coerceIn(-2.5f, 2.5f) / 2.5f }
    }
    internal val grainCoarse: FloatArray by lazy {
        val coarse = Blur.box(grainFine, width, height, 1, passes = 1)
        var peak = 0.0001f
        for (v in coarse) peak = max(peak, kotlin.math.abs(v))
        for (i in coarse.indices) coarse[i] /= peak
        coarse
    }
}

/**
 * An approximation of how a recipe renders, for trying ideas on the phone. It mimics each film
 * simulation's character (color mix, saturation, contrast, split toning) and applies the recipe's
 * white balance, tones, dynamic range, color, effects, clarity, sharpness and grain on top. It
 * isn't Fujifilm's processing: the camera's own result will differ.
 *
 * The order follows a camera's: white balance and the film simulation's color in linear light,
 * then the tone curve (its shape defined on the perceptual scale, where the highlight and shadow
 * settings live), saturation, and back to sRGB for split toning, the color chrome effects, the
 * local-contrast effects and grain. Where a measured 3D LUT for a film simulation is loaded
 * ([FilmLuts]), it replaces the parametric color and contrast.
 */
object RecipePreview {

    /** A film simulation's character, in the preview's simple terms. */
    private class Look(
        val saturation: Float,
        val contrast: Float,
        /** 3×3 color mix, row-major, applied in linear light before the tone curve. */
        val mix: FloatArray? = null,
        val shadowTint: FloatArray = ZERO,
        val highlightTint: FloatArray = ZERO,
        /** Monochrome: how much red, green and blue make the grey (the filter), in linear light. */
        val grey: FloatArray? = null,
        val sepia: Boolean = false,
    )

    private val ZERO = floatArrayOf(0f, 0f, 0f)
    private fun t(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    // Luminance of linear sRGB (BT.709).
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    private val PLAIN = t(LR, LG, LB)
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

    // ---- sRGB transfer ----

    fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    fun toSrgb(x: Float): Float = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

    private val DECODE8 = FloatArray(256) { toLinear(it / 255f) }
    private const val TABLE = 8192
    private const val ENCODE_RANGE = 2f
    private val ENCODE = FloatArray(TABLE + 1) { toSrgb(it * ENCODE_RANGE / TABLE) }
    private val DECODE = FloatArray(TABLE + 1) { toLinear(it.toFloat() / TABLE) }

    /** Linear → sRGB through a table (exact past its range). */
    private fun encode(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= ENCODE_RANGE) return toSrgb(x)
        val f = x * (TABLE / ENCODE_RANGE)
        val i = f.toInt()
        return ENCODE[i] + (ENCODE[i + 1] - ENCODE[i]) * (f - i)
    }

    /** sRGB → linear through a table, for values already in 0…1. */
    private fun decode(c: Float): Float {
        if (c <= 0f) return 0f
        if (c >= 1f) return toLinear(c)
        val f = c * TABLE
        val i = f.toInt()
        return DECODE[i] + (DECODE[i + 1] - DECODE[i]) * (f - i)
    }

    // ---- white balance ----

    /** White balance relative to a daylight-balanced picture, as linear red, green and blue gains. */
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
        // Keep the brightness: a grey stays as bright as it was.
        val norm = LR * red + LG * green + LB * blue
        red /= norm; green /= norm; blue /= norm
        return floatArrayOf(red, green, blue)
    }

    // ---- tone ----

    private fun bell(v: Float, center: Float, width: Float): Float {
        val d = (v - center) / width
        return exp(-d * d)
    }

    /**
     * The tone curve: the film simulation's contrast, the highlight and shadow settings and the
     * dynamic range's shoulder, as a table from linear light to linear light. Its shape is defined
     * on the perceptual (sRGB) scale. Black and white stay put unless a setting pushes them past
     * the ends (where they clip, as on the camera).
     */
    private class Tone(contrast: Float, highlight: Float, shadow: Float, compress: Float) {
        private val table = FloatArray(SIZE + 1)
        val top: Float

        init {
            fun shape(x: Float): Float {
                var v = x
                v += contrast * (v - 0.5f) * 4f * v * (1f - v)
                v += 0.045f * highlight * bell(v, 0.80f, 0.22f) * (v - 0.45f) * 2f
                v -= 0.045f * shadow * bell(v, 0.22f, 0.20f) * (0.55f - v) * 2f
                // Dynamic range: lower, flatter upper mids, white kept white (more headroom above them).
                if (compress > 0f && v > KNEE) {
                    val over = ((v - KNEE) / (1f - KNEE)).coerceAtMost(1f)
                    v -= compress * 1.7f * over * over * (1f - over)
                }
                return v
            }
            val lift = shape(0f).coerceAtLeast(0f)
            val drop = (1f - shape(1f)).coerceAtLeast(0f)
            for (i in 0..SIZE) {
                val e = toSrgb(i.toFloat() / SIZE)
                // Soft shadows or highlights would grey the black or the white: pin the ends.
                val u = 1f - e
                val v = shape(e) - lift * u * u * u * u + drop * e * e * e * e
                table[i] = toLinear(v.coerceAtLeast(0f))
                if (i > 0 && table[i] < table[i - 1]) table[i] = table[i - 1]
            }
            top = table[SIZE]
        }

        fun apply(x: Float): Float {
            if (x <= 0f) return table[0]
            if (x >= 1f) return top
            val f = x * SIZE
            val i = f.toInt()
            return table[i] + (table[i + 1] - table[i]) * (f - i)
        }

        companion object {
            const val SIZE = 4096
            const val KNEE = 0.6f
        }
    }

    private fun tone(contrast: Float, r: Recipe): Tone {
        // D-Range Priority sets the tones and dynamic range itself: a fixed, flat curve that protects both ends.
        return when (r.drPriority) {
            DrPriority.OFF -> Tone(
                contrast, r.highlight.toFloat(), r.shadow.toFloat(),
                when (r.dynamicRange) {
                    DynamicRange.DR400 -> 0.16f
                    DynamicRange.DR200, DynamicRange.AUTO -> 0.08f
                    DynamicRange.DR100 -> 0f
                },
            )
            DrPriority.WEAK, DrPriority.AUTO -> Tone(contrast, -1f, -1f, 0.08f)
            DrPriority.STRONG -> Tone(contrast, -2f, -2f, 0.16f)
        }
    }

    private fun effect(e: Effect): Float = when (e) { Effect.OFF -> 0f; Effect.WEAK -> 0.14f; Effect.STRONG -> 0.26f }

    private fun grainAmp(g: Grain): Float = when (g) {
        Grain.OFF -> 0f
        Grain.WEAK_SMALL, Grain.WEAK_LARGE -> 0.032f
        Grain.STRONG_SMALL, Grain.STRONG_LARGE -> 0.058f
    }

    /** Render [recipe] onto [out] (same size as the source). */
    fun render(src: PreviewSource, recipe: Recipe, out: IntArray) {
        val look = look(recipe.filmSim)
        val lut = FilmLuts.get(recipe.filmSim)
        val wb = whiteBalance(recipe)
        val mono = recipe.filmSim.mono
        // A measured LUT carries the simulation's own color, saturation, contrast and tints.
        val tone = tone(if (lut != null) 0f else look.contrast, recipe)
        val saturation = when {
            mono -> 0f
            lut != null -> 1f + 0.12f * recipe.color
            else -> look.saturation * (1f + 0.12f * recipe.color)
        }
        val shadowTint = if (lut != null) ZERO else look.shadowTint
        val highlightTint = if (lut != null) ZERO else look.highlightTint
        val mix = if (lut != null) null else look.mix
        val grey = if (lut != null) null else look.grey

        val clarity = recipe.clarity * 0.045f
        val sharp = recipe.sharpness * 0.22f
        val smooth = max(0, recipe.noiseReduction) * 0.06f
        val cce = effect(recipe.colorChrome)
        val ccb = effect(recipe.colorChromeBlue)
        val grainAmp = grainAmp(recipe.grain)
        val grainTex = when {
            grainAmp <= 0f -> null
            recipe.grain == Grain.WEAK_LARGE || recipe.grain == Grain.STRONG_LARGE -> src.grainCoarse
            else -> src.grainFine
        }
        val detailed = clarity != 0f || sharp != 0f || smooth != 0f
        val luma = if (detailed) src.luma else null
        val blurLarge = if (clarity != 0f) src.blurLarge else null
        val blurSmall = if (sharp != 0f || smooth != 0f) src.blurSmall else null
        val wc = recipe.monoWarmCool * 0.0045f
        val mg = recipe.monoMagentaGreen * 0.004f
        val lutOut = FloatArray(3)

        for (i in src.pixels.indices) {
            // Linear light, white balanced.
            val p = src.pixels[i]
            var r = DECODE8[(p shr 16) and 0xFF] * wb[0]
            var g = DECODE8[(p shr 8) and 0xFF] * wb[1]
            var b = DECODE8[p and 0xFF] * wb[2]

            // The film simulation's color.
            if (lut != null) {
                lut.apply(encode(r), encode(g), encode(b), lutOut)
                r = decode(lutOut[0]); g = decode(lutOut[1]); b = decode(lutOut[2])
            } else if (grey != null) {
                val l = grey[0] * r + grey[1] * g + grey[2] * b
                r = l; g = l; b = l
            } else if (mix != null) {
                val nr = mix[0] * r + mix[1] * g + mix[2] * b
                val ng = mix[3] * r + mix[4] * g + mix[5] * b
                val nb = mix[6] * r + mix[7] * g + mix[8] * b
                r = nr; g = ng; b = nb
            }

            // Tone, then saturation around the (unchanged) luminance.
            r = tone.apply(r); g = tone.apply(g); b = tone.apply(b)
            if (!mono) {
                val y = LR * r + LG * g + LB * b
                r = y + (r - y) * saturation
                g = y + (g - y) * saturation
                b = y + (b - y) * saturation
            }

            // Perceptual from here.
            r = encode(r); g = encode(g); b = encode(b)
            var l = 0.299f * r + 0.587f * g + 0.114f * b
            if (!mono) {
                val lo = (1f - l) * (1f - l)
                val up = l * l
                r += shadowTint[0] * lo + highlightTint[0] * up
                g += shadowTint[1] * lo + highlightTint[1] * up
                b += shadowTint[2] * lo + highlightTint[2] * up

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
            var add = 0f
            if (luma != null) {
                if (blurLarge != null) add += clarity * (luma[i] - blurLarge[i]) * 2.2f
                if (blurSmall != null) add += (sharp * 2f - smooth) * (luma[i] - blurSmall[i])
            }
            if (grainTex != null) add += grainTex[i] * grainAmp * (0.25f + 3f * l * (1f - l))
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
