package com.fujiapp.remote.preview

import kotlin.math.roundToInt

/**
 * The Lab's calibration chart, drawn in code: the 24 patches of a classic color checker (its
 * published sRGB values), a row of skin tones from light to dark, and a grey ramp. What a recipe
 * does to each color, to skin and to the tonal scale is easy to see, and the vectorscope shows
 * whether skin stays on the skin-tone line.
 */
object SampleScene {

    /** Color checker, row by row: skin, sky, foliage…; then the primaries; then the neutrals. */
    val CHECKER = intArrayOf(
        0x735244, 0xC29682, 0x627A9D, 0x576C43, 0x8580B1, 0x67BDAA,
        0xD67E2C, 0x505BA6, 0xC15A63, 0x5E3C6C, 0x9DBC40, 0xE0A32E,
        0x383D96, 0x469449, 0xAF363C, 0xE7C71F, 0xBB5695, 0x0885A1,
        0xF3F3F2, 0xC8C8C8, 0xA0A0A0, 0x7A7A79, 0x555555, 0x343434,
    )

    /** Skin tones, very light to very dark. */
    val SKIN = intArrayOf(0xF6D7C3, 0xEBC1A5, 0xDDA98A, 0xC98C6B, 0xAE7454, 0x8E5A3D, 0x6C432C, 0x4A2E20)

    fun pixels(w: Int, h: Int): IntArray {
        val out = IntArray(w * h) { 0xFF161A20.toInt() }
        val margin = (w * 0.025f).roundToInt()
        val gap = (w * 0.012f).roundToInt()
        val cols = 6
        val chartH = (h * 0.62f).roundToInt()
        val cellW = (w - 2 * margin - (cols - 1) * gap) / cols
        val cellH = (chartH - margin - 3 * gap) / 4
        for (i in CHECKER.indices) {
            val x0 = margin + (i % cols) * (cellW + gap)
            val y0 = margin + (i / cols) * (cellH + gap)
            fill(out, w, x0, y0, cellW, cellH, CHECKER[i])
        }
        val skinTop = margin + 4 * (cellH + gap) + gap
        val skinH = (h * 0.17f).roundToInt()
        val skinW = (w - 2 * margin - (SKIN.size - 1) * gap) / SKIN.size
        for (i in SKIN.indices) fill(out, w, margin + i * (skinW + gap), skinTop, skinW, skinH, SKIN[i])
        val rampTop = skinTop + skinH + gap * 2
        val rampBottom = h - margin
        for (y in rampTop until rampBottom) {
            for (x in margin until w - margin) {
                val v = ((x - margin).toFloat() / (w - 2 * margin - 1) * 255f).roundToInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return out
    }

    private fun fill(out: IntArray, w: Int, x0: Int, y0: Int, cw: Int, ch: Int, rgb: Int) {
        val color = (0xFF shl 24) or rgb
        for (y in y0 until y0 + ch) for (x in x0 until x0 + cw) out[y * w + x] = color
    }
}

/**
 * A vectorscope in the manner of DaVinci Resolve's: every pixel placed by its color difference
 * (BT.709 Cb across, Cr up), with the 75% color targets and the skin-tone line. Skin of any
 * complexion sits on or near that line; a recipe that pushes skin off it shows here at once.
 */
object Vectorscope {
    /** The skin-tone line, in degrees counter-clockwise from +Cb (about 123°, between red and yellow). */
    const val SKIN_LINE_DEG = 123.0

    fun render(src: IntArray, size: Int, out: IntArray) {
        val sum = FloatArray(size * size * 3)
        val count = FloatArray(size * size)
        val c = size / 2f
        val scale = size * 0.9f
        var i = 0
        while (i < src.size) {
            val p = src[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val cb = -0.1146f * r - 0.3854f * g + 0.5f * b
            val cr = 0.5f * r - 0.4542f * g - 0.0458f * b
            val x = (c + cb * scale).toInt()
            val y = (c - cr * scale).toInt()
            // A small dot per sample, so a flat patch (one color) still reads as a point.
            for (oy in -1..1) for (ox in -1..1) {
                val xx = x + ox
                val yy = y + oy
                if (xx in 0 until size && yy in 0 until size) {
                    val k = yy * size + xx
                    count[k] += 1f
                    sum[k * 3] += r; sum[k * 3 + 1] += g; sum[k * 3 + 2] += b
                }
            }
            i += 2
        }
        var peak = 1f
        for (v in count) if (v > peak) peak = v
        val logPeak = kotlin.math.ln(1f + peak)
        for (k in out.indices) {
            val x = k % size
            val y = k / size
            val dx = x - c
            val dy = y - c
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            var a = if (d <= c) 0xE6 else 0x00
            var rr = 6; var gg = 8; var bb = 12
            if (d <= c) {
                // graticule: outer ring, inner ring, crosshair
                if (kotlin.math.abs(d - c * 0.98f) < 1f || kotlin.math.abs(d - c * 0.5f) < 0.6f ||
                    (kotlin.math.abs(dx) < 0.6f || kotlin.math.abs(dy) < 0.6f)
                ) { rr = 40; gg = 50; bb = 64 }
                val n = count[k]
                if (n > 0f) {
                    val t = (kotlin.math.ln(1f + n) / logPeak).coerceIn(0.6f, 1f)
                    rr = (sum[k * 3] / n * 255f * t + 90 * t).toInt().coerceAtMost(255)
                    gg = (sum[k * 3 + 1] / n * 255f * t + 90 * t).toInt().coerceAtMost(255)
                    bb = (sum[k * 3 + 2] / n * 255f * t + 90 * t).toInt().coerceAtMost(255)
                }
            } else a = 0
            out[k] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        // the skin-tone line, in the key light
        val rad = Math.toRadians(SKIN_LINE_DEG)
        val steps = size
        for (s in 0 until steps) {
            val t = s.toFloat() / steps * c * 0.97f
            if ((s / 3) % 2 == 1) continue // dashed
            val x = (c + t * kotlin.math.cos(rad)).toInt()
            val y = (c - t * kotlin.math.sin(rad)).toInt()
            if (x in 0 until size && y in 0 until size) out[y * size + x] = 0xFFEB9438.toInt()
        }
        // 75% targets: R, Mg, B, Cy, G, Yl
        for (rgb in intArrayOf(0xBF0000, 0xBF00BF, 0x0000BF, 0x00BFBF, 0x00BF00, 0xBFBF00)) {
            val r = ((rgb shr 16) and 0xFF) / 255f
            val g = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            val cx = (c + (-0.1146f * r - 0.3854f * g + 0.5f * b) * scale).toInt()
            val cy = (c - (0.5f * r - 0.4542f * g - 0.0458f * b) * scale).toInt()
            val half = (size * 0.025f).toInt().coerceAtLeast(2)
            for (o in -half..half) {
                plot(out, size, cx + o, cy - half); plot(out, size, cx + o, cy + half)
                plot(out, size, cx - half, cy + o); plot(out, size, cx + half, cy + o)
            }
        }
    }

    private fun plot(out: IntArray, size: Int, x: Int, y: Int) {
        if (x in 0 until size && y in 0 until size) out[y * size + x] = 0xFF8FA0B3.toInt()
    }
}
