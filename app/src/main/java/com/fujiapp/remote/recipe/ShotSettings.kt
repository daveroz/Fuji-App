package com.fujiapp.remote.recipe

import kotlin.math.roundToInt

/**
 * What a camera JPEG says about itself in its Exif: which way up it goes and, in Fujifilm's maker
 * note, the settings it was shot with (film simulation, white balance, tones, dynamic range,
 * color, effects). A RAF's embedded JPEG carries the same Exif as the camera's JPEGs. Tag numbers
 * and values are as ExifTool documents Fujifilm's maker note.
 */
object ShotSettings {

    /** Exif of a JPEG: its orientation (1 upright, 3 upside down, 6 turn right, 8 turn left) and Fujifilm's tags. */
    class Exif(val orientation: Int, val fujifilm: Map<Int, IntArray>) {
        /** The settings the picture was shot with, or null when the maker note doesn't name a film simulation. */
        val recipe: Recipe? by lazy { recipe(fujifilm) }

        /** The tags that decide the look, for the log. */
        fun describe(): String = KEY_TAGS.mapNotNull { t -> fujifilm[t]?.let { "0x%04X=%s".format(t, it.joinToString(",")) } }.joinToString(" ")
    }

    /** Read the Exif of [jpeg]; null when it has none (or it's damaged). */
    fun read(jpeg: ByteArray): Exif? = runCatching { parse(jpeg) }.getOrNull()

    private fun parse(jpeg: ByteArray): Exif? {
        val (start, end) = exifSegment(jpeg) ?: return null
        val tiff = when {
            jpeg[start] == 'I'.code.toByte() && jpeg[start + 1] == 'I'.code.toByte() -> Ifd(jpeg, start, end, little = true)
            jpeg[start] == 'M'.code.toByte() && jpeg[start + 1] == 'M'.code.toByte() -> Ifd(jpeg, start, end, little = false)
            else -> return null
        }
        if (tiff.u16(2) != 42) return null
        val ifd0 = tiff.entries(tiff.u32(4))
        val orientation = ifd0[0x0112]?.let { tiff.values(it).firstOrNull() } ?: 1
        val exifIfd = ifd0[0x8769]?.let { tiff.values(it).firstOrNull() }
        val maker = exifIfd?.let { tiff.entries(it)[0x927C] }
        var fujifilm = emptyMap<Int, IntArray>()
        if (maker != null && maker.count >= 12) {
            // "FUJIFILM", then where its IFD starts; it's always little-endian and its offsets count from the note's start.
            val at = tiff.base + tiff.u32(maker.valueAt)
            if (at + 12 <= end && String(jpeg, at, 8, Charsets.US_ASCII) == "FUJIFILM") {
                val note = Ifd(jpeg, at, minOf(end, at + maker.count), little = true)
                val entries = note.entries(note.u32(8))
                // One damaged entry doesn't spoil the rest.
                fujifilm = entries.mapValues { (_, e) -> runCatching { note.values(e) }.getOrDefault(IntArray(0)) }.filterValues { it.isNotEmpty() }
            }
        }
        return Exif(orientation, fujifilm)
    }

    /** Where the TIFF data of the Exif APP1 segment starts and ends. */
    private fun exifSegment(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var p = 2
        while (p + 4 <= b.size) {
            if (u8(b, p) != 0xFF) return null
            val marker = u8(b, p + 1)
            when {
                marker == 0xFF -> { p++; continue }
                marker == 0x01 || marker in 0xD0..0xD7 -> { p += 2; continue }
                marker == 0xDA || marker == 0xD9 -> return null
            }
            val length = (u8(b, p + 2) shl 8) or u8(b, p + 3)
            if (length < 2 || p + 2 + length > b.size) return null
            val start = p + 4
            if (marker == 0xE1 && length >= 16 && String(b, start, 4, Charsets.US_ASCII) == "Exif" && b[start + 4] == 0.toByte()) {
                return (start + 6) to (p + 2 + length)
            }
            p += 2 + length
        }
        return null
    }

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF

    private class Entry(val tag: Int, val type: Int, val count: Int, /** Where the value, or its offset, sits. */ val valueAt: Int)

    /** A TIFF-style directory reader; offsets count from [base]. */
    private class Ifd(val b: ByteArray, val base: Int, val end: Int, val little: Boolean) {
        fun u16(off: Int): Int {
            val at = base + off
            require(off >= 0 && at + 2 <= end)
            val x = b[at].toInt() and 0xFF
            val y = b[at + 1].toInt() and 0xFF
            return if (little) x or (y shl 8) else (x shl 8) or y
        }

        fun u32(off: Int): Int {
            val lo = u16(if (little) off else off + 2)
            val hi = u16(if (little) off + 2 else off)
            return (hi shl 16) or lo
        }

        fun entries(off: Int): Map<Int, Entry> {
            val n = u16(off)
            require(n in 0..512)
            return (0 until n).associate { i ->
                val e = off + 2 + i * 12
                val tag = u16(e)
                tag to Entry(tag, u16(e + 2), u32(e + 4), e + 8)
            }
        }

        /** An entry's numbers (signed where the type is). Text and unknown types give none. */
        fun values(e: Entry): IntArray {
            val width = when (e.type) { 1, 6, 7 -> 1; 3, 8 -> 2; 4, 9 -> 4; else -> return IntArray(0) }
            if (e.count !in 1..64) return IntArray(0)
            val at = if (e.count * width <= 4) e.valueAt else u32(e.valueAt)
            return IntArray(e.count) { i ->
                val o = at + i * width
                when (e.type) {
                    1, 7 -> { require(base + o < end); b[base + o].toInt() and 0xFF }
                    6 -> { require(base + o < end); b[base + o].toInt() }
                    3 -> u16(o)
                    8 -> u16(o).toShort().toInt()
                    else -> u32(o)
                }
            }
        }
    }

    // ---- Fujifilm's maker note → a recipe ----

    private const val SHARPNESS = 0x1001
    private const val WHITE_BALANCE = 0x1002
    private const val SATURATION = 0x1003
    private const val COLOR_TEMPERATURE = 0x1005
    private const val WB_FINE_TUNE = 0x100A
    private const val NOISE_REDUCTION = 0x100E
    private const val CLARITY = 0x100F
    private const val SHADOW_TONE = 0x1040
    private const val HIGHLIGHT_TONE = 0x1041
    private const val GRAIN_ROUGHNESS = 0x1047
    private const val COLOR_CHROME = 0x1048
    private const val BW_WARM_COOL = 0x1049
    private const val BW_MAGENTA_GREEN = 0x104B
    private const val GRAIN_SIZE = 0x104C
    private const val COLOR_CHROME_BLUE = 0x104E
    private const val FILM_MODE = 0x1401
    private const val DR_SETTING = 0x1402
    private const val DEVELOPMENT_DR = 0x1403
    private const val DR_PRIORITY = 0x1443
    private const val DR_PRIORITY_AUTO = 0x1444
    private const val DR_PRIORITY_FIXED = 0x1445

    private val KEY_TAGS = listOf(
        FILM_MODE, SATURATION, WHITE_BALANCE, COLOR_TEMPERATURE, WB_FINE_TUNE, HIGHLIGHT_TONE, SHADOW_TONE,
        DR_SETTING, DEVELOPMENT_DR, DR_PRIORITY, DR_PRIORITY_AUTO, DR_PRIORITY_FIXED, COLOR_CHROME, COLOR_CHROME_BLUE,
        GRAIN_ROUGHNESS, GRAIN_SIZE, SHARPNESS, NOISE_REDUCTION, CLARITY, BW_WARM_COOL, BW_MAGENTA_GREEN,
    )

    /** The recipe a picture was shot with, from Fujifilm's maker-note tags; null without a film simulation. */
    fun recipe(tags: Map<Int, IntArray>): Recipe? {
        fun v(tag: Int): Int? = tags[tag]?.firstOrNull()
        val saturation = v(SATURATION)
        val sim = when (saturation) {
            0x300 -> FilmSim.MONOCHROME
            0x301 -> FilmSim.MONOCHROME_R
            0x302 -> FilmSim.MONOCHROME_YE
            0x303 -> FilmSim.MONOCHROME_G
            0x310 -> FilmSim.SEPIA
            0x500 -> FilmSim.ACROS
            0x501 -> FilmSim.ACROS_R
            0x502 -> FilmSim.ACROS_YE
            0x503 -> FilmSim.ACROS_G
            else -> when (v(FILM_MODE)) {
                0x000 -> FilmSim.PROVIA
                0x100, 0x110, 0x120, 0x130, 0x300 -> FilmSim.ASTIA
                0x200, 0x400 -> FilmSim.VELVIA
                0x500 -> FilmSim.PRO_NEG_STD
                0x501 -> FilmSim.PRO_NEG_HI
                0x600 -> FilmSim.CLASSIC_CHROME
                0x700 -> FilmSim.ETERNA
                0x800 -> FilmSim.CLASSIC_NEG
                0x900 -> FilmSim.BLEACH_BYPASS
                0xA00 -> FilmSim.NOSTALGIC_NEG
                0xB00 -> FilmSim.REALA_ACE
                else -> null
            }
        } ?: return null

        val color = if (sim.mono) 0 else when (saturation) {
            0x080 -> 1; 0x100 -> 2; 0x0C0 -> 3; 0x0E0 -> 4
            0x180 -> -1; 0x400 -> -2; 0x4C0 -> -3; 0x4E0 -> -4
            else -> 0
        }

        val (whiteBalance, kelvin) = when (v(WHITE_BALANCE)) {
            0x001 -> WhiteBalance.AUTO_WHITE to 5500
            0x002 -> WhiteBalance.AUTO_AMBIENCE to 5500
            0x100 -> WhiteBalance.DAYLIGHT to 5500
            0x200 -> WhiteBalance.SHADE to 5500
            0x300 -> WhiteBalance.FLUORESCENT_1 to 5500
            0x303, 0x304 -> WhiteBalance.FLUORESCENT_2 to 5500
            0x301, 0x302 -> WhiteBalance.FLUORESCENT_3 to 5500
            0x400 -> WhiteBalance.INCANDESCENT to 5500
            0x600 -> WhiteBalance.UNDERWATER to 5500
            0xF00 -> WhiteBalance.CUSTOM_1 to 5500
            0xF01 -> WhiteBalance.CUSTOM_2 to 5500
            0xF02 -> WhiteBalance.CUSTOM_3 to 5500
            0xFF0 -> WhiteBalance.KELVIN to ((v(COLOR_TEMPERATURE) ?: 5500).coerceIn(2500, 10000) / 10 * 10)
            else -> WhiteBalance.AUTO to 5500
        }
        // The fine tune is red, blue: in steps of 20 on recent cameras, plain steps on older ones.
        val fine = tags[WB_FINE_TUNE]
        fun shift(x: Int?): Int = when {
            x == null -> 0
            x % 20 == 0 -> x / 20
            else -> x
        }.coerceIn(-9, 9)
        val wbRed = shift(fine?.getOrNull(0))
        val wbBlue = shift(fine?.getOrNull(1))

        val dynamicRange = when {
            v(DR_SETTING) == 0 -> DynamicRange.AUTO
            else -> when (v(DEVELOPMENT_DR)) {
                200 -> DynamicRange.DR200
                400 -> DynamicRange.DR400
                else -> when (v(DR_SETTING)) {
                    0x200 -> DynamicRange.DR200
                    0x201 -> DynamicRange.DR400
                    else -> DynamicRange.DR100
                }
            }
        }
        val drPriority = when (v(DR_PRIORITY)) {
            0 -> if (v(DR_PRIORITY_AUTO) in 1..2) DrPriority.AUTO else DrPriority.OFF
            1 -> when (v(DR_PRIORITY_FIXED)) { 1 -> DrPriority.WEAK; 2 -> DrPriority.STRONG; else -> DrPriority.OFF }
            else -> DrPriority.OFF
        }
        // Tones are stored as −16 per step (−64 is +4, 32 is −2).
        fun tone(tag: Int): Double {
            val raw = v(tag) ?: return 0.0
            return ((-raw / 16.0) * 2).roundToInt().coerceIn(-4, 8) / 2.0
        }
        val highlight = if (drPriority == DrPriority.OFF) tone(HIGHLIGHT_TONE) else 0.0
        val shadow = if (drPriority == DrPriority.OFF) tone(SHADOW_TONE) else 0.0

        fun effect(tag: Int): Effect = when (v(tag)) { 32 -> Effect.WEAK; 64 -> Effect.STRONG; else -> Effect.OFF }
        val large = v(GRAIN_SIZE) == 32
        val grain = when (v(GRAIN_ROUGHNESS)) {
            32 -> if (large) Grain.WEAK_LARGE else Grain.WEAK_SMALL
            64 -> if (large) Grain.STRONG_LARGE else Grain.STRONG_SMALL
            else -> Grain.OFF
        }
        val sharpness = when (v(SHARPNESS)) {
            0x00 -> -4; 0x01 -> -3; 0x02 -> -2; 0x82 -> -1; 0x84 -> 1; 0x04 -> 2; 0x05 -> 3; 0x06 -> 4
            else -> 0
        }
        val noiseReduction = when (v(NOISE_REDUCTION)) {
            0x180 -> 1; 0x100 -> 2; 0x1C0 -> 3; 0x1E0 -> 4
            0x280 -> -1; 0x200 -> -2; 0x2C0 -> -3; 0x2E0 -> -4
            else -> 0
        }
        val clarity = v(CLARITY)?.let { if (it in -5..5) it else if (it % 1000 == 0) it / 1000 else 0 }?.coerceIn(-5, 5) ?: 0
        fun toning(tag: Int): Int = if (sim.toning) (v(tag) ?: 0).coerceIn(-18, 18) else 0

        return Recipe(
            name = "AS SHOT",
            filmSim = sim,
            grain = grain,
            colorChrome = effect(COLOR_CHROME),
            colorChromeBlue = effect(COLOR_CHROME_BLUE),
            whiteBalance = whiteBalance,
            kelvin = kelvin,
            wbRed = wbRed,
            wbBlue = wbBlue,
            dynamicRange = dynamicRange,
            drPriority = drPriority,
            highlight = highlight,
            shadow = shadow,
            color = color,
            sharpness = sharpness,
            noiseReduction = noiseReduction,
            clarity = clarity,
            monoWarmCool = toning(BW_WARM_COOL),
            monoMagentaGreen = toning(BW_MAGENTA_GREEN),
        )
    }
}
