package com.fujiapp.remote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.withTranslation
import com.fujiapp.remote.ui.LineIcon.Kind

/**
 * Layout pieces after the references (Hasselblad's app and camera screens, Leica's control grid):
 * a hero panel, a row of spec icons with captions, square setting tiles, thin line icons. The
 * palette, type and haze stay the app's own.
 */
object Layouts {
    /** Card corners, as in the references' rounded cards. */
    const val CARD_RADIUS = 13f

    /** A glass card that frames a camera, a picture or a readout. */
    fun hero(c: Context): LinearLayout = Kit.column(c).apply {
        // Glass, like every other panel: the haze, lights and grain show through.
        background = Deco.glass(c, CARD_RADIUS)
        val pad = Deco.dpi(c, 16)
        setPadding(pad, pad, pad, pad)
    }

    /** A title set wide, in capitals, like Hasselblad's: H 6 D - 1 0 0 C. */
    fun wideTitle(c: Context, text: String, sizeSp: Float = 20f, color: Int = Deco.TEXT): TextView =
        Kit.text(c, text.uppercase(), sizeSp, color).apply {
            letterSpacing = 0.32f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    /** One spec: a line icon, the value, a small caption, centred. */
    data class Spec(val icon: LineIcon.Kind, val value: String, val caption: String)

    /** Specs side by side, thin rules between (Hasselblad's "50 or 100 MP · Wi-Fi · ISO" row). */
    fun specRow(c: Context, specs: List<Spec>): View = Kit.row(c).apply {
        gravity = Gravity.TOP
        specs.forEachIndexed { i, s ->
            if (i > 0) addView(View(c).apply { setBackgroundColor(Deco.BORDER_SOFT) }, LinearLayout.LayoutParams(maxOf(1, Deco.dpi(c, 1)), Deco.dpi(c, 56)).apply { topMargin = Deco.dpi(c, 4) })
            addView(Kit.column(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(LineIcon(c, s.icon), LinearLayout.LayoutParams(Deco.dpi(c, 26), Deco.dpi(c, 26)))
                addView(Kit.text(c, s.value, 15f, Deco.TEXT, bold = true).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, Kit.lp(top = Deco.dpi(c, 8)))
                addView(Kit.label(c, s.caption, Deco.DIM, 8f).apply { gravity = Gravity.CENTER; maxLines = 1 }, Kit.lp(top = Deco.dpi(c, 4)))
            }, Kit.lp(0, weight = 1f))
        }
    }

    /**
     * A square tile: an icon (or a short value) in a rounded box, the caption under it, like
     * Leica's Format / Looks / Flash / Timer grid. Returns the tile and a setter for its value.
     */
    class Tile(c: Context, icon: LineIcon.Kind, caption: String, onClick: () -> Unit) {
        private val valueText = Kit.label(c, "", Deco.TEXT_SOFT, 8f).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val box = Kit.column(c).apply {
            gravity = Gravity.CENTER
            background = Deco.glassPressable(c, 11f)
            setPadding(Deco.dpi(c, 4), Deco.dpi(c, 8), Deco.dpi(c, 4), Deco.dpi(c, 6))
            addView(LineIcon(c, icon), LinearLayout.LayoutParams(Deco.dpi(c, 22), Deco.dpi(c, 22)))
            addView(valueText, Kit.lp(top = Deco.dpi(c, 4)))
            isClickable = true
            isFocusable = true
            contentDescription = caption
            setOnClickListener { onClick() }
        }
        val view: View = Kit.column(c).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(box, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 62)))
            addView(Kit.label(c, caption, Deco.DIM, 8f).apply { gravity = Gravity.CENTER; maxLines = 1 }, Kit.lp(top = Deco.dpi(c, 6)))
        }

        fun set(value: String) {
            valueText.text = value
        }

        fun enable(on: Boolean) {
            Kit.setEnabled(view, on)
            box.isEnabled = on // the box takes the taps
        }
    }

    /** A label above its value, as the references lay out their details (FROM / Jakarta). */
    fun field(c: Context, label: String, value: String, end: Boolean = false): View = Kit.column(c).apply {
        if (end) gravity = Gravity.END
        addView(Kit.label(c, label, Deco.DIM, 9f).apply { if (end) gravity = Gravity.END })
        addView(Kit.text(c, value, 15f, Deco.TEXT).apply {
            if (end) gravity = Gravity.END
            setTextIsSelectable(true)
        }, Kit.lp(top = Deco.dpi(c, 5)))
    }

    /**
     * Readings as the references lay out details: each label above its value, two to a line (a
     * long one takes the line), thin rules between lines.
     */
    fun fieldGrid(c: Context, rows: List<Pair<String, String>>): View = Kit.column(c).apply {
        val lines = mutableListOf<List<Pair<String, String>>>()
        var pending: Pair<String, String>? = null
        for (r in rows) {
            val wide = r.second.length > WIDE_VALUE || r.first.length > WIDE_LABEL
            when {
                wide -> {
                    pending?.let { lines += listOf(it) }
                    pending = null
                    lines += listOf(r)
                }
                pending == null -> pending = r
                else -> {
                    lines += listOf(pending, r)
                    pending = null
                }
            }
        }
        pending?.let { lines += listOf(it) }
        lines.forEachIndexed { i, line ->
            if (i > 0) addView(Kit.hairline(c))
            addView(Kit.row(c).apply {
                setPadding(0, Deco.dpi(c, 12), 0, Deco.dpi(c, 12))
                gravity = Gravity.TOP
                line.forEachIndexed { k, (label, value) ->
                    addView(field(c, label.uppercase(), value, end = k == 1), Kit.lp(0, weight = 1f))
                }
            })
        }
    }

    private const val WIDE_VALUE = 18
    private const val WIDE_LABEL = 20

    /** A section's title with an action on the right ("Saved trips · See more"). */
    fun header(c: Context, title: String, action: String? = null, onAction: (() -> Unit)? = null): View = Kit.row(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, Deco.dpi(c, 26), 0, Deco.dpi(c, 10))
        addView(Kit.sectionTitle(c, title).apply { setPadding(0, 0, 0, 0) }, Kit.lp(0, weight = 1f))
        if (action != null && onAction != null) {
            addView(Kit.label(c, action, Deco.MUTED, 10f).apply {
                isClickable = true
                setPadding(Deco.dpi(c, 8), Deco.dpi(c, 6), 0, Deco.dpi(c, 6))
                setOnClickListener { onAction() }
            })
        }
    }

    /** Tiles in a row of equal widths. */
    fun tileRow(c: Context, tiles: List<Tile>): View = Kit.row(c).apply {
        tiles.forEachIndexed { i, t ->
            addView(t.view, Kit.lp(0, weight = 1f).apply { if (i > 0) marginStart = Deco.dpi(c, 8) })
        }
    }
}

/**
 * The app's icon set, solid: filled shapes with their details cut out (a camera's lens, a film's
 * sprocket holes), and bold round-capped strokes for the few glyphs that are lines by nature
 * (chevrons, close, plus, check). One colour, drawn in code (no artwork), crisp at any size and
 * taking any tint. Used as a view ([LineIcon]) or inside buttons ([IconDrawable]).
 */
class IconDrawable(private val kind: LineIcon.Kind, color: Int, private val context: Context) : android.graphics.drawable.Drawable() {
    var lineColor: Int = color
        set(value) {
            field = value
            invalidateSelf()
        }

    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    /** The filled part, holes cut out; and the bold strokes. Built when the size changes. */
    private val shape = Path()
    private val strokes = Path()
    private var alphaValue = 255

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        build(bounds.width().toFloat(), bounds.height().toFloat())
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        solid.color = lineColor
        solid.alpha = alphaValue
        bold.color = lineColor
        bold.alpha = alphaValue
        canvas.withTranslation(b.left.toFloat(), b.top.toFloat()) {
            drawPath(shape, solid)
            drawPath(strokes, bold)
        }
    }

    // ---- building, in units of the icon's size from its centre ----
    private var s = 0f
    private var ox = 0f
    private var oy = 0f
    private fun x(u: Float) = ox + u * s
    private fun y(u: Float) = oy + u * s

    private fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = Path().apply {
        addRoundRect(RectF(x(l), y(t), x(r), y(b)), rad * s, rad * s, Path.Direction.CW)
    }

    private fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addCircle(x(cx), y(cy), r * s, Path.Direction.CW) }

    private fun poly(vararg pts: Float) = Path().apply {
        moveTo(x(pts[0]), y(pts[1]))
        var i = 2
        while (i < pts.size) {
            lineTo(x(pts[i]), y(pts[i + 1]))
            i += 2
        }
        close()
    }

    /** A bar of [w] from one point to another, as a filled shape (for slits cut into a disc). */
    private fun bar(x0: Float, y0: Float, x1: Float, y1: Float, w: Float): Path {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
        val nx = -dy / len * w / 2
        val ny = dx / len * w / 2
        return poly(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny)
    }

    private fun add(p: Path) = shape.op(p, Path.Op.UNION)
    private fun cut(p: Path) = shape.op(p, Path.Op.DIFFERENCE)
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
        strokes.moveTo(x(x0), y(y0))
        strokes.lineTo(x(x1), y(y1))
    }

    private fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
        strokes.arcTo(RectF(x(cx - r), y(cy - r), x(cx + r), y(cy + r)), start, sweep, true)
    }

    private fun build(w: Float, h: Float) {
        shape.reset()
        strokes.reset()
        if (w <= 0f || h <= 0f) return
        s = minOf(w, h)
        ox = w / 2
        oy = h / 2
        bold.strokeWidth = maxOf(2f, s * 0.12f)
        when (kind) {
            Kind.SHOTS -> {
                add(rr(-0.45f, -0.24f, 0.45f, 0.36f, 0.1f))
                add(poly(-0.2f, -0.23f, -0.12f, -0.38f, 0.12f, -0.38f, 0.2f, -0.23f))
                cut(circle(0f, 0.06f, 0.18f))
                add(circle(0f, 0.06f, 0.09f))
            }
            Kind.POWER -> {
                bold.strokeWidth = maxOf(2f, s * 0.13f)
                arc(0f, 0.04f, 0.36f, -55f, 290f)
                line(0f, -0.44f, 0f, -0.02f)
            }
            Kind.WIFI -> {
                // A fan of three bands, the camera's Wi-Fi.
                for (k in 1..2) arc(0f, 0.3f, 0.2f * k + 0.06f, -135f, 90f)
                add(circle(0f, 0.3f, 0.09f))
            }
            Kind.CHIP -> {
                add(rr(-0.28f, -0.28f, 0.28f, 0.28f, 0.07f))
                cut(rr(-0.11f, -0.11f, 0.11f, 0.11f, 0.03f))
                bold.strokeWidth = maxOf(1.5f, s * 0.08f)
                for (k in -1..1) {
                    val o = k * 0.14f
                    line(o, -0.3f, o, -0.44f); line(o, 0.3f, o, 0.44f)
                    line(-0.3f, o, -0.44f, o); line(0.3f, o, 0.44f, o)
                }
            }
            Kind.FILM -> {
                add(rr(-0.32f, -0.44f, 0.32f, 0.44f, 0.06f))
                cut(rr(-0.13f, -0.32f, 0.13f, 0.32f, 0.03f))
                for (k in -2..2) {
                    val c = k * 0.16f
                    cut(rr(-0.27f, c - 0.04f, -0.19f, c + 0.04f, 0.015f))
                    cut(rr(0.19f, c - 0.04f, 0.27f, c + 0.04f, 0.015f))
                }
            }
            Kind.WB -> {
                add(circle(0f, 0f, 0.21f))
                bold.strokeWidth = maxOf(1.8f, s * 0.1f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    line(c * 0.32f, sn * 0.32f, c * 0.44f, sn * 0.44f)
                }
            }
            Kind.FLASH -> add(poly(0.1f, -0.46f, -0.24f, 0.08f, 0f, 0.08f, -0.1f, 0.46f, 0.25f, -0.1f, 0.01f, -0.1f))
            Kind.TIMER -> {
                add(circle(0f, 0.07f, 0.37f))
                cut(rr(-0.045f, -0.16f, 0.045f, 0.11f, 0.045f))
                add(rr(-0.11f, -0.47f, 0.11f, -0.37f, 0.04f))
            }
            Kind.FOCUS -> {
                bold.strokeWidth = maxOf(2f, s * 0.11f)
                val e = 0.4f
                val l = 0.16f
                for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                    val px = dx * e
                    val py = dy * e
                    strokes.moveTo(x(px - dx * l), y(py))
                    strokes.lineTo(x(px), y(py))
                    strokes.lineTo(x(px), y(py - dy * l))
                }
                add(circle(0f, 0f, 0.1f))
            }
            Kind.ISO -> {
                add(rr(-0.45f, -0.28f, 0.45f, 0.28f, 0.08f))
                val text = Path()
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Deco.bold(context)
                    textSize = s * 0.3f
                    textAlign = Paint.Align.CENTER
                }
                tp.getTextPath("ISO", 0, 3, ox, oy + s * 0.105f, text)
                cut(text)
            }
            Kind.BATTERY -> {
                add(rr(-0.44f, -0.22f, 0.34f, 0.22f, 0.07f))
                add(rr(0.36f, -0.09f, 0.45f, 0.09f, 0.03f))
            }
            Kind.MACRO -> {
                for (k in 0 until 5) {
                    val a = Math.toRadians(k * 72.0 - 90)
                    add(circle(Math.cos(a).toFloat() * 0.17f, -0.1f + Math.sin(a).toFloat() * 0.17f, 0.13f))
                }
                cut(circle(0f, -0.1f, 0.06f))
                bold.strokeWidth = maxOf(1.8f, s * 0.09f)
                line(0f, 0.18f, 0f, 0.46f)
            }
            Kind.GAUGE -> {
                val ring = circle(0f, 0.1f, 0.45f).apply { op(circle(0f, 0.1f, 0.28f), Path.Op.DIFFERENCE) }
                val pie = Path().apply {
                    moveTo(x(0f), y(0.1f))
                    arcTo(RectF(x(-0.6f), y(-0.5f), x(0.6f), y(0.7f)), 160f, 220f)
                    close()
                }
                ring.op(pie, Path.Op.INTERSECT)
                add(ring)
                add(bar(0f, 0.1f, 0.19f, -0.11f, 0.09f))
                add(circle(0f, 0.1f, 0.08f))
            }
            Kind.ARCHIVE -> {
                add(rr(-0.45f, -0.38f, 0.45f, -0.15f, 0.06f))
                add(rr(-0.38f, -0.1f, 0.38f, 0.42f, 0.06f))
                cut(rr(-0.13f, 0.03f, 0.13f, 0.11f, 0.04f))
            }
            Kind.GEAR -> {
                add(circle(0f, 0f, 0.31f))
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    add(bar(Math.cos(a).toFloat() * 0.22f, Math.sin(a).toFloat() * 0.22f, Math.cos(a).toFloat() * 0.45f, Math.sin(a).toFloat() * 0.45f, 0.15f))
                }
                cut(circle(0f, 0f, 0.13f))
            }
            Kind.CHEVRON_LEFT -> {
                bold.strokeWidth = maxOf(2.4f, s * 0.15f)
                line(0.12f, -0.28f, -0.15f, 0f); line(-0.15f, 0f, 0.12f, 0.28f)
            }
            Kind.CHEVRON_RIGHT -> {
                bold.strokeWidth = maxOf(2.4f, s * 0.15f)
                line(-0.12f, -0.28f, 0.15f, 0f); line(0.15f, 0f, -0.12f, 0.28f)
            }
            Kind.CLOSE -> {
                bold.strokeWidth = maxOf(2.2f, s * 0.13f)
                line(-0.25f, -0.25f, 0.25f, 0.25f); line(0.25f, -0.25f, -0.25f, 0.25f)
            }
            Kind.PLUS -> {
                bold.strokeWidth = maxOf(2.2f, s * 0.13f)
                line(-0.3f, 0f, 0.3f, 0f); line(0f, -0.3f, 0f, 0.3f)
            }
            Kind.MINUS -> {
                bold.strokeWidth = maxOf(2.2f, s * 0.13f)
                line(-0.3f, 0f, 0.3f, 0f)
            }
            Kind.CHECK -> {
                bold.strokeWidth = maxOf(2.2f, s * 0.14f)
                strokes.moveTo(x(-0.3f), y(0.02f))
                strokes.lineTo(x(-0.08f), y(0.23f))
                strokes.lineTo(x(0.31f), y(-0.22f))
            }
            Kind.REFRESH -> {
                arc(0f, 0.02f, 0.32f, -50f, 290f)
                add(poly(0.1f, -0.44f, 0.4f, -0.3f, 0.14f, -0.12f))
            }
            Kind.SHARE -> {
                add(poly(0f, -0.46f, 0.2f, -0.24f, -0.2f, -0.24f))
                add(rr(-0.36f, -0.08f, 0.36f, 0.44f, 0.08f))
                cut(rr(-0.24f, -0.12f, 0.24f, 0.3f, 0.03f))
                add(rr(-0.055f, -0.3f, 0.055f, 0.12f, 0.03f))
            }
            Kind.TRASH -> {
                add(rr(-0.4f, -0.33f, 0.4f, -0.22f, 0.04f))
                add(rr(-0.13f, -0.45f, 0.13f, -0.3f, 0.04f))
                add(poly(-0.31f, -0.17f, 0.31f, -0.17f, 0.25f, 0.44f, -0.25f, 0.44f))
                cut(rr(-0.12f, -0.07f, -0.05f, 0.32f, 0.03f))
                cut(rr(0.05f, -0.07f, 0.12f, 0.32f, 0.03f))
            }
            Kind.DOWNLOAD, Kind.UPLOAD -> {
                val down = kind == Kind.DOWNLOAD
                if (down) {
                    add(rr(-0.07f, -0.44f, 0.07f, 0.0f, 0.04f))
                    add(poly(-0.24f, -0.04f, 0.24f, -0.04f, 0f, 0.22f))
                } else {
                    add(rr(-0.07f, -0.18f, 0.07f, 0.24f, 0.04f))
                    add(poly(-0.24f, -0.14f, 0.24f, -0.14f, 0f, -0.42f))
                }
                add(rr(-0.4f, 0.32f, 0.4f, 0.44f, 0.05f))
            }
            Kind.APERTURE -> {
                add(circle(0f, 0f, 0.45f))
                // Six blades: a hexagon in the middle and a slit from each corner to the rim.
                val hex = FloatArray(12)
                for (k in 0 until 6) {
                    val a = Math.toRadians(k * 60.0 + 30)
                    hex[2 * k] = Math.cos(a).toFloat() * 0.15f
                    hex[2 * k + 1] = Math.sin(a).toFloat() * 0.15f
                }
                cut(poly(*hex))
                for (k in 0 until 6) {
                    val t = Math.toRadians(k * 60.0 + 120)
                    val x0 = hex[2 * k]
                    val y0 = hex[2 * k + 1]
                    cut(bar(x0, y0, x0 + Math.cos(t).toFloat() * 0.5f, y0 + Math.sin(t).toFloat() * 0.5f, 0.05f))
                }
                shape.op(circle(0f, 0f, 0.45f), Path.Op.INTERSECT)
            }
            Kind.BLUETOOTH -> {
                bold.strokeWidth = maxOf(2f, s * 0.11f)
                strokes.moveTo(x(-0.22f), y(-0.2f))
                strokes.lineTo(x(0.2f), y(0.18f))
                strokes.lineTo(x(0f), y(0.42f))
                strokes.lineTo(x(0f), y(-0.42f))
                strokes.lineTo(x(0.2f), y(-0.18f))
                strokes.lineTo(x(-0.22f), y(0.2f))
            }
            Kind.LOG -> {
                add(rr(-0.33f, -0.43f, 0.33f, 0.43f, 0.08f))
                for (k in -1..1) cut(rr(-0.18f, k * 0.17f - 0.035f, 0.18f, k * 0.17f + 0.035f, 0.035f))
            }
            Kind.PIN -> {
                add(Path().apply {
                    moveTo(x(0f), y(0.45f))
                    cubicTo(x(-0.52f), y(-0.02f), x(-0.26f), y(-0.47f), x(0f), y(-0.44f))
                    cubicTo(x(0.26f), y(-0.47f), x(0.52f), y(-0.02f), x(0f), y(0.45f))
                    close()
                })
                cut(circle(0f, -0.12f, 0.12f))
            }
            Kind.LAB -> {
                add(poly(-0.12f, -0.36f, -0.12f, -0.08f, -0.38f, 0.38f, 0.38f, 0.38f, 0.12f, -0.08f, 0.12f, -0.36f))
                add(rr(-0.2f, -0.46f, 0.2f, -0.35f, 0.04f))
                cut(rr(-0.18f, 0.12f, 0.18f, 0.18f, 0.03f))
            }
            Kind.LIBRARY -> {
                add(rr(-0.4f, -0.38f, -0.24f, 0.4f, 0.04f))
                add(rr(-0.18f, -0.38f, -0.02f, 0.4f, 0.04f))
                add(poly(0.06f, -0.33f, 0.2f, -0.37f, 0.42f, 0.36f, 0.28f, 0.4f))
            }
            Kind.PHOTOS -> {
                add(rr(-0.45f, -0.35f, 0.45f, 0.35f, 0.09f))
                cut(rr(-0.33f, -0.23f, 0.33f, 0.23f, 0.03f))
                add(poly(-0.33f, 0.23f, -0.33f, 0.12f, -0.14f, -0.06f, 0.04f, 0.12f, 0.15f, 0.02f, 0.33f, 0.17f, 0.33f, 0.23f))
                add(circle(0.17f, -0.1f, 0.06f))
            }
            Kind.REMOTE -> {
                add(rr(-0.43f, -0.2f, 0.43f, 0.36f, 0.1f))
                cut(circle(0f, 0.08f, 0.15f))
                add(circle(0f, 0.08f, 0.07f))
                add(rr(-0.18f, -0.42f, 0.18f, -0.31f, 0.04f))
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        solid.colorFilter = colorFilter
        bold.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** An icon from the set as a view; it draws inside its padding, so a bubble background can frame it. */
@SuppressLint("ViewConstructor") // made in code only
class LineIcon(c: Context, kind: Kind, color: Int = Deco.AMBER_LIGHT) : View(c) {
    enum class Kind {
        SHOTS, POWER, WIFI, CHIP, FILM, WB, FLASH, TIMER, FOCUS, ISO, BATTERY, MACRO, GAUGE, ARCHIVE, GEAR,
        CHEVRON_LEFT, CHEVRON_RIGHT, CLOSE, PLUS, MINUS, REFRESH, SHARE, TRASH, DOWNLOAD, UPLOAD, APERTURE,
        BLUETOOTH, LOG, PIN, LAB, LIBRARY, CHECK, PHOTOS, REMOTE,
    }

    private val icon = IconDrawable(kind, color, c)

    /** The line colour (the tab bar dims the icons it isn't on). */
    var tint: Int
        get() = icon.lineColor
        set(value) {
            icon.lineColor = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        icon.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        icon.draw(canvas)
    }
}

/**
 * A ticket's tear line: dashes across, a half-round notch at each end where it meets the card's
 * edge (the references' boarding-pass cards). Set its side margins to minus the card's padding.
 */
@SuppressLint("ViewConstructor") // made in code only
class TicketDivider(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val notch = h / 2
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1f, h * 0.06f)
        p.color = Deco.BORDER_STRONG
        val dash = h * 0.35f
        var x = notch * 2
        while (x < w - notch * 2) {
            canvas.drawLine(x, h / 2, minOf(x + dash, w - notch * 2), h / 2, p)
            x += dash * 2
        }
        // The notches: the page's night showing through, rimmed like the card.
        p.style = Paint.Style.FILL
        p.color = Deco.BG
        canvas.drawCircle(0f, h / 2, notch, p)
        canvas.drawCircle(w, h / 2, notch, p)
        p.style = Paint.Style.STROKE
        p.color = Deco.BORDER
        r.set(-notch, 0f, notch, h)
        canvas.drawArc(r, -90f, 180f, false, p)
        r.set(w - notch, 0f, w + notch, h)
        canvas.drawArc(r, 90f, 180f, false, p)
    }
}
