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

/**
 * Layout pieces after the references (Hasselblad's app and camera screens, Leica's control grid):
 * a hero panel, a row of spec icons with captions, square setting tiles, thin line icons. The
 * palette, type and haze stay the app's own.
 */
object Layouts {
    /** A dark, square-cornered panel that frames a camera, a picture or a readout. */
    fun hero(c: Context): LinearLayout = Kit.column(c).apply {
        background = Deco.box(c, Deco.PANEL, Deco.BORDER_SOFT, 6f)
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
            background = Deco.pressable(Deco.box(c, Deco.WELL, Deco.BORDER, 12f), Deco.box(c, Deco.SURFACE_HI, Deco.BORDER_STRONG, 12f))
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
            addView(box, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 64)))
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

    /** Tiles in a row of equal widths. */
    fun tileRow(c: Context, tiles: List<Tile>): View = Kit.row(c).apply {
        tiles.forEachIndexed { i, t ->
            addView(t.view, Kit.lp(0, weight = 1f).apply { if (i > 0) marginStart = Deco.dpi(c, 8) })
        }
    }
}

/** A thin line icon in the app's amber, drawn in code (no artwork). */
@SuppressLint("ViewConstructor") // made in code only
class LineIcon(c: Context, private val kind: Kind, private val color: Int = Deco.AMBER_LIGHT) : View(c) {
    enum class Kind { SHOTS, POWER, WIFI, CHIP, FILM, WB, FLASH, TIMER, FOCUS, ISO, BATTERY, MACRO }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val r = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        val cx = w / 2
        val cy = h / 2
        p.color = color
        p.strokeWidth = maxOf(1.5f, s * 0.07f)
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
            Kind.MACRO -> {
                for (k in 0 until 5) {
                    val a = Math.toRadians(k * 72.0 - 90)
                    canvas.drawCircle(cx + Math.cos(a).toFloat() * s * 0.18f, cy - s * 0.08f + Math.sin(a).toFloat() * s * 0.18f, s * 0.12f, p)
                }
                canvas.drawLine(cx, cy + s * 0.16f, cx, cy + s * 0.44f, p)
            }
        }
    }
}
