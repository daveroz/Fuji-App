package com.fujiapp.remote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.TextView

/**
 * The Deco "light in fog" visual language, shared with Worldly, the Deco launcher and SAB Monitor:
 * a night stage in haze, glass panels with hairline edges, and one amber key light. Something
 * that's on is "lit" (a touch of the key on the glass with a glowing key edge) rather than a solid
 * block, and small labels are spaced bold capitals, like markings on a piece of equipment.
 *
 * Layout follows medium-format camera software: generous margins, a restrained monochrome page,
 * large numerals for the readings that matter, small capital labels and hairline rules.
 *
 * Type is Carbon+, as in Worldly, SAB Monitor and the Deco launcher (`assets/fonts/`). If a file
 * can't be loaded, the system sans-serif stands in.
 */
object Deco {
    // surfaces (darkest to lightest)
    const val BG = 0xFF060708.toInt()
    const val PANEL = 0xFF0E0F12.toInt()
    const val SURFACE = 0xFF111215.toInt()
    const val SURFACE_HI = 0xFF17181C.toInt()
    /** Glass: the night at 72%, over the light. */
    const val GLASS = 0xCC0F1013.toInt()
    /** A well inside glass: darker, still see-through. */
    const val WELL = 0x8C000000.toInt()
    /** Glass, top to bottom: lit edge, body, depth; its rim and sheen. */
    const val GLASS_TOP = 0xA61A2333.toInt()
    const val GLASS_DEEP = 0xC405080D.toInt()
    const val GLASS_RIM = 0x33C8DCFF
    const val SHEEN = 0x14FFFFFF
    /** A bubble's pooled light and its depth. */
    const val BUBBLE_LIGHT = 0x4D3A4A60
    const val BUBBLE_DEEP = 0xCC0A0F18.toInt()
    // hairlines: white at low strength, an edge of light rather than a line of paint
    const val BORDER_SOFT = 0x14FFFFFF
    const val BORDER = 0x1FFFFFFF
    const val BORDER_STRONG = 0x33FFFFFF
    // text
    const val TEXT = 0xFFF2F3F5.toInt()
    const val TEXT_SOFT = 0xFFCDD0D6.toInt()
    const val MUTED = 0xFF9EA2AA.toInt()
    const val DIM = 0xFF6E727B.toInt()
    // the key light
    const val AMBER = 0xFFEB9438.toInt()
    const val AMBER_DEEP = 0xFFC97624.toInt()
    const val AMBER_LIGHT = 0xFFF0AE6A.toInt()
    const val KEY_GLASS = 0x24EB9438
    const val KEY_GLASS_HI = 0x3DEB9438
    const val KEY_EDGE = 0x99EB9438.toInt()
    const val GREEN = 0xFF34D399.toInt()
    const val RED = 0xFFF2766B.toInt()

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    private fun load(c: Context, asset: String, fallback: Typeface): Typeface =
        runCatching { Typeface.createFromAsset(c.assets, asset) }.getOrDefault(fallback)

    fun regular(c: Context): Typeface =
        regular ?: load(c.applicationContext, "fonts/carbonplus.otf", Typeface.create("sans-serif", Typeface.NORMAL)).also { regular = it }

    fun bold(c: Context): Typeface =
        bold ?: load(c.applicationContext, "fonts/carbonplus-Bold.otf", Typeface.create("sans-serif-medium", Typeface.NORMAL)).also { bold = it }

    fun dp(c: Context, v: Float): Float = v * c.resources.displayMetrics.density
    fun dpi(c: Context, v: Int): Int = Math.round(dp(c, v.toFloat()))

    /** A rounded box with an optional 1dp border (stroke = 0 for none). */
    fun box(c: Context, fill: Int, stroke: Int, radiusDp: Float): Drawable =
        RoundedDrawable(fill, stroke, if (stroke != 0) Math.max(1f, dp(c, 1f)) else 0f, dp(c, radiusDp))

    /**
     * Liquid glass (see [GlassDrawable]): a pane of the night that lets the stage through and picks
     * up its light, a sheen along the top, grain, and a rim warm where the key light falls.
     * Rounded corners: [radiusDp] < 0 takes a control's corner (12 dp).
     */
    fun glass(c: Context, radiusDp: Float, tone: GlassDrawable.Tone = GlassDrawable.Tone.PANE, pressed: Boolean = false): Drawable =
        GlassDrawable(c, if (radiusDp < 0) -1f else dp(c, radiusDp), tone = tone, pressed = pressed)

    /** Glass that's pressed: the key light on it. */
    fun glassPressable(c: Context, radiusDp: Float, tone: GlassDrawable.Tone = GlassDrawable.Tone.PANE): Drawable =
        pressable(glass(c, radiusDp, tone), glass(c, radiusDp, tone, pressed = true))

    /** A bubble: a small square of glass with full rounded corners; pressed, it takes the key light. */
    @Suppress("UNUSED_PARAMETER") // the size comes from the bounds
    fun bubble(c: Context, sizeDp: Float, pressed: Boolean = false): Drawable =
        GlassDrawable(c, 0f, round = true, pressed = pressed)

    /** The key action: solid amber, filling its bounds. Text on it is [KeyDrawable.INK]. */
    fun key(c: Context, radiusDp: Float): Drawable =
        pressable(KeyDrawable(c, if (radiusDp < 0) -1f else dp(c, radiusDp)), KeyDrawable(c, if (radiusDp < 0) -1f else dp(c, radiusDp), pressed = true))

    /** A status lamp with its glow: give the view about three times the lamp's size. */
    fun led(color: Int): Drawable = LedDrawable(color)

    /**
     * Something that's on: a touch of the key light on the glass and an amber edge. It fills its
     * bounds exactly, as every shape does, so a chosen option is the same size as the others.
     */
    fun lit(c: Context, radiusDp: Float, pressed: Boolean = false): Drawable =
        Lit(dp(c, radiusDp), Math.max(1f, dp(c, 1.2f)), if (pressed) KEY_GLASS_HI else KEY_GLASS)

    fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    /** Small label: bold capitals, spaced out. */
    fun label(c: Context, t: TextView, sizeSp: Float = 11f, color: Int = MUTED) {
        t.isAllCaps = true
        t.typeface = bold(c)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.letterSpacing = 0.22f
        t.setTextColor(color)
    }

    /** The lit look: an amber wash and an amber edge, within the bounds. */
    class Lit(private val radius: Float, private val line: Float, private val fill: Int) : Drawable() {
        private val shape = android.graphics.Path()
        private val edge = android.graphics.Path()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onBoundsChange(bounds: Rect) {
            val r = RectF(bounds)
            roundedPath(shape, r, radius)
            val h = line / 2
            roundedPath(edge, RectF(r.left + h, r.top + h, r.right - h, r.bottom - h), radius - h)
        }

        override fun draw(canvas: Canvas) {
            p.style = Paint.Style.FILL
            p.color = fill
            canvas.drawPath(shape, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = line
            p.color = KEY_EDGE
            canvas.drawPath(edge, p)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
