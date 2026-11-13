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
import com.fujiapp.remote.ui.LineIcon.Kind

/**
 * Layout pieces after the references (Hasselblad's app and camera screens, Leica's control grid):
 * a hero panel, a row of spec icons with captions, square setting tiles, thin line icons. The
 * palette, type and haze stay the app's own.
 */
object Layouts {
    /** Card corners, as in the references' rounded cards. */
    const val CARD_RADIUS = 16f

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
            background = Deco.glassPressable(c, 14f)
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
 * The app's icon set: thin round-capped lines in one colour, drawn in code (no artwork), so they stay
 * crisp at any size and take any tint. Used as a view ([LineIcon]) or inside buttons ([IconDrawable]).
 */
class IconDrawable(private val kind: LineIcon.Kind, color: Int, private val context: Context) : android.graphics.drawable.Drawable() {
    var lineColor: Int = color
        set(value) {
            field = value
            invalidateSelf()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val r = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h)
        val cx = b.left + w / 2
        val cy = b.top + h / 2
        p.color = lineColor
        p.alpha = alphaValue
        // Chevrons on a bubble are the thumb's target: drawn a little bolder than the rest of the set.
        p.strokeWidth = if (kind == Kind.CHEVRON_LEFT || kind == Kind.CHEVRON_RIGHT) maxOf(2f, s * 0.11f) else maxOf(1.5f, s * 0.07f)
        p.style = Paint.Style.STROKE
        when (kind) {
            Kind.SHOTS -> {
                r.set(cx - s * 0.42f, cy - s * 0.3f, cx + s * 0.42f, cy + s * 0.34f)
                canvas.drawRoundRect(r, s * 0.08f, s * 0.08f, p)
                canvas.drawCircle(cx, cy + s * 0.03f, s * 0.15f, p)
                canvas.drawLine(cx - s * 0.18f, cy - s * 0.3f, cx - s * 0.1f, cy - s * 0.42f, p)
                canvas.drawLine(cx - s * 0.1f, cy - s * 0.42f, cx + s * 0.1f, cy - s * 0.42f, p)
                canvas.drawLine(cx + s * 0.1f, cy - s * 0.42f, cx + s * 0.18f, cy - s * 0.3f, p)
            }
            Kind.POWER -> {
                r.set(cx - s * 0.36f, cy - s * 0.32f, cx + s * 0.36f, cy + s * 0.4f)
                canvas.drawArc(r, -60f, 300f, false, p)
                canvas.drawLine(cx, cy - s * 0.44f, cx, cy, p)
            }
            Kind.WIFI -> {
                for (k in 1..3) {
                    val rad = s * 0.14f * k
                    r.set(cx - rad, cy + s * 0.28f - rad, cx + rad, cy + s * 0.28f + rad)
                    canvas.drawArc(r, -135f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy + s * 0.28f, s * 0.05f, p)
            }
            Kind.CHIP -> {
                r.set(cx - s * 0.26f, cy - s * 0.26f, cx + s * 0.26f, cy + s * 0.26f)
                canvas.drawRect(r, p)
                for (k in -1..1) {
                    val o = k * s * 0.14f
                    canvas.drawLine(cx + o, cy - s * 0.26f, cx + o, cy - s * 0.42f, p)
                    canvas.drawLine(cx + o, cy + s * 0.26f, cx + o, cy + s * 0.42f, p)
                    canvas.drawLine(cx - s * 0.26f, cy + o, cx - s * 0.42f, cy + o, p)
                    canvas.drawLine(cx + s * 0.26f, cy + o, cx + s * 0.42f, cy + o, p)
                }
            }
            Kind.FILM -> {
                r.set(cx - s * 0.3f, cy - s * 0.42f, cx + s * 0.3f, cy + s * 0.42f)
                canvas.drawRect(r, p)
                canvas.drawLine(cx - s * 0.16f, cy - s * 0.42f, cx - s * 0.16f, cy + s * 0.42f, p)
                canvas.drawLine(cx + s * 0.16f, cy - s * 0.42f, cx + s * 0.16f, cy + s * 0.42f, p)
                p.style = Paint.Style.FILL
                for (k in -2..2) {
                    canvas.drawCircle(cx - s * 0.23f, cy + k * s * 0.16f, s * 0.03f, p)
                    canvas.drawCircle(cx + s * 0.23f, cy + k * s * 0.16f, s * 0.03f, p)
                }
            }
            Kind.WB -> {
                canvas.drawCircle(cx, cy, s * 0.16f, p)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val x0 = cx + Math.cos(a).toFloat() * s * 0.27f
                    val y0 = cy + Math.sin(a).toFloat() * s * 0.27f
                    val x1 = cx + Math.cos(a).toFloat() * s * 0.42f
                    val y1 = cy + Math.sin(a).toFloat() * s * 0.42f
                    canvas.drawLine(x0, y0, x1, y1, p)
                }
            }
            Kind.FLASH -> {
                path.reset()
                path.moveTo(cx + s * 0.08f, cy - s * 0.44f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.06f)
                path.lineTo(cx + s * 0.02f, cy + s * 0.06f)
                path.lineTo(cx - s * 0.08f, cy + s * 0.44f)
                path.lineTo(cx + s * 0.22f, cy - s * 0.08f)
                path.lineTo(cx, cy - s * 0.08f)
                path.close()
                canvas.drawPath(path, p)
            }
            Kind.TIMER -> {
                canvas.drawCircle(cx, cy + s * 0.06f, s * 0.34f, p)
                canvas.drawLine(cx, cy + s * 0.06f, cx, cy - s * 0.14f, p)
                canvas.drawLine(cx, cy + s * 0.06f, cx + s * 0.14f, cy + s * 0.12f, p)
                canvas.drawLine(cx - s * 0.1f, cy - s * 0.44f, cx + s * 0.1f, cy - s * 0.44f, p)
            }
            Kind.FOCUS -> {
                val e = s * 0.4f
                val l = s * 0.16f
                for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                    val x = cx + dx * e
                    val y = cy + dy * e
                    canvas.drawLine(x, y, x - dx * l, y, p)
                    canvas.drawLine(x, y, x, y - dy * l, p)
                }
                canvas.drawCircle(cx, cy, s * 0.05f, p)
            }
            Kind.ISO -> {
                r.set(cx - s * 0.42f, cy - s * 0.26f, cx + s * 0.42f, cy + s * 0.26f)
                canvas.drawRoundRect(r, s * 0.06f, s * 0.06f, p)
                p.style = Paint.Style.FILL
                p.textSize = s * 0.3f
                p.textAlign = Paint.Align.CENTER
                p.typeface = Deco.bold(context)
                canvas.drawText("ISO", cx, cy + s * 0.11f, p)
            }
            Kind.BATTERY -> {
                r.set(cx - s * 0.4f, cy - s * 0.2f, cx + s * 0.32f, cy + s * 0.2f)
                canvas.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
                canvas.drawLine(cx + s * 0.4f, cy - s * 0.08f, cx + s * 0.4f, cy + s * 0.08f, p)
            }
            Kind.GAUGE -> {
                r.set(cx - s * 0.4f, cy - s * 0.32f, cx + s * 0.4f, cy + s * 0.48f)
                canvas.drawArc(r, 160f, 220f, false, p)
                canvas.drawLine(cx, cy + s * 0.08f, cx + s * 0.2f, cy - s * 0.14f, p)
                canvas.drawCircle(cx, cy + s * 0.08f, s * 0.05f, p)
            }
            Kind.ARCHIVE -> {
                r.set(cx - s * 0.42f, cy - s * 0.36f, cx + s * 0.42f, cy - s * 0.12f)
                canvas.drawRoundRect(r, s * 0.04f, s * 0.04f, p)
                r.set(cx - s * 0.36f, cy - s * 0.12f, cx + s * 0.36f, cy + s * 0.4f)
                canvas.drawRoundRect(r, s * 0.04f, s * 0.04f, p)
                canvas.drawLine(cx - s * 0.12f, cy + s * 0.04f, cx + s * 0.12f, cy + s * 0.04f, p)
            }
            Kind.GEAR -> {
                canvas.drawCircle(cx, cy, s * 0.14f, p)
                canvas.drawCircle(cx, cy, s * 0.3f, p)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    canvas.drawLine(
                        cx + Math.cos(a).toFloat() * s * 0.3f, cy + Math.sin(a).toFloat() * s * 0.3f,
                        cx + Math.cos(a).toFloat() * s * 0.42f, cy + Math.sin(a).toFloat() * s * 0.42f, p,
                    )
                }
            }
            Kind.CHEVRON_LEFT -> {
                canvas.drawLine(cx + s * 0.12f, cy - s * 0.28f, cx - s * 0.16f, cy, p)
                canvas.drawLine(cx - s * 0.16f, cy, cx + s * 0.12f, cy + s * 0.28f, p)
            }
            Kind.CHEVRON_RIGHT -> {
                canvas.drawLine(cx - s * 0.12f, cy - s * 0.28f, cx + s * 0.16f, cy, p)
                canvas.drawLine(cx + s * 0.16f, cy, cx - s * 0.12f, cy + s * 0.28f, p)
            }
            Kind.CLOSE -> {
                canvas.drawLine(cx - s * 0.24f, cy - s * 0.24f, cx + s * 0.24f, cy + s * 0.24f, p)
                canvas.drawLine(cx + s * 0.24f, cy - s * 0.24f, cx - s * 0.24f, cy + s * 0.24f, p)
            }
            Kind.PLUS -> {
                canvas.drawLine(cx - s * 0.28f, cy, cx + s * 0.28f, cy, p)
                canvas.drawLine(cx, cy - s * 0.28f, cx, cy + s * 0.28f, p)
            }
            Kind.MINUS -> canvas.drawLine(cx - s * 0.28f, cy, cx + s * 0.28f, cy, p)
            Kind.CHECK -> {
                canvas.drawLine(cx - s * 0.28f, cy + s * 0.02f, cx - s * 0.08f, cy + s * 0.22f, p)
                canvas.drawLine(cx - s * 0.08f, cy + s * 0.22f, cx + s * 0.3f, cy - s * 0.22f, p)
            }
            Kind.REFRESH -> {
                r.set(cx - s * 0.32f, cy - s * 0.32f, cx + s * 0.32f, cy + s * 0.32f)
                canvas.drawArc(r, -60f, 300f, false, p)
                val ax = cx + s * 0.16f
                val ay = cy - s * 0.28f
                canvas.drawLine(ax, ay, ax + s * 0.16f, ay - s * 0.06f, p)
                canvas.drawLine(ax, ay, ax + s * 0.06f, ay + s * 0.15f, p)
            }
            Kind.SHARE -> {
                canvas.drawLine(cx, cy + s * 0.1f, cx, cy - s * 0.4f, p)
                canvas.drawLine(cx, cy - s * 0.4f, cx - s * 0.14f, cy - s * 0.26f, p)
                canvas.drawLine(cx, cy - s * 0.4f, cx + s * 0.14f, cy - s * 0.26f, p)
                path.reset()
                path.moveTo(cx - s * 0.14f, cy - s * 0.12f)
                path.lineTo(cx - s * 0.32f, cy - s * 0.12f)
                path.lineTo(cx - s * 0.32f, cy + s * 0.4f)
                path.lineTo(cx + s * 0.32f, cy + s * 0.4f)
                path.lineTo(cx + s * 0.32f, cy - s * 0.12f)
                path.lineTo(cx + s * 0.14f, cy - s * 0.12f)
                canvas.drawPath(path, p)
            }
            Kind.TRASH -> {
                canvas.drawLine(cx - s * 0.34f, cy - s * 0.26f, cx + s * 0.34f, cy - s * 0.26f, p)
                canvas.drawLine(cx - s * 0.1f, cy - s * 0.4f, cx + s * 0.1f, cy - s * 0.4f, p)
                path.reset()
                path.moveTo(cx - s * 0.26f, cy - s * 0.26f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.4f)
                path.lineTo(cx + s * 0.2f, cy + s * 0.4f)
                path.lineTo(cx + s * 0.26f, cy - s * 0.26f)
                canvas.drawPath(path, p)
            }
            Kind.DOWNLOAD, Kind.UPLOAD -> {
                val down = kind == Kind.DOWNLOAD
                val tip = if (down) cy + s * 0.14f else cy - s * 0.36f
                val tail = if (down) cy - s * 0.36f else cy + s * 0.14f
                canvas.drawLine(cx, tail, cx, tip, p)
                val back = if (down) -s * 0.16f else s * 0.16f
                canvas.drawLine(cx, tip, cx - s * 0.16f, tip + back, p)
                canvas.drawLine(cx, tip, cx + s * 0.16f, tip + back, p)
                canvas.drawLine(cx - s * 0.34f, cy + s * 0.38f, cx + s * 0.34f, cy + s * 0.38f, p)
            }
            Kind.APERTURE -> {
                canvas.drawCircle(cx, cy, s * 0.4f, p)
                for (k in 0 until 6) {
                    val a = Math.toRadians(k * 60.0)
                    val a2 = Math.toRadians(k * 60.0 + 100.0)
                    canvas.drawLine(
                        cx + Math.cos(a).toFloat() * s * 0.4f, cy + Math.sin(a).toFloat() * s * 0.4f,
                        cx + Math.cos(a2).toFloat() * s * 0.16f, cy + Math.sin(a2).toFloat() * s * 0.16f, p,
                    )
                }
            }
            Kind.BLUETOOTH -> {
                path.reset()
                path.moveTo(cx - s * 0.2f, cy - s * 0.18f)
                path.lineTo(cx + s * 0.18f, cy + s * 0.18f)
                path.lineTo(cx, cy + s * 0.4f)
                path.lineTo(cx, cy - s * 0.4f)
                path.lineTo(cx + s * 0.18f, cy - s * 0.18f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.18f)
                canvas.drawPath(path, p)
            }
            Kind.LOG -> {
                r.set(cx - s * 0.3f, cy - s * 0.4f, cx + s * 0.3f, cy + s * 0.4f)
                canvas.drawRoundRect(r, s * 0.06f, s * 0.06f, p)
                for (k in -1..1) canvas.drawLine(cx - s * 0.16f, cy + k * s * 0.16f, cx + s * 0.16f, cy + k * s * 0.16f, p)
            }
            Kind.PIN -> {
                canvas.drawCircle(cx, cy - s * 0.12f, s * 0.12f, p)
                path.reset()
                path.moveTo(cx, cy + s * 0.42f)
                path.cubicTo(cx - s * 0.5f, cy - s * 0.05f, cx - s * 0.22f, cy - s * 0.45f, cx, cy - s * 0.42f)
                path.cubicTo(cx + s * 0.22f, cy - s * 0.45f, cx + s * 0.5f, cy - s * 0.05f, cx, cy + s * 0.42f)
                canvas.drawPath(path, p)
            }
            Kind.LAB -> {
                path.reset()
                path.moveTo(cx - s * 0.1f, cy - s * 0.4f)
                path.lineTo(cx - s * 0.1f, cy - s * 0.08f)
                path.lineTo(cx - s * 0.34f, cy + s * 0.36f)
                path.lineTo(cx + s * 0.34f, cy + s * 0.36f)
                path.lineTo(cx + s * 0.1f, cy - s * 0.08f)
                path.lineTo(cx + s * 0.1f, cy - s * 0.4f)
                canvas.drawPath(path, p)
                canvas.drawLine(cx - s * 0.2f, cy + s * 0.16f, cx + s * 0.2f, cy + s * 0.16f, p)
            }
            Kind.LIBRARY -> {
                for (k in 0..2) {
                    val x = cx - s * 0.3f + k * s * 0.2f
                    canvas.drawLine(x, cy - s * 0.36f, x, cy + s * 0.36f, p)
                }
                canvas.drawLine(cx + s * 0.16f, cy - s * 0.34f, cx + s * 0.36f, cy + s * 0.34f, p)
            }
            Kind.PHOTOS -> {
                r.set(cx - s * 0.42f, cy - s * 0.32f, cx + s * 0.42f, cy + s * 0.32f)
                canvas.drawRoundRect(r, s * 0.08f, s * 0.08f, p)
                path.reset()
                path.moveTo(cx - s * 0.42f, cy + s * 0.2f)
                path.lineTo(cx - s * 0.16f, cy - s * 0.04f)
                path.lineTo(cx + s * 0.04f, cy + s * 0.14f)
                path.lineTo(cx + s * 0.18f, cy + s * 0.02f)
                path.lineTo(cx + s * 0.42f, cy + s * 0.24f)
                canvas.drawPath(path, p)
                canvas.drawCircle(cx + s * 0.18f, cy - s * 0.14f, s * 0.06f, p)
            }
            Kind.REMOTE -> {
                r.set(cx - s * 0.4f, cy - s * 0.2f, cx + s * 0.4f, cy + s * 0.34f)
                canvas.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                canvas.drawCircle(cx, cy + s * 0.07f, s * 0.13f, p)
                canvas.drawLine(cx - s * 0.16f, cy - s * 0.38f, cx + s * 0.16f, cy - s * 0.38f, p)
            }
            Kind.MACRO -> {
                for (k in 0 until 5) {
                    val a = Math.toRadians(k * 72.0 - 90)
                    canvas.drawCircle(cx + Math.cos(a).toFloat() * s * 0.18f, cy - s * 0.08f + Math.sin(a).toFloat() * s * 0.18f, s * 0.12f, p)
                }
                canvas.drawLine(cx, cy + s * 0.16f, cx, cy + s * 0.44f, p)
            }
        }
    }

    private var alphaValue = 255
    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        p.colorFilter = colorFilter
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
