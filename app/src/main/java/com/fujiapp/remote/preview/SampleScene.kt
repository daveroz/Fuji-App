package com.fujiapp.remote.preview

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sample picture drawn in code (no photo to license): a sky with the sun low, layered
 * mountains, a lake, a meadow with a red cabin and flowers, and along the bottom a color chart
 * with skin tones and a grey ramp. Enough to see what a recipe does to skies, greens, reds, skin,
 * highlights and shadows.
 */
object SampleScene {

    private val CHART = intArrayOf(
        0x735244, 0xC29682, 0x627A9D, 0x576C43, 0x8580B1, 0x67BDAA,
        0xD67E2C, 0x505BA6, 0xC15A63, 0x5E3C6C, 0x9DBC40, 0xE0A32E,
    )

    fun pixels(w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val horizon = h * 0.52f
        val lakeEnd = h * 0.68f
        val chartTop = h * 0.84f
        val sunX = w * 0.70f
        val sunY = h * 0.36f
        val sunR = w * 0.035f

        fun ridge(x: Float, base: Float, amp: Float, f1: Float, f2: Float, phase: Float) =
            base - amp * (0.55f * sin(x * f1 + phase) + 0.30f * sin(x * f2 + phase * 1.7f) + 0.15f * sin(x * f2 * 2.3f + 0.4f))

        for (y in 0 until h) {
            for (x in 0 until w) {
                val fx = x.toFloat()
                val fy = y.toFloat()
                var r: Float
                var g: Float
                var b: Float
                if (fy < chartTop) {
                    // Sky, with the sun's glow.
                    val t = (fy / horizon).coerceIn(0f, 1f)
                    r = lerp(0.18f, 0.96f, t * t)
                    g = lerp(0.40f, 0.80f, t)
                    b = lerp(0.74f, 0.62f, t)
                    val d = sqrt((fx - sunX) * (fx - sunX) + (fy - sunY) * (fy - sunY))
                    val glow = exp(-d / (w * 0.12f))
                    r += 0.55f * glow; g += 0.38f * glow; b += 0.12f * glow
                    if (d < sunR) { r = 1.6f; g = 1.45f; b = 1.1f }

                    val nx = fx / w * 12f
                    val far = ridge(nx, horizon - h * 0.02f, h * 0.10f, 0.9f, 2.1f, 0.3f)
                    val mid = ridge(nx, horizon + h * 0.01f, h * 0.07f, 1.4f, 3.3f, 1.9f)
                    val near = ridge(nx, horizon + h * 0.03f, h * 0.05f, 2.2f, 5.1f, 4.2f)
                    if (fy > far && fy < lakeEnd) { r = mix(r, 0.48f, 0.7f); g = mix(g, 0.56f, 0.7f); b = mix(b, 0.66f, 0.7f) }
                    if (fy > mid && fy < lakeEnd) { r = 0.30f; g = 0.42f; b = 0.36f }
                    if (fy > near && fy < lakeEnd) { r = 0.17f; g = 0.29f; b = 0.18f }

                    if (fy >= horizon + h * 0.05f && fy < lakeEnd) {
                        // Lake: the sky mirrored (horizon colors near the shore, deeper blue further out), darker, with ripples.
                        val lakeTop = horizon + h * 0.05f
                        val u = ((fy - lakeTop) / (lakeEnd - lakeTop)).coerceIn(0f, 1f)
                        val t2 = 1f - 0.85f * u
                        val ripple = 0.035f * sin(fy * 0.9f + sin(fx * 0.05f) * 2f)
                        r = 0.78f * lerp(0.18f, 0.96f, t2 * t2) + ripple
                        g = 0.78f * lerp(0.40f, 0.80f, t2) + ripple
                        b = 0.82f * lerp(0.74f, 0.62f, t2) + ripple
                        val dx = abs(fx - sunX)
                        val sparkle = exp(-dx / (w * 0.02f)) * (0.5f + 0.5f * sin(fy * 1.7f))
                        r += 0.5f * sparkle; g += 0.4f * sparkle; b += 0.2f * sparkle
                    }
                    if (fy >= lakeEnd) {
                        // Meadow.
                        val n = noise(x, y)
                        val depth = (fy - lakeEnd) / (chartTop - lakeEnd)
                        r = 0.24f + 0.22f * n + 0.10f * depth
                        g = 0.42f + 0.25f * n - 0.05f * depth
                        b = 0.12f + 0.08f * n
                        if (noise(x / 3, y / 3) > 0.94f) { r = 0.85f; g = 0.12f; b = 0.18f } // poppies
                        if (noise(x / 3 + 999, y / 3) > 0.96f) { r = 0.96f; g = 0.86f; b = 0.25f } // buttercups
                    }
                    // A red cabin with a dark roof and a lit window.
                    val cx0 = w * 0.16f; val cx1 = w * 0.30f
                    val cy0 = lakeEnd - h * 0.02f; val cy1 = lakeEnd + h * 0.09f
                    if (fx in cx0..cx1 && fy in cy0..cy1) {
                        r = 0.66f; g = 0.16f; b = 0.12f
                        if (fx in (cx0 + w * 0.03f)..(cx0 + w * 0.06f) && fy in (cy0 + h * 0.03f)..(cy0 + h * 0.065f)) { r = 1.0f; g = 0.82f; b = 0.48f }
                    }
                    val roofPeak = cy0 - h * 0.06f
                    val half = (cx1 - cx0) / 2f + w * 0.01f
                    val mx = (cx0 + cx1) / 2f
                    if (fy in roofPeak..cy0 && abs(fx - mx) < half * (fy - roofPeak) / (cy0 - roofPeak)) { r = 0.16f; g = 0.12f; b = 0.12f }
                } else {
                    // Color chart, then a grey ramp.
                    val rampTop = h * 0.95f
                    if (fy < rampTop) {
                        val cell = (fx / w * CHART.size).toInt().coerceIn(0, CHART.size - 1)
                        val inner = (fx / w * CHART.size) % 1f
                        val c = CHART[cell]
                        val edge = inner < 0.06f || inner > 0.94f || fy < chartTop + h * 0.008f
                        r = if (edge) 0.08f else ((c shr 16) and 0xFF) / 255f
                        g = if (edge) 0.08f else ((c shr 8) and 0xFF) / 255f
                        b = if (edge) 0.08f else (c and 0xFF) / 255f
                    } else {
                        val v = (fx / w * 11f).toInt() / 10f
                        r = v; g = v; b = v
                    }
                }
                out[y * w + x] = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
            }
        }
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun c(v: Float) = (min(1f, max(0f, v)) * 255f).toInt()

    /** Deterministic value noise in 0..1. */
    private fun noise(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) and 0xFFFF) / 65535f
    }
}
