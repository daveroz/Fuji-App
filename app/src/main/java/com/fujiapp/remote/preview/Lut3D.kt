package com.fujiapp.remote.preview

import com.fujiapp.remote.recipe.FilmSim
import java.util.concurrent.ConcurrentHashMap

/**
 * A 3D color lookup table: [size]³ RGB entries, red changing fastest (the `.cube` order), sampled
 * with tetrahedral interpolation. Input and output are sRGB values in 0…1 (inputs are first mapped
 * from the table's domain).
 */
class Lut3D(
    val size: Int,
    private val data: FloatArray,
    private val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    private val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init {
        require(size >= 2) { "A 3D LUT needs at least 2 points per side" }
        require(data.size == size * size * size * 3) { "A ${size}³ LUT needs ${size * size * size} entries, not ${data.size / 3}" }
        require((0..2).all { domainMax[it] > domainMin[it] }) { "The LUT's domain is empty" }
    }

    /** Look up ([r], [g], [b]) and write the result into [out] (three values). */
    fun apply(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        val fr = scaled(r, 0) * n
        val fg = scaled(g, 1) * n
        val fb = scaled(b, 2) * n
        val r0 = minOf(fr.toInt(), n - 1)
        val g0 = minOf(fg.toInt(), n - 1)
        val b0 = minOf(fb.toInt(), n - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        val sr = 3
        val sg = size * 3
        val sb = size * size * 3
        val c000 = (b0 * size * size + g0 * size + r0) * 3
        val c100 = c000 + sr
        val c010 = c000 + sg
        val c001 = c000 + sb
        val c110 = c000 + sr + sg
        val c101 = c000 + sr + sb
        val c011 = c000 + sg + sb
        val c111 = c000 + sr + sg + sb
        // The cube splits into six tetrahedra along its diagonal; pick the one the point is in.
        for (k in 0..2) {
            val v000 = data[c000 + k]
            val v111 = data[c111 + k]
            out[k] = if (dr >= dg) {
                if (dg >= db) {
                    v000 + dr * (data[c100 + k] - v000) + dg * (data[c110 + k] - data[c100 + k]) + db * (v111 - data[c110 + k])
                } else if (dr >= db) {
                    v000 + dr * (data[c100 + k] - v000) + db * (data[c101 + k] - data[c100 + k]) + dg * (v111 - data[c101 + k])
                } else {
                    v000 + db * (data[c001 + k] - v000) + dr * (data[c101 + k] - data[c001 + k]) + dg * (v111 - data[c101 + k])
                }
            } else {
                if (db >= dg) {
                    v000 + db * (data[c001 + k] - v000) + dg * (data[c011 + k] - data[c001 + k]) + dr * (v111 - data[c011 + k])
                } else if (db >= dr) {
                    v000 + dg * (data[c010 + k] - v000) + db * (data[c011 + k] - data[c010 + k]) + dr * (v111 - data[c011 + k])
                } else {
                    v000 + dg * (data[c010 + k] - v000) + dr * (data[c110 + k] - data[c010 + k]) + db * (v111 - data[c110 + k])
                }
            }
        }
    }

    private fun scaled(v: Float, k: Int): Float = ((v - domainMin[k]) / (domainMax[k] - domainMin[k])).coerceIn(0f, 1f)

    companion object {
        /** A table that changes nothing. */
        fun identity(size: Int): Lut3D {
            val data = FloatArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                data[i++] = r.toFloat() / (size - 1)
                data[i++] = g.toFloat() / (size - 1)
                data[i++] = b.toFloat() / (size - 1)
            }
            return Lut3D(size, data)
        }

        /** Read an Adobe/Resolve `.cube` 3D LUT. Throws [IllegalArgumentException] if it isn't one. */
        fun parseCube(text: String): Lut3D {
            var size = 0
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            var data: FloatArray? = null
            var n = 0
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> Unit
                    "LUT_3D_SIZE" -> {
                        size = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Bad LUT_3D_SIZE")
                        require(size in 2..256) { "LUT_3D_SIZE $size is out of range" }
                        data = FloatArray(size * size * size * 3)
                    }
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("A 1D LUT, not a 3D one")
                    "DOMAIN_MIN" -> for (k in 0..2) min[k] = parts.getOrNull(k + 1)?.toFloatOrNull() ?: throw IllegalArgumentException("Bad DOMAIN_MIN")
                    "DOMAIN_MAX" -> for (k in 0..2) max[k] = parts.getOrNull(k + 1)?.toFloatOrNull() ?: throw IllegalArgumentException("Bad DOMAIN_MAX")
                    else -> {
                        val d = data ?: throw IllegalArgumentException("Data before LUT_3D_SIZE")
                        require(parts.size == 3) { "Bad line: $line" }
                        require(n + 3 <= d.size) { "More entries than LUT_3D_SIZE says" }
                        for (k in 0..2) d[n++] = parts[k].toFloatOrNull() ?: throw IllegalArgumentException("Bad number: ${parts[k]}")
                    }
                }
            }
            val d = data ?: throw IllegalArgumentException("No LUT_3D_SIZE")
            require(n == d.size) { "${n / 3} entries, ${d.size / 3} expected" }
            return Lut3D(size, d, min, max)
        }
    }
}

/**
 * Measured film simulation LUTs, when there are any: a LUT fitted from the camera's own renderings
 * replaces the preview's parametric color and contrast for that simulation (the rest of the
 * recipe still applies on top). None ship yet; the app loads `assets/luts/<FILM_SIM>.cube`.
 */
object FilmLuts {
    private val luts = ConcurrentHashMap<FilmSim, Lut3D>()

    fun get(sim: FilmSim): Lut3D? = luts[sim]
    fun put(sim: FilmSim, lut: Lut3D) { luts[sim] = lut }
    fun remove(sim: FilmSim) { luts.remove(sim) }
    val loaded: Set<FilmSim> get() = luts.keys.toSet()

    /** The simulation an asset file is for: `CLASSIC_CHROME.cube` → Classic Chrome. */
    fun simFor(fileName: String): FilmSim? {
        if (!fileName.endsWith(".cube", ignoreCase = true)) return null
        val stem = fileName.dropLast(5)
        return FilmSim.entries.firstOrNull { it.name.equals(stem, ignoreCase = true) }
    }
}
