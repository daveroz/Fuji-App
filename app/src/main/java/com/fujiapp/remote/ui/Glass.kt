package com.fujiapp.remote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withClip
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The stage's light, as glass picks it up. The stage publishes a small, soft copy of its light
 * (the key light, the fill, the ceiling lamps, the fog: no background) every frame; glass draws
 * the part behind it, brightened, so a panel glows where a light is behind it, as frosted glass
 * does, and its rim is warmest where the light falls.
 */
object Stagelight {
    /** The light at a small size, or null before the stage's first frame. */
    var light: Bitmap? = null
        private set
    /** Screen pixels per pixel of [light]. */
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set
    /** Where the stage starts in the window. */
    val origin = IntArray(2)
    private val glass: MutableSet<Drawable> = Collections.newSetFromMap(WeakHashMap())
    private var frame = 0

    fun register(d: Drawable) {
        glass.add(d)
    }

    /** From the stage after each frame; glass follows every other frame (the lights drift slowly). */
    fun update(light: Bitmap, sx: Float, sy: Float) {
        this.light = light
        scaleX = sx
        scaleY = sy
        if (++frame % 2 == 0) refresh()
    }

    /** Redraw every glass surface (after a frame of the stage, or a scroll). */
    fun refresh() {
        if (glass.isEmpty()) return
        glass.toList().forEach { it.invalidateSelf() }
    }

    /** How strong the light is at a point of the window, about 0–1. */
    fun at(x: Float, y: Float): Float {
        val b = light ?: return 0f
        val px = ((x - origin[0]) / scaleX).toInt().coerceIn(0, b.width - 1)
        val py = ((y - origin[1]) / scaleY).toInt().coerceIn(0, b.height - 1)
        val c = b[px, py]
        val a = (c ushr 24) / 255f
        val lum = (0.3f * ((c shr 16) and 0xFF) + 0.59f * ((c shr 8) and 0xFF) + 0.11f * (c and 0xFF)) / 255f
        return a * lum
    }

    /** The view a drawable is drawn for (through any state list or layer around it). */
    fun hostOf(d: Drawable): View? {
        var cb: Any? = d.callback
        while (cb is Drawable) cb = cb.callback
        return cb as? View
    }

    /** Film grain, tiled: faint white specks (the stage's own). */
    val grain: Bitmap by lazy { StageView.grainTile() }
}

/**
 * Liquid glass, kept quiet: a pane of charcoal that lets a little of the stage through, a touch
 * lighter at its top left where the light catches it, deeper at the bottom, with film grain and a
 * hairline rim, brightest at the corners. It picks up the light behind it faintly (see
 * [Stagelight]), and its rim warms a little where the key light falls. Corners are rounded
 * ([roundedPath]); [radius] < 0 takes a control's corner, and [round] a button's fuller one.
 */
class GlassDrawable(
    context: Context,
    private val radius: Float,
    private val round: Boolean = false,
    private val tone: Tone = Tone.PANE,
    private val pressed: Boolean = false,
) : Drawable() {
    /** PANE: a card. LENS: a lighter pane, the chosen segment or tab. WELL: a recess for a control. BAR: denser glass for what floats over content. */
    enum class Tone { PANE, WARM, COOL, LENS, WELL, BAR }

    private val density = context.resources.displayMetrics.density
    private val rect = RectF()
    private val rimRect = RectF()
    private val shape = android.graphics.Path()
    private val rimShape = android.graphics.Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val warmth = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depth = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(Stagelight.grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        alpha = 80
    }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        colorFilter = LIGHT_FILTER
        alpha = if (tone == Tone.BAR) 80 else 130
    }
    private val key = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EEB9438 }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density * 0.6f)
    }
    private val lightMatrix = Matrix()
    private var lightShader: BitmapShader? = null
    private var lightSource: Bitmap? = null
    private val loc = IntArray(2)
    private var rimWarmth = -1f

    init {
        Stagelight.register(this)
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        val inset = rim.strokeWidth / 2
        rimRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        outline(shape, rect, 0f)
        outline(rimShape, rimRect, inset)
        val h = rect.height()
        val w = rect.width()
        // Charcoal, barely cool: lighter at the top left, deepest at the bottom right.
        // A little more solid than glass usually is: the stage only just shows through.
        val (top, mid, bottom) = when (tone) {
            Tone.LENS -> Triple(0x29FFFFFF, 0x21FFFFFF, 0x1CFFFFFF)
            Tone.WELL -> Triple(0x99000000.toInt(), 0x8C000000.toInt(), 0x85000000.toInt())
            Tone.BAR -> Triple(0xF0191A1E.toInt(), 0xF2131417.toInt(), 0xF50F1013.toInt())
            else -> Triple(0xDE191A1E.toInt(), 0xE3131417.toInt(), 0xEB0F1013.toInt())
        }
        fill.shader = if (round) {
            LinearGradient(rect.left, rect.top, rect.left, rect.bottom, intArrayOf(0xF21B1C20.toInt(), 0xF5121316.toInt()), null, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(rect.left, rect.top, rect.left + w * 0.35f, rect.bottom, intArrayOf(top, mid, bottom), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        // The key light, faintly, in one corner: top right for panes, under a lens.
        val glow = when (tone) {
            Tone.WARM -> 0x14EB9438
            Tone.LENS -> 0x0FEB9438
            else -> 0
        }
        warmth.shader = when {
            glow == 0 -> null
            tone == Tone.LENS -> RadialGradient(rect.centerX(), rect.bottom + h * 0.2f, max(w, h) * 0.7f, glow, 0, Shader.TileMode.CLAMP)
            else -> RadialGradient(rect.right, rect.top, max(w * 0.9f, h * 0.8f), glow, 0, Shader.TileMode.CLAMP)
        }
        sheen.shader = if (tone == Tone.WELL) null else
            RadialGradient(rect.left + w * 0.15f, rect.top, max(w, h) * 0.75f, if (tone == Tone.LENS) 0x0DFFFFFF else 0x08FFFFFF, 0, Shader.TileMode.CLAMP)
        depth.shader = if (tone == Tone.WELL) {
            LinearGradient(0f, rect.top, 0f, rect.top + min(h, 14 * density), 0x66000000, 0, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, rect.bottom - min(h * 0.4f, 36 * density), 0f, rect.bottom, 0, 0x1F000000, Shader.TileMode.CLAMP)
        }
        rimWarmth = -1f
    }

    /** The shape: always a rounded square (a "round" button has a fuller corner). */
    private fun outline(path: android.graphics.Path, r: RectF, inset: Float) {
        val corner = when {
            round -> min(r.width(), r.height()) * ROUND_SHARE
            radius < 0 -> CONTROL_RADIUS_DP * density
            else -> radius
        }
        roundedPath(path, r, max(0f, corner - inset))
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawPath(shape, fill)
        warmth.shader?.let { canvas.drawPath(shape, warmth) }
        val host = Stagelight.hostOf(this)
        val src = Stagelight.light
        var warm = 0f
        if (host != null && src != null && tone != Tone.WELL && tone != Tone.LENS) {
            host.getLocationInWindow(loc)
            if (lightSource !== src) {
                lightSource = src
                lightShader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            // The stage's light behind this glass, stretched back to screen size.
            lightMatrix.setScale(Stagelight.scaleX, Stagelight.scaleY)
            lightMatrix.postTranslate(-(loc[0] - Stagelight.origin[0].toFloat()), -(loc[1] - Stagelight.origin[1].toFloat()))
            lightShader?.setLocalMatrix(lightMatrix)
            light.shader = lightShader
            canvas.drawPath(shape, light)
            warm = (2f * Stagelight.at(loc[0] + rect.right - 12 * density, loc[1] + rect.top + 12 * density)).coerceIn(0f, 0.25f)
        }
        if (pressed) canvas.drawPath(shape, key)
        sheen.shader?.let { canvas.drawPath(shape, sheen) }
        canvas.drawPath(shape, depth)
        canvas.drawPath(shape, grain)
        if (pressed) warm = 0.6f
        if (kotlin.math.abs(warm - rimWarmth) > 0.03f) {
            rimWarmth = warm
            rim.shader = rimShader(warm)
        }
        canvas.drawPath(rimShape, rim)
    }

    /**
     * A hairline, brightest at the top left and bottom left corners where the light catches the
     * edge; where the key light falls on it (top right), it takes a little of its warmth.
     */
    private fun rimShader(warm: Float): Shader {
        val strong = if (tone == Tone.WELL) 0x12 else if (round) 0x33 else 0x36
        val soft = if (tone == Tone.WELL) 0x0A else if (round) 0x21 else 0x1A
        val edge = (strong shl 24) or 0xFFFFFF
        val faint = (soft shl 24) or 0xFFFFFF
        val corner = if (warm > 0.01f) {
            val a = (soft + (0x48 - soft) * warm / 0.6f).roundToInt().coerceIn(0, 255)
            (a shl 24) or 0xFFD2A6
        } else faint
        val mid = (((strong + soft) / 2) shl 24) or 0xFFFFFF
        // Clockwise from the right: bottom right, bottom left and top left catch the light; the
        // top right takes the key's warmth.
        return android.graphics.SweepGradient(
            rimRect.centerX(), rimRect.centerY(),
            intArrayOf(faint, mid, faint, mid, faint, edge, faint, corner, faint),
            floatArrayOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f),
        )
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** A control's corner (buttons, chips, the switch), in dp. */
        const val CONTROL_RADIUS_DP = 12f
        /** A "round" button's corner, as a share of its size: a rounded square, not a circle. */
        const val ROUND_SHARE = 0.27f

        /** The light, about as bright in the glass as around it, a little richer. */
        private val LIGHT_FILTER = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(1.2f)
            postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.3f, 0f)))
        })
    }
}

/**
 * The key action: solid amber, a rounded square, with a soft glow around it (drawn once per size into a
 * picture, inside an inset that leaves room for the glow).
 */
class KeyDrawable(context: Context, private val radius: Float, private val pressed: Boolean = false) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val inset = 4 * density
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
            val rad = if (radius < 0) GlassDrawable.CONTROL_RADIUS_DP * density else radius
            val path = android.graphics.Path().also { roundedPath(it, r, rad) }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (pressed) 0x80EB9438.toInt() else 0x47EB9438
                maskFilter = BlurMaskFilter(inset, BlurMaskFilter.Blur.OUTER)
            })
            val top = if (pressed) 0xFFE99A4C.toInt() else 0xFFF2A65C.toInt()
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, r.top, 0f, r.bottom, top, if (pressed) Deco.AMBER_DEEP else 0xFFDB862F.toInt(), Shader.TileMode.CLAMP)
            })
            picture = bmp
        }
        canvas.drawBitmap(pic, b.left.toFloat(), b.top.toFloat(), null)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Text on the key: the night, warm. */
        const val INK = 0xFF1A0F04.toInt()
    }
}

/** A lamp: a bright core and its glow in fog (status lights, the shot counter's dot). */
class LedDrawable(var color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 0

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = min(b.width(), b.height()) / 2f
        if (r <= 0f) return
        if (shown != color || p.shader == null) {
            shown = color
            val rgb = color and 0x00FFFFFF
            p.shader = RadialGradient(
                b.exactCenterX(), b.exactCenterY(), r,
                intArrayOf(0xFFFFF4E6.toInt(), color, color, (0x73 shl 24) or rgb, (0x1A shl 24) or rgb, rgb),
                floatArrayOf(0f, 0.12f, 0.3f, 0.38f, 0.66f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, p)
    }

    override fun onBoundsChange(bounds: Rect) {
        p.shader = null
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The shutter: an amber ring lit like a lamp, glowing into the haze, around a warm glass button.
 * Pressed, the button lights up; busy ([dim]), it fades.
 */
class ShutterDrawable(context: Context, private val pressed: Boolean = false) : Drawable() {
    private val d = context.resources.displayMetrics.density
    private var picture: Bitmap? = null

    override fun onBoundsChange(bounds: Rect) {
        picture = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val pic = picture ?: createBitmap(b.width(), b.height()).also { bmp ->
            val c = Canvas(bmp)
            val cx = b.width() / 2f
            val cy = b.height() / 2f
            val outer = min(cx, cy) - 6 * d
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3 * d
                color = 0x99EB9438.toInt()
                maskFilter = BlurMaskFilter(6 * d, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawCircle(cx, cy, outer, ring)
            ring.maskFilter = null
            ring.color = Deco.AMBER
            c.drawCircle(cx, cy, outer, ring)
            val disc = outer * 0.74f
            c.drawCircle(cx, cy, disc, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy - disc * 0.5f, disc * 1.5f,
                    if (pressed) intArrayOf(0xFFFFF6EA.toInt(), 0xFFF6C08A.toInt(), Deco.AMBER) else intArrayOf(0xF2FFECD6.toInt(), 0xD9F0AE6A.toInt(), 0xE6C97624.toInt()),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
            c.drawCircle(cx, cy, disc, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = d
                shader = LinearGradient(0f, cy - disc, 0f, cy + disc, 0xB3FFFFFF.toInt(), 0, Shader.TileMode.CLAMP)
            })
            picture = bmp
        }
        canvas.drawBitmap(pic, b.left.toFloat(), b.top.toFloat(), null)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** An on/off switch: a glass well, and a knob that lights up amber when on. */
@SuppressLint("ViewConstructor") // made in code only
class GlassSwitch(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val well = GlassDrawable(c, -1f, tone = GlassDrawable.Tone.WELL)
    private val on = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    var checked = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        well.callback = this
        setLayerType(LAYER_TYPE_SOFTWARE, null) // for the knob's glow
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === well || super.verifyDrawable(who)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((54 * d).roundToInt(), (34 * d).roundToInt())
    }

    private var onShader: Shader? = null
    private var knobOn: Shader? = null
    private var knobOff: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 2 * d
        r.set(pad, pad, w - pad, h - pad)
        well.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        val rad = r.height() / 2
        val kr = rad - 3 * d
        onShader = LinearGradient(0f, r.top, 0f, r.bottom, 0x99EB9438.toInt(), 0x66C97624, Shader.TileMode.CLAMP)
        val ky = r.centerY()
        knobOn = RadialGradient(r.right - rad, ky - kr * 0.6f, kr * 1.8f, intArrayOf(0xFFFFF8EF.toInt(), 0xFFF0D2AE.toInt()), null, Shader.TileMode.CLAMP)
        knobOff = RadialGradient(r.left + rad, ky - kr * 0.6f, kr * 1.8f, intArrayOf(0xFF4E5157.toInt(), 0xFF1E1F23.toInt()), null, Shader.TileMode.CLAMP)
        glow.color = 0x66EB9438
        glow.maskFilter = BlurMaskFilter(5 * d, BlurMaskFilter.Blur.NORMAL)
        on.shader = onShader
        roundedPath(trackPath, r, GlassDrawable.CONTROL_RADIUS_DP * d)
        val k = RectF(r.right - rad - kr, ky - kr, r.right - rad + kr, ky + kr)
        roundedPath(knobOnPath, k, kr * 2 * GlassDrawable.ROUND_SHARE)
        k.offsetTo(r.left + rad - kr, ky - kr)
        roundedPath(knobOffPath, k, kr * 2 * GlassDrawable.ROUND_SHARE)
    }

    private val trackPath = android.graphics.Path()
    private val knobOnPath = android.graphics.Path()
    private val knobOffPath = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        well.draw(canvas)
        if (checked) canvas.drawPath(trackPath, on)
        if (checked) canvas.drawPath(knobOnPath, glow)
        knob.shader = if (checked) knobOn else knobOff
        canvas.drawPath(if (checked) knobOnPath else knobOffPath, knob)
    }
}

/**
 * A ring of lamps, like a lighting rig seen head on: [count] lamps over 270°, lit up to [fraction],
 * the lit ones glowing amber, brighter towards the end of the run.
 */
@SuppressLint("ViewConstructor") // made in code only
class LedRing(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val lamp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    var fraction: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // for the glow
        lamp.strokeWidth = 3.2f * d
        halo.strokeWidth = 5f * d
        halo.maskFilter = BlurMaskFilter(5 * d, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy) - 8 * d
        val inner = outer - 14 * d
        val lit = ((fraction ?: 0f).coerceIn(0f, 1f) * COUNT).roundToInt()
        for (k in 0 until COUNT) {
            val a = Math.toRadians(-225.0 + k * 270.0 / (COUNT - 1))
            val x1 = cx + inner * cos(a).toFloat()
            val y1 = cy + inner * sin(a).toFloat()
            val x2 = cx + outer * cos(a).toFloat()
            val y2 = cy + outer * sin(a).toFloat()
            if (k < lit) {
                val t = 0.55f + 0.45f * k / max(1, lit)
                halo.color = ((0x8C * t).roundToInt() shl 24) or (Deco.AMBER and 0xFFFFFF)
                canvas.drawLine(x1, y1, x2, y2, halo)
                lamp.color = ((255 * t).roundToInt() shl 24) or (Deco.AMBER_LIGHT and 0xFFFFFF)
            } else {
                lamp.color = 0x24C8D7E6
            }
            canvas.drawLine(x1, y1, x2, y2, lamp)
        }
    }

    private companion object {
        const val COUNT = 60
    }
}

/** The chosen segment of a [Kit.Segmented]: a lighter lens of glass, nothing under its name. */
@Suppress("FunctionName") // reads as the drawable it makes
fun SegmentLens(c: Context): Drawable = GlassDrawable(c, 9f * c.resources.displayMetrics.density, tone = GlassDrawable.Tone.LENS)

/** A rectangle with rounded corners of [radius] (reduced to fit), the one shape the app uses. */
fun roundedPath(path: android.graphics.Path, r: RectF, radius: Float) {
    path.reset()
    if (r.width() <= 0f || r.height() <= 0f) return
    val rad = min(radius, min(r.width(), r.height()) / 2).coerceAtLeast(0f)
    path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
}

/**
 * A plain rounded square: a fill and an optional hairline (Deco.box), for small solid shapes. It can be
 * copied ([getConstantState]): Material's slider copies the thumb it's given, and a drawable that
 * can't be copied crashes it.
 */
class RoundedDrawable(private val fill: Int, private val stroke: Int, private val strokeWidth: Float, private val radius: Float) : Drawable() {
    private val state = object : ConstantState() {
        override fun newDrawable(): Drawable = RoundedDrawable(fill, stroke, strokeWidth, radius)
        override fun getChangingConfigurations(): Int = 0
    }

    override fun getConstantState(): ConstantState = state

    private val path = android.graphics.Path()
    private val edge = android.graphics.Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        val r = RectF(bounds)
        roundedPath(path, r, radius)
        val h = strokeWidth / 2
        roundedPath(edge, RectF(r.left + h, r.top + h, r.right - h, r.bottom - h), max(0f, radius - h))
    }

    override fun draw(canvas: Canvas) {
        if (fill ushr 24 != 0) {
            p.style = Paint.Style.FILL
            p.color = fill
            canvas.drawPath(path, p)
        }
        if (stroke ushr 24 != 0 && strokeWidth > 0f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeWidth
            p.color = stroke
            canvas.drawPath(edge, p)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** A frame whose children are clipped to rounded corners, on a ground colour (pictures set into glass). */
@SuppressLint("ViewConstructor") // made in code only
class RoundedFrame(c: Context, private val radius: Float, ground: Int = 0xFF000000.toInt()) : android.widget.FrameLayout(c) {
    private val clip = android.graphics.Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ground }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        roundedPath(clip, RectF(0f, 0f, w.toFloat(), h.toFloat()), radius)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(clip, paint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.withClip(clip) { super.dispatchDraw(this) }
    }
}
