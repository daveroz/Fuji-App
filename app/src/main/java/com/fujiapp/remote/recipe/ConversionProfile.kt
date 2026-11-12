package com.fujiapp.remote.recipe

import kotlin.math.roundToInt

/**
 * The camera's RAW conversion profile (PTP property `0xD185`): how it develops the RAF it was
 * sent. A header (cell count, the camera's IOPCode) and from byte `0x1FD` a table of 4-byte signed
 * little-endian cells, one per setting. Cell numbers and encodings from the fujifilm-ptp-reference
 * record (CC BY 4.0), which matches X RAW Studio's writes byte for byte.
 */
object ConversionProfile {
    const val BASE = 0x1FD
    /** The 625-byte read of X-Processor 5 bodies has 29 cells; X RAW Studio writes 30 (629 bytes). */
    private const val READ_CELLS = 29
    private const val WRITE_CELLS = 30

    const val FILE_TYPE = 2
    const val IMAGE_QUALITY = 4
    const val DYNAMIC_RANGE = 6
    const val FILM_SIM = 8
    const val GRAIN = 9
    const val COLOR_CHROME = 10
    const val WB_SOURCE = 11
    const val WB_MODE = 12
    const val WB_RED = 13
    const val WB_BLUE = 14
    const val KELVIN = 15
    const val HIGHLIGHT = 16
    const val SHADOW = 17
    const val COLOR = 18
    const val SHARPNESS = 19
    const val NOISE_REDUCTION = 20
    const val MONO_WC = 23
    const val COLOR_CHROME_BLUE = 25
    const val MONO_MG = 26
    const val CLARITY = 27

    private const val JPEG = 7
    private const val FINE = 2
    private const val WB_FROM_CELL = 2

    fun cells(profile: ByteArray): Int = maxOf(0, (profile.size - BASE) / 4)

    fun get(profile: ByteArray, cell: Int): Int? {
        val at = BASE + 4 * cell
        if (at + 4 > profile.size) return null
        return (profile[at].toInt() and 0xFF) or ((profile[at + 1].toInt() and 0xFF) shl 8) or
            ((profile[at + 2].toInt() and 0xFF) shl 16) or ((profile[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun put(profile: ByteArray, cell: Int, value: Int) {
        val at = BASE + 4 * cell
        if (at + 4 > profile.size) return
        profile[at] = value.toByte()
        profile[at + 1] = (value shr 8).toByte()
        profile[at + 2] = (value shr 16).toByte()
        profile[at + 3] = (value shr 24).toByte()
    }

    /** The cells [recipe] sets, and their values (only cells the profile has). */
    fun values(recipe: Recipe, cellCount: Int): Map<Int, Int> {
        val v = LinkedHashMap<Int, Int>()
        v[FILE_TYPE] = JPEG
        v[IMAGE_QUALITY] = FINE
        // No working Auto encoding is known for the profile: Auto keeps the RAF's own range.
        if (recipe.dynamicRange != DynamicRange.AUTO) v[DYNAMIC_RANGE] = recipe.dynamicRange.code
        v[FILM_SIM] = recipe.filmSim.code
        v[GRAIN] = recipe.grain.code
        v[COLOR_CHROME] = recipe.colorChrome.code
        v[WB_SOURCE] = WB_FROM_CELL
        v[WB_MODE] = recipe.whiteBalance.code
        v[WB_RED] = recipe.wbRed
        v[WB_BLUE] = recipe.wbBlue
        if (recipe.whiteBalance == WhiteBalance.KELVIN) v[KELVIN] = recipe.kelvin
        v[HIGHLIGHT] = (recipe.highlight * 10).roundToInt()
        v[SHADOW] = (recipe.shadow * 10).roundToInt()
        if (!recipe.filmSim.mono) v[COLOR] = recipe.color * 10
        v[SHARPNESS] = recipe.sharpness * 10
        v[NOISE_REDUCTION] = BankCodec.nrCode(recipe.noiseReduction)
        if (recipe.filmSim.toning) {
            v[MONO_WC] = recipe.monoWarmCool * 10
            v[MONO_MG] = recipe.monoMagentaGreen * 10
        }
        v[COLOR_CHROME_BLUE] = recipe.colorChromeBlue.code
        v[CLARITY] = recipe.clarity * 10
        return v.filterKeys { it < cellCount }
    }

    /**
     * The profile to send for [recipe], patched from the one the camera read after the upload: only
     * the recipe's cells change. A 29-cell read becomes X RAW Studio's 30-cell write (the extra cell
     * 0, the count left at 29, the IOPCode length byte 9).
     */
    fun apply(read: ByteArray, recipe: Recipe): ByteArray {
        require(read.size > BASE) { "The camera's conversion profile is too short (${read.size} bytes)." }
        val out = if (cells(read) == READ_CELLS && read.size == BASE + 4 * READ_CELLS) read.copyOf(BASE + 4 * WRITE_CELLS) else read.copyOf()
        if (out.size > read.size && out[2].toInt() == 8) out[2] = 9
        for ((cell, value) in values(recipe, cells(out))) put(out, cell, value)
        return out
    }

    /** Cells that read back differently from what was written (grain Off may come back as 6 or 7). */
    fun mismatches(written: ByteArray, readBack: ByteArray, recipe: Recipe): List<Int> =
        values(recipe, minOf(cells(written), cells(readBack))).filter { (cell, want) ->
            val got = get(readBack, cell)
            when {
                cell == GRAIN && want == Grain.OFF.code -> got !in setOf(1, 6, 7)
                else -> got != want
            }
        }.keys.toList()
}
