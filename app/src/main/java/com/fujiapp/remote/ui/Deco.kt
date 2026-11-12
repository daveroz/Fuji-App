package com.fujiapp.remote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.TextView
import androidx.core.graphics.createBitmap

/**
 * The Deco "light in fog" visual language, shared with Worldly, the Deco launcher and SAB Monitor:
 * a night stage in haze, glass panels with hairline edges, and one amber key light. Something
 * that's on is "lit" (a touch of the key on the glass with a glowing key edge) rather than a solid
 * block, and small labels are spaced bold capitals, like markings on a piece of equipment.
 *
 * Layout follows medium-format camera software: generous margins, a restrained monochrome page,
 * large numerals for the readings that matter, small capital labels and hairline rules.
 *
 * Type is Carbon+ like Worldly: drop `carbonplus.otf` and `carbonplus-Bold.otf` into
 * `app/src/main/assets/fonts/` and they're used; without them the system sans-serif stands in.
 */
object Deco {
    // surfaces (darkest to lightest)
    const val BG = 0xFF05070B.toInt()
    const val HEADER = 0xFF070A10.toInt()
    const val PANEL = 0xFF0B1220.toInt()
    const val SURFACE = 0xFF0D1422.toInt()
    const val SURFACE_HI = 0xFF131C2C.toInt()
    /** Glass: the night at 72%, over the light. */
    const val GLASS = 0xB80A0F16.toInt()
    /** A well inside glass: darker, still see-through. */
    const val WELL = 0x8C05070B.toInt()
    // hairlines: a cool white at low strength, an edge of light rather than a line of paint
    const val BORDER_SOFT = 0x14AADCFF
    const val BORDER = 0x1FAADCFF
    const val BORDER_STRONG = 0x38AADCFF
    // text
    const val TEXT = 0xFFEEF4F8.toInt()
    const val TEXT_SOFT = 0xFFC9D4DD.toInt()
    const val MUTED = 0xFF8FA0B3.toInt()
    const val DIM = 0xFF6F8091.toInt()
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

    /** Rounded box with an optional 1dp border (stroke = 0 for none). */
    fun box(c: Context, fill: Int, stroke: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, radiusDp)
        if (stroke != 0) setStroke(Math.max(1, Math.round(dp(c, 1f))), stroke)
    }

    /** A glass panel: the night at 72% with a hairline edge. */
    fun glass(c: Context, radiusDp: Float): GradientDrawable = box(c, GLASS, BORDER, radiusDp)

    /** Something that's on: a touch of the key light on the glass, a key edge and a soft glow outside it. */
    fun lit(c: Context, radiusDp: Float, pressed: Boolean = false): Drawable =
        Lit(dp(c, radiusDp), dp(c, Lit.INSET_DP), dp(c, 1f), if (pressed) KEY_GLASS_HI else KEY_GLASS)

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

    /** The lit look, drawn once into a picture whenever its size changes (a blur can't be drawn live everywhere). */
    class Lit(private val radius: Float, private val inset: Float, private val line: Float, private val fill: Int) : Drawable() {
        private var picture: Bitmap? = null

        override fun onBoundsChange(bounds: Rect) {
            picture = null
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val pic = picture ?: createBitmap(b.width(), b.height()).also { bmp ->
                val c = Canvas(bmp)
                val r = RectF(inset, inset, b.width() - inset, b.height() - inset)
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = inset * 0.9f
                    color = 0x73EB9438
                    maskFilter = BlurMaskFilter(inset * 0.9f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawRoundRect(r, radius, radius, glow)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                c.drawRoundRect(r, radius, radius, p) // the glow is only outside; inside is glass
                p.xfermode = null
                p.color = fill
                c.drawRoundRect(r, radius, radius, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = line
                p.color = KEY_EDGE
                val h = line / 2
                c.drawRoundRect(RectF(r.left + h, r.top + h, r.right - h, r.bottom - h), radius, radius, p)
                picture = bmp
            }
            canvas.drawBitmap(pic, b.left.toFloat(), b.top.toFloat(), null)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        companion object {
            const val INSET_DP = 3f
        }
    }
}
