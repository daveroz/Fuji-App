package com.fujiapp.remote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/**
 * The picture of the camera on the Camera tab: the app's own drawing, or a photo the user picks
 * (of their own camera, say). Kept as a JPEG in the app's private files.
 */
object CameraPicture {
    private const val FILE = "camera-picture.jpg"

    /** Goes up when the picture changes, so screens know to look again. */
    @Volatile
    var revision = 0
        private set
    private var cached: Bitmap? = null

    private fun file(c: Context) = File(c.filesDir, FILE)

    fun isCustom(c: Context): Boolean = file(c).exists()

    @Synchronized
    fun load(c: Context): Bitmap? = cached ?: file(c).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.also { cached = it }

    @Synchronized
    fun save(c: Context, bitmap: Bitmap) {
        file(c).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        cached = bitmap
        revision++
    }

    @Synchronized
    fun clear(c: Context) {
        file(c).delete()
        cached = null
        revision++
    }
}

/** The camera at the top of the Camera tab: the user's photo if there is one, else the drawing. */
@SuppressLint("ViewConstructor") // made in code only
class CameraView(c: Context, private val lit: Boolean = true) : FrameLayout(c) {
    private val art = CameraArt(c).apply { lit = this@CameraView.lit }
    private val photo = ImageView(c).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        maxHeight = Deco.dpi(c, MAX_PHOTO_DP)
        contentDescription = "Your camera"
    }
    private var shown = -1

    init {
        addView(art, LayoutParams(Kit.MATCH, Kit.WRAP))
        addView(photo, LayoutParams(Kit.MATCH, Kit.WRAP))
        refresh()
    }

    fun refresh() {
        if (shown == CameraPicture.revision) return
        shown = CameraPicture.revision
        val bmp = CameraPicture.load(context)
        if (bmp != null) {
            photo.setImageBitmap(bmp)
            photo.alpha = if (lit) 1f else 0.45f
            photo.visibility = View.VISIBLE
            art.visibility = View.GONE
        } else {
            photo.setImageDrawable(null)
            photo.visibility = View.GONE
            art.visibility = View.VISIBLE
        }
    }

    private companion object {
        const val MAX_PHOTO_DP = 240
    }
}
