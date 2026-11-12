package com.fujiapp.remote.recipe

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Film simulations, with the camera's own codes (the bank property `0xD192`). */
enum class FilmSim(val code: Int, val label: String, val mono: Boolean = false, val toning: Boolean = false) {
    PROVIA(0x01, "Provia / Standard"),
    VELVIA(0x02, "Velvia / Vivid"),
    ASTIA(0x03, "Astia / Soft"),
    PRO_NEG_HI(0x04, "Pro Neg. Hi"),
    PRO_NEG_STD(0x05, "Pro Neg. Std"),
    MONOCHROME(0x06, "Monochrome", mono = true, toning = true),
    MONOCHROME_YE(0x07, "Monochrome + Ye", mono = true, toning = true),
    MONOCHROME_R(0x08, "Monochrome + R", mono = true, toning = true),
    MONOCHROME_G(0x09, "Monochrome + G", mono = true, toning = true),
    SEPIA(0x0A, "Sepia", mono = true),
    CLASSIC_CHROME(0x0B, "Classic Chrome"),
    ACROS(0x0C, "Acros", mono = true, toning = true),
    ACROS_YE(0x0D, "Acros + Ye", mono = true, toning = true),
    ACROS_R(0x0E, "Acros + R", mono = true, toning = true),
    ACROS_G(0x0F, "Acros + G", mono = true, toning = true),
    ETERNA(0x10, "Eterna / Cinema"),
    CLASSIC_NEG(0x11, "Classic Neg."),
    BLEACH_BYPASS(0x12, "Eterna Bleach Bypass"),
    NOSTALGIC_NEG(0x13, "Nostalgic Neg."),
    REALA_ACE(0x14, "Reala Ace");

    companion object {
        fun of(code: Int): FilmSim? = entries.firstOrNull { it.code == code }
    }
}

/** Grain effect: strength and size. The camera takes 1 for Off and reads Off back as 6 or 7. */
enum class Grain(val code: Int, val label: String) {
    OFF(1, "Off"),
    WEAK_SMALL(2, "Weak, small"),
    STRONG_SMALL(3, "Strong, small"),
    WEAK_LARGE(4, "Weak, large"),
    STRONG_LARGE(5, "Strong, large");

    companion object {
        fun of(code: Int): Grain? = if (code == 6 || code == 7) OFF else entries.firstOrNull { it.code == code }
    }
}

/** Color Chrome Effect, Color Chrome FX Blue (and Smooth Skin): 1 Off, 2 Weak, 3 Strong. */
enum class Effect(val code: Int, val label: String) {
    OFF(1, "Off"), WEAK(2, "Weak"), STRONG(3, "Strong");

    companion object {
        fun of(code: Int): Effect? = entries.firstOrNull { it.code == code }
    }
}

enum class WhiteBalance(val code: Int, val label: String) {
    AUTO(0x0002, "Auto"),
    AUTO_WHITE(0x8020, "Auto, white priority"),
    AUTO_AMBIENCE(0x8021, "Auto, ambience priority"),
    DAYLIGHT(0x0004, "Daylight"),
    SHADE(0x8006, "Shade"),
    FLUORESCENT_1(0x8001, "Fluorescent 1"),
    FLUORESCENT_2(0x8002, "Fluorescent 2"),
    FLUORESCENT_3(0x8003, "Fluorescent 3"),
    INCANDESCENT(0x0006, "Incandescent"),
    UNDERWATER(0x0008, "Underwater"),
    KELVIN(0x8007, "Color temperature"),
    CUSTOM_1(0x8008, "Custom 1"),
    CUSTOM_2(0x8009, "Custom 2"),
    CUSTOM_3(0x800A, "Custom 3");

    companion object {
        fun of(code: Int): WhiteBalance? = entries.firstOrNull { it.code == code }
    }
}

/** Dynamic range: the percentage itself, or 0xFFFF for Auto. */
enum class DynamicRange(val code: Int, val label: String, val minIso: Int) {
    AUTO(0xFFFF, "Auto", 0), DR100(100, "DR100", 0), DR200(200, "DR200", 250), DR400(400, "DR400", 500);

    companion object {
        fun of(code: Int): DynamicRange? = entries.firstOrNull { it.code == code }
    }
}

enum class DrPriority(val code: Int, val label: String) {
    OFF(0x0000, "Off"), WEAK(0x0001, "Weak"), STRONG(0x0002, "Strong"), AUTO(0x8000, "Auto");

    companion object {
        fun of(code: Int): DrPriority? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A film simulation recipe: the image settings a custom setting slot (C1–C7) stores and that a
 * recipe changes. Tones are in steps of 0.5 (−2…+4); color, sharpness and noise reduction −4…+4;
 * clarity −5…+5; white balance shift −9…+9; monochrome toning −18…+18 (monochrome only).
 */
data class Recipe(
    val name: String,
    val filmSim: FilmSim,
    val grain: Grain = Grain.OFF,
    val colorChrome: Effect = Effect.OFF,
    val colorChromeBlue: Effect = Effect.OFF,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val kelvin: Int = 5500,
    val wbRed: Int = 0,
    val wbBlue: Int = 0,
    val dynamicRange: DynamicRange = DynamicRange.DR100,
    val drPriority: DrPriority = DrPriority.OFF,
    val highlight: Double = 0.0,
    val shadow: Double = 0.0,
    val color: Int = 0,
    val sharpness: Int = 0,
    val noiseReduction: Int = 0,
    val clarity: Int = 0,
    val monoWarmCool: Int = 0,
    val monoMagentaGreen: Int = 0,
) {
    /** The name as the camera stores it: printable ASCII, at most 25 characters. */
    val cameraName: String
        get() = name.uppercase(Locale.US).filter { it.code in 0x20..0x7E }.trim().take(NAME_MAX)

    /** What's wrong with the values, if anything (empty when the camera can take them all). */
    fun problems(): List<String> = buildList {
        if (cameraName.isEmpty()) add("Name is empty")
        fun tone(label: String, v: Double) {
            if (v < -2.0 || v > 4.0) add("$label must be −2…+4")
            if (abs(v * 2 - (v * 2).roundToInt()) > 1e-9) add("$label must be in steps of 0.5")
        }
        tone("Highlight", highlight)
        tone("Shadow", shadow)
        if (color !in -4..4) add("Color must be −4…+4")
        if (sharpness !in -4..4) add("Sharpness must be −4…+4")
        if (noiseReduction !in -4..4) add("High ISO NR must be −4…+4")
        if (clarity !in -5..5) add("Clarity must be −5…+5")
        if (wbRed !in -9..9 || wbBlue !in -9..9) add("White balance shift must be −9…+9")
        if (whiteBalance == WhiteBalance.KELVIN && kelvin !in 2500..10000) add("Color temperature must be 2500…10000 K")
        if (monoWarmCool !in -18..18 || monoMagentaGreen !in -18..18) add("Monochrome toning must be −18…+18")
        if (!filmSim.toning && (monoWarmCool != 0 || monoMagentaGreen != 0)) add("Toning only applies to Monochrome and Acros")
        if (drPriority != DrPriority.OFF && (highlight != 0.0 || shadow != 0.0)) add("Tones are set by the camera while D-Range Priority is on")
    }

    /** The settings as a recipe card lists them. */
    fun rows(): List<Pair<String, String>> = buildList {
        add("Film simulation" to filmSim.label)
        if (filmSim.toning && (monoWarmCool != 0 || monoMagentaGreen != 0)) {
            add("Monochrome toning" to "WC ${signed(monoWarmCool)}, MG ${signed(monoMagentaGreen)}")
        }
        add("Grain" to grain.label)
        add("Color chrome" to colorChrome.label)
        add("Color chrome FX blue" to colorChromeBlue.label)
        add("White balance" to (if (whiteBalance == WhiteBalance.KELVIN) "${kelvin}K" else whiteBalance.label))
        add("WB shift" to "R ${signed(wbRed)}, B ${signed(wbBlue)}")
        add("Dynamic range" to dynamicRange.label)
        if (drPriority != DrPriority.OFF) add("D-range priority" to drPriority.label)
        if (drPriority == DrPriority.OFF) {
            add("Highlight" to signed(highlight))
            add("Shadow" to signed(shadow))
        }
        if (!filmSim.mono) add("Color" to signed(color))
        add("Sharpness" to signed(sharpness))
        add("High ISO NR" to signed(noiseReduction))
        add("Clarity" to signed(clarity))
    }

    companion object {
        const val NAME_MAX = 25

        fun signed(v: Int): String = when {
            v > 0 -> "+$v"
            v < 0 -> "−${-v}"
            else -> "0"
        }

        fun signed(v: Double): String {
            val text = if (v == v.roundToInt().toDouble()) "${abs(v).roundToInt()}" else String.format(Locale.US, "%.1f", abs(v))
            return when {
                v > 0 -> "+$text"
                v < 0 -> "−$text"
                else -> "0"
            }
        }
    }
}
