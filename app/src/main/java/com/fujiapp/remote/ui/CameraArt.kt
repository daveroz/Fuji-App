package com.fujiapp.remote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * A drawing of a fixed-lens rangefinder camera, front on, in the app's night-and-amber palette:
 * the camera on the Camera tab, as XApp shows the connected body. Drawn in code (no artwork from
 * Fujifilm), so it stays sharp at any size; [lit] dims it while no camera is connected.
 */
class CameraArt(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val body = RectF()
    private val finder = RectF()
    // Gradients depend on the size: made in onSizeChanged, not while drawing.
    private var dialShader: Shader? = null
    private var bodyShader: Shader? = null
    private var finderShader: Shader? = null
    private var barrelShader: Shader? = null
    private var glassShader: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        dialShader = LinearGradient(0f, fh * 0.06f, 0f, fh * 0.24f, 0xFF3A4458.toInt(), 0xFF1B2230.toInt(), Shader.TileMode.CLAMP)
        body.set(fw * 0.04f, fh * 0.22f, fw * 0.96f, fh * 0.94f)
        bodyShader = LinearGradient(0f, body.top, 0f, body.bottom, intArrayOf(0xFF35405A.toInt(), 0xFF222B3C.toInt(), 0xFF121824.toInt(), 0xFF0C1019.toInt()), floatArrayOf(0f, 0.32f, 0.36f, 1f), Shader.TileMode.CLAMP)
        finder.set(fw * 0.70f, fh * 0.29f, fw * 0.90f, fh * 0.43f)
        finderShader = LinearGradient(finder.left, finder.top, finder.right, finder.bottom, 0xFF16263D.toInt(), 0xFF05070B.toInt(), Shader.TileMode.CLAMP)
        val cx = fw * 0.36f
        val cy = fh * 0.61f
        val outer = fh * 0.31f
        barrelShader = RadialGradient(cx, cy, maxOf(1f, outer), intArrayOf(0xFF2A3346.toInt(), 0xFF141A26.toInt()), null, Shader.TileMode.CLAMP)
        val glass = outer * 0.58f
        glassShader = RadialGradient(cx - glass * 0.2f, cy - glass * 0.25f, maxOf(1f, glass * 1.2f), intArrayOf(0xFF1E3A5C.toInt(), 0xFF0B1424.toInt(), 0xFF05070B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    var lit: Boolean = true
        set(value) {
            field = value
            alpha = if (value) 1f else 0.45f
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.5f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val line = maxOf(1f, w / 360f)

        // Top dials, hot shoe and release, behind the body.
        p.style = Paint.Style.FILL
        p.shader = dialShader
        r.set(w * 0.68f, h * 0.10f, w * 0.79f, h * 0.24f); canvas.drawRoundRect(r, line * 3, line * 3, p)
        r.set(w * 0.83f, h * 0.12f, w * 0.93f, h * 0.24f); canvas.drawRoundRect(r, line * 3, line * 3, p)
        r.set(w * 0.40f, h * 0.16f, w * 0.55f, h * 0.24f); canvas.drawRoundRect(r, line * 2, line * 2, p)
        p.shader = null
        p.color = Deco.BORDER_STRONG
        for (i in 0..9) { // knurling on the dials
            val x1 = w * (0.69f + i * 0.0095f)
            canvas.drawRect(x1, h * 0.11f, x1 + line, h * 0.23f, p)
        }
        p.color = 0xFF2A3346.toInt()
        canvas.drawCircle(w * 0.735f, h * 0.075f, h * 0.035f, p)
        p.color = Deco.AMBER_DEEP
        canvas.drawCircle(w * 0.735f, h * 0.075f, h * 0.016f, p)

        // Body: metal top plate, leatherette below.
        val radius = h * 0.07f
        p.shader = bodyShader
        canvas.drawRoundRect(body, radius, radius, p)
        p.shader = null
        p.color = 0x0FFFFFFF
        var y = h * 0.50f
        while (y < body.bottom - radius * 0.5f) { // leatherette grain
            canvas.drawRect(body.left + radius * 0.3f, y, body.right - radius * 0.3f, y + line * 0.6f, p)
            y += line * 3.2f
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = line
        p.color = Deco.BORDER_STRONG
        canvas.drawRoundRect(body, radius, radius, p)
        p.color = Deco.BORDER
        canvas.drawLine(body.left + radius * 0.4f, h * 0.47f, body.right - radius * 0.4f, h * 0.47f, p)

        // Viewfinder window and the frame-line window.
        p.style = Paint.Style.FILL
        r.set(finder)
        p.shader = finderShader
        canvas.drawRoundRect(r, line * 3, line * 3, p)
        p.shader = null
        p.color = 0x40AADCFF
        canvas.drawRect(r.left + (r.width() * 0.18f), r.top + line * 2, r.left + r.width() * 0.24f, r.bottom - line * 2, p)
        p.style = Paint.Style.STROKE
        p.color = Deco.BORDER_STRONG
        canvas.drawRoundRect(r, line * 3, line * 3, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF1A2232.toInt()
        r.set(w * 0.58f, h * 0.30f, w * 0.65f, h * 0.37f); canvas.drawRoundRect(r, line * 2, line * 2, p)
        p.color = Deco.AMBER
        canvas.drawCircle(w * 0.62f, h * 0.58f, h * 0.018f, p) // AF-assist lamp

        // The lens: barrel, aperture ring with ticks, amber edge, and glass with reflections.
        val cx = w * 0.36f
        val cy = h * 0.61f
        val outer = h * 0.31f
        p.shader = barrelShader
        canvas.drawCircle(cx, cy, outer, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = line * 1.6f
        p.color = Deco.KEY_EDGE
        canvas.drawCircle(cx, cy, outer, p)
        p.strokeWidth = line
        p.color = Deco.BORDER_STRONG
        for (i in 0 until 48) { // aperture-ring knurling
            val a = Math.PI * 2 * i / 48
            val r1 = outer * 0.84f
            val r2 = outer * 0.95f
            canvas.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), p)
        }
        p.color = Deco.BORDER
        canvas.drawCircle(cx, cy, outer * 0.78f, p)
        p.style = Paint.Style.FILL
        val glass = outer * 0.58f
        p.shader = glassShader
        canvas.drawCircle(cx, cy, glass, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = line * 2f
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x99F0AE6A.toInt()
        r.set(cx - glass * 0.72f, cy - glass * 0.72f, cx + glass * 0.72f, cy + glass * 0.72f)
        canvas.drawArc(r, 200f, 50f, false, p)
        p.color = 0x6667E8F9
        r.set(cx - glass * 0.45f, cy - glass * 0.45f, cx + glass * 0.45f, cy + glass * 0.45f)
        canvas.drawArc(r, 20f, 40f, false, p)
        p.style = Paint.Style.FILL
        p.color = 0xCCEEF4F8.toInt()
        canvas.drawCircle(cx - glass * 0.38f, cy - glass * 0.42f, line * 2.2f, p)
        p.strokeCap = Paint.Cap.BUTT
    }
}
