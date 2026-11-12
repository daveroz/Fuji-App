package com.fujiapp.remote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.graphics.createBitmap
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The room behind every screen, as in Worldly and on the Deco launcher: a dark stage in haze, lit
 * in amber. Two washes of the key light drift slowly, a strong one high on the right and a fainter
 * one low on the left; fog banks drift through; and a row of small lamps along the ceiling runs a
 * slow chase, like a lighting rig seen through haze.
 *
 * Every light has two parts: a soft glow, drawn at 1/8 of the screen's size and stretched so it
 * spreads like light in fog, and a sharp core drawn at full detail. A fine grain over the haze
 * keeps its gradients smooth. It animates only while the app is in front.
 */
class StageView(context: Context) : View(context) {
    private val softPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val glowPaint = Paint(Paint.DITHER_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) } // light adds up
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint().apply { shader = BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val matrix = Matrix()
    private val screen = Rect()
    private val key = unit(Deco.AMBER)
    private val led = unit(LED)
    private val fog = unit(FOG)
    private var soft: Bitmap? = null
    private var softCanvas: Canvas? = null
    private var running = false
    private var lastFrame = 0L
    private var air = 0f

    /** Where the ceiling is: the lamps hang just below the status bar. */
    var ceiling = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Animates while the screen is in front; otherwise it holds still. */
    fun setRunning(run: Boolean) {
        if (running == run) return
        running = run
        lastFrame = 0
        if (run) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        if (w <= 0 || h <= 0) return
        val bmp = createBitmap(max(1, w / SOFT), max(1, h / SOFT))
        soft = bmp
        softCanvas = Canvas(bmp).apply { scale(bmp.width / w.toFloat(), bmp.height / h.toFloat()) }
    }

    override fun onDraw(canvas: Canvas) {
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.1f, (now - lastFrame) / 1000f)
        lastFrame = now
        air += dt
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Deco.BG)
        val soft = soft ?: return

        val big = max(w, h)
        val rig = if (w > h) 9 else 7
        val dp = resources.displayMetrics.density
        val top = ceiling.toFloat()
        soft.eraseColor(0)
        glow(key, w * (0.82f + 0.03f * sin(air * 0.07f)), h * (0.15f + 0.02f * cos(air * 0.05f)), big * 0.42f, big * 0.42f, 0.16f)
        glow(key, w * (0.16f + 0.05f * sin(air * 0.06f + 1)), h * 0.9f, big * 0.4f, big * 0.4f, 0.075f)
        for (i in 0 until rig) {
            glow(if (i % 2 == 1) led else key, w * (i + 0.5f) / rig, top + 12 * dp, 44 * dp, 44 * dp, brightness(i, rig) * 0.3f)
        }
        for (i in 0 until 2) {
            val x = ((air * (7 + i * 4) / 1280f + i * 0.6f) % 1.6f - 0.3f) * w
            glow(fog, x, h * (0.36f + i * 0.28f), w * 0.95f, h * 0.13f, 0.05f)
        }

        screen.set(0, 0, width, height)
        canvas.drawBitmap(soft, null, screen, softPaint)
        for (i in 0 until rig) {
            val tone = if (i % 2 == 1) LED else mix(Deco.AMBER, LED, 0.6f)
            corePaint.color = alpha(tone, min(1f, brightness(i, rig) + 0.2f))
            val x = w * (i + 0.5f) / rig
            canvas.drawRect(x - 3 * dp, top + 4 * dp, x + 3 * dp, top + 5.5f * dp, corePaint)
        }
        canvas.drawRect(screen, grainPaint)

        if (running) postInvalidateDelayed(FRAME_MS)
    }

    /** A rig lamp's brightness: a slow chase runs along the row. */
    private fun brightness(i: Int, count: Int): Float {
        val chase = max(0.0, cos(Math.PI * 2 * (i / count.toFloat() - air / 7))).pow(4).toFloat()
        return 0.22f + 0.5f * chase
    }

    /** A soft glow on the small canvas, in screen pixels: centre, radii and strength. */
    private fun glow(shader: Shader, x: Float, y: Float, rx: Float, ry: Float, strength: Float) {
        if (strength < 0.004f) return
        matrix.setScale(rx, ry)
        matrix.postTranslate(x, y)
        shader.setLocalMatrix(matrix)
        glowPaint.shader = shader
        glowPaint.alpha = min(255, (strength * 255).roundToInt())
        softCanvas?.drawRect(x - rx, y - ry, x + rx, y + ry, glowPaint)
    }

    private companion object {
        const val SOFT = 8
        const val FRAME_MS = 33L
        const val FOG = 0xFFB4C8E1.toInt()
        /** The rig's white lamps (Worldly's LED). */
        const val LED = 0xFFFDF2E7.toInt()

        /** A round glow of radius 1 around the origin, from the colour to nothing. */
        fun unit(colour: Int): Shader =
            RadialGradient(0f, 0f, 1f, colour or 0xFF000000.toInt(), colour and 0x00FFFFFF, Shader.TileMode.CLAMP)

        fun alpha(colour: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (colour and 0x00FFFFFF)

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            fun lerp(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).roundToInt()
            return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
        }

        /** A tile of faint white specks, like film grain. */
        fun grain(): Bitmap {
            val size = 128
            val random = Random(7)
            val pixels = IntArray(size * size) {
                val v = random.nextFloat()
                val a = if (v > 0.5f) ((v - 0.5f) * 2 * 14).roundToInt() else 0
                (a shl 24) or 0x00FFFFFF
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
