package com.fujiapp.remote.recipe

import kotlin.math.roundToInt

/**
 * The custom setting slots over USB (PTP, camera in USB RAW CONV./BACKUP RESTORE): property codes,
 * value encodings, and the order to write them in. Facts from the fujifilm-ptp-reference record
 * (github.com/zgrimz/fujifilm-ptp-reference, CC BY 4.0), which lists the X100VI as able to read
 * and write its slots this way.
 *
 * Values travel as 16-bit little-endian integers. Tones, color, sharpness and clarity are signed
 * ×10; white balance shifts are signed in the camera's own units; monochrome toning is signed ×10;
 * High ISO NR is a lookup; Dynamic Range is the percentage, or 0xFFFF for Auto.
 */
object BankCodec {
    const val SELECTOR = 0xD18C
    const val NAME = 0xD18D
    const val IMAGE_SIZE = 0xD18E
    const val IMAGE_QUALITY = 0xD18F
    const val DYNAMIC_RANGE = 0xD190
    const val DR_PRIORITY = 0xD191
    const val FILM_SIM = 0xD192
    const val MONO_WC = 0xD193
    const val MONO_MG = 0xD194
    const val GRAIN = 0xD195
    const val COLOR_CHROME = 0xD196
    const val COLOR_CHROME_BLUE = 0xD197
    const val SMOOTH_SKIN = 0xD198
    const val WB_MODE = 0xD199
    const val WB_RED = 0xD19A
    const val WB_BLUE = 0xD19B
    const val KELVIN = 0xD19C
    const val HIGHLIGHT = 0xD19D
    const val SHADOW = 0xD19E
    const val COLOR = 0xD19F
    const val SHARPNESS = 0xD1A0
    const val NOISE_REDUCTION = 0xD1A1
    const val CLARITY = 0xD1A2
    const val LENS_MODULATION = 0xD1A3
    const val COLOR_SPACE = 0xD1A4
    /** The camera's USB mode: 6 = USB RAW CONV./BACKUP RESTORE. */
    const val USB_MODE = 0xD16E
    const val USB_MODE_RAW_CONV = 6

    /** Every setting a slot holds, in the order the camera reads them. */
    val SLOT_PROPERTIES: List<Int> = (IMAGE_SIZE..COLOR_SPACE).toList()

    val NAMES: Map<Int, String> = mapOf(
        DYNAMIC_RANGE to "Dynamic range", DR_PRIORITY to "D-range priority", FILM_SIM to "Film simulation",
        MONO_WC to "Toning (warm–cool)", MONO_MG to "Toning (magenta–green)", GRAIN to "Grain",
        COLOR_CHROME to "Color chrome", COLOR_CHROME_BLUE to "Color chrome FX blue", WB_MODE to "White balance",
        WB_RED to "WB shift red", WB_BLUE to "WB shift blue", KELVIN to "Color temperature",
        HIGHLIGHT to "Highlight", SHADOW to "Shadow", COLOR to "Color", SHARPNESS to "Sharpness",
        NOISE_REDUCTION to "High ISO NR", CLARITY to "Clarity", NAME to "Name",
    )

    private val NR_CODES = mapOf(-4 to 0x8000, -3 to 0x7000, -2 to 0x4000, -1 to 0x3000, 0 to 0x2000, 1 to 0x1000, 2 to 0x0000, 3 to 0x6000, 4 to 0x5000)

    fun nrCode(v: Int): Int = NR_CODES.getValue(v)
    fun nrValue(code: Int): Int? = NR_CODES.entries.firstOrNull { it.value == code }?.key

    fun u16(v: Int): Int = v and 0xFFFF
    fun signed(code: Int): Int = (code and 0xFFFF).toShort().toInt()
    private fun x10(v: Double): Int = u16((v * 10).roundToInt())
    private fun x10(v: Int): Int = u16(v * 10)

    /** One property write. */
    data class Write(val property: Int, val value: Int) {
        val label: String get() = NAMES[property] ?: "0x%04X".format(property)
    }

    /** A slot as read over USB: its name and the raw 16-bit value of each property it answered. */
    data class Slot(val number: Int, val name: String, val values: Map<Int, Int>) {
        val recipe: Recipe? get() = decode(name, values)
    }

    /** The recipe a slot holds, or null if a key setting is missing or unknown. */
    fun decode(name: String, v: Map<Int, Int>): Recipe? {
        val sim = v[FILM_SIM]?.let { FilmSim.of(it) } ?: return null
        val nr = v[NOISE_REDUCTION]?.let { nrValue(it) } ?: 0
        return Recipe(
            name = name,
            filmSim = sim,
            grain = v[GRAIN]?.let { Grain.of(it) } ?: Grain.OFF,
            colorChrome = v[COLOR_CHROME]?.let { Effect.of(it) } ?: Effect.OFF,
            colorChromeBlue = v[COLOR_CHROME_BLUE]?.let { Effect.of(it) } ?: Effect.OFF,
            whiteBalance = v[WB_MODE]?.let { WhiteBalance.of(it) } ?: WhiteBalance.AUTO,
            kelvin = v[KELVIN]?.takeIf { it in 2500..10000 } ?: 5500,
            wbRed = v[WB_RED]?.let { signed(it) } ?: 0,
            wbBlue = v[WB_BLUE]?.let { signed(it) } ?: 0,
            dynamicRange = v[DYNAMIC_RANGE]?.let { DynamicRange.of(it) } ?: DynamicRange.AUTO,
            drPriority = v[DR_PRIORITY]?.let { DrPriority.of(it) } ?: DrPriority.OFF,
            highlight = v[HIGHLIGHT]?.let { tone(it) } ?: 0.0,
            shadow = v[SHADOW]?.let { tone(it) } ?: 0.0,
            color = if (sim.mono) 0 else v[COLOR]?.let { signed(it) / 10 } ?: 0,
            sharpness = v[SHARPNESS]?.let { signed(it) / 10 } ?: 0,
            noiseReduction = nr,
            clarity = v[CLARITY]?.let { signed(it) / 10 } ?: 0,
            monoWarmCool = if (sim.toning) v[MONO_WC]?.let { signed(it) / 10 } ?: 0 else 0,
            monoMagentaGreen = if (sim.toning) v[MONO_MG]?.let { signed(it) / 10 } ?: 0 else 0,
        )
    }

    /** 0x8000 is "no value" for tones on some bodies. */
    private fun tone(code: Int): Double? = if (code == 0x8000) null else signed(code) / 10.0

    /**
     * The writes that turn a slot holding [current] into [target], in the order the camera needs:
     * D-Range Priority off before Dynamic Range; film simulation before toning, tones and color;
     * Kelvin straight after the white balance mode and the shifts after it; the name last. Only
     * settings that change are written (the camera can refuse a write of the value it holds),
     * except that a film simulation change rewrites tones and color, which some bodies reset with it.
     * Settings the recipe doesn't cover (image size and quality, smooth skin, lens modulation,
     * color space) are left alone.
     */
    fun plan(current: Map<Int, Int>, target: Recipe): List<Write> {
        val out = mutableListOf<Write>()
        fun same(p: Int, value: Int) = current[p]?.let { u16(it) == u16(value) } == true
        fun add(p: Int, value: Int, force: Boolean = false) {
            if (force || !same(p, value)) out += Write(p, u16(value))
        }
        val priorityOff = target.drPriority == DrPriority.OFF
        val currentPriority = current[DR_PRIORITY]?.let { DrPriority.of(it) } ?: DrPriority.OFF
        val simChanges = !same(FILM_SIM, target.filmSim.code)

        if (priorityOff && currentPriority != DrPriority.OFF) add(DR_PRIORITY, DrPriority.OFF.code)
        if (priorityOff) add(DYNAMIC_RANGE, target.dynamicRange.code)
        if (!priorityOff) add(DR_PRIORITY, target.drPriority.code)
        add(FILM_SIM, target.filmSim.code)
        if (target.filmSim.toning) {
            add(MONO_WC, x10(target.monoWarmCool), force = simChanges)
            add(MONO_MG, x10(target.monoMagentaGreen), force = simChanges)
        }
        val grainNow = current[GRAIN]?.let { Grain.of(it) }
        if (grainNow != target.grain) out += Write(GRAIN, target.grain.code)
        add(COLOR_CHROME, target.colorChrome.code)
        add(COLOR_CHROME_BLUE, target.colorChromeBlue.code)
        val modeChanges = !same(WB_MODE, target.whiteBalance.code)
        add(WB_MODE, target.whiteBalance.code)
        if (target.whiteBalance == WhiteBalance.KELVIN) add(KELVIN, target.kelvin, force = modeChanges)
        // Each white balance mode keeps its own shift: after a mode change the old values say nothing.
        add(WB_RED, target.wbRed, force = modeChanges)
        add(WB_BLUE, target.wbBlue, force = modeChanges)
        if (priorityOff) {
            add(HIGHLIGHT, x10(target.highlight), force = simChanges)
            add(SHADOW, x10(target.shadow), force = simChanges)
        }
        if (!target.filmSim.mono) add(COLOR, x10(target.color), force = simChanges)
        add(SHARPNESS, x10(target.sharpness))
        add(NOISE_REDUCTION, nrCode(target.noiseReduction))
        add(CLARITY, x10(target.clarity))
        return out
    }

    /** Settings that read back differently from [target], by meaning (empty when the slot matches). */
    fun mismatches(readBack: Map<Int, Int>, target: Recipe): List<String> {
        val got = decode(target.name, readBack) ?: return listOf("Film simulation")
        val wrong = mutableListOf<String>()
        fun check(label: String, ok: Boolean) { if (!ok) wrong += label }
        check("Film simulation", got.filmSim == target.filmSim)
        check("Grain", got.grain == target.grain)
        check("Color chrome", got.colorChrome == target.colorChrome)
        check("Color chrome FX blue", got.colorChromeBlue == target.colorChromeBlue)
        check("White balance", got.whiteBalance == target.whiteBalance)
        if (target.whiteBalance == WhiteBalance.KELVIN) check("Color temperature", got.kelvin == target.kelvin)
        check("WB shift", got.wbRed == target.wbRed && got.wbBlue == target.wbBlue)
        check("D-range priority", got.drPriority == target.drPriority)
        if (target.drPriority == DrPriority.OFF) {
            check("Dynamic range", got.dynamicRange == target.dynamicRange)
            check("Highlight", got.highlight == target.highlight)
            check("Shadow", got.shadow == target.shadow)
        }
        if (!target.filmSim.mono) check("Color", got.color == target.color)
        check("Sharpness", got.sharpness == target.sharpness)
        check("High ISO NR", got.noiseReduction == target.noiseReduction)
        check("Clarity", got.clarity == target.clarity)
        if (target.filmSim.toning) check("Toning", got.monoWarmCool == target.monoWarmCool && got.monoMagentaGreen == target.monoMagentaGreen)
        return wrong
    }

    /**
     * The writes that put a slot back to a snapshot taken before a recipe was loaded: every setting
     * the recipe could have changed, from the snapshot's raw values. Grain Off can't be written as
     * the 6/7 the camera reads back, so it goes back as 1 (Off; the size may differ).
     */
    fun undo(snapshot: Map<Int, Int>, now: Map<Int, Int>): List<Write> {
        val order = listOf(
            DR_PRIORITY, DYNAMIC_RANGE, FILM_SIM, MONO_WC, MONO_MG, GRAIN, COLOR_CHROME, COLOR_CHROME_BLUE,
            WB_MODE, KELVIN, WB_RED, WB_BLUE, HIGHLIGHT, SHADOW, COLOR, SHARPNESS, NOISE_REDUCTION, CLARITY,
        )
        val priorityOn = snapshot[DR_PRIORITY]?.let { it != DrPriority.OFF.code } == true
        val writes = mutableListOf<Write>()
        // Turning the priority on locks Dynamic Range and the tones: put those back first.
        val sequence = if (priorityOn) listOf(DYNAMIC_RANGE, HIGHLIGHT, SHADOW) + order.filter { it !in setOf(DYNAMIC_RANGE, HIGHLIGHT, SHADOW) } else order
        for (p in sequence) {
            val want = snapshot[p] ?: continue
            val value = if (p == GRAIN && (want == 6 || want == 7)) Grain.OFF.code else want
            if (p == GRAIN && Grain.of(now[p] ?: -1) == Grain.of(want)) continue
            if (now[p] == want) continue
            writes += Write(p, value)
        }
        return writes
    }
}
