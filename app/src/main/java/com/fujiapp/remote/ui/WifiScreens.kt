package com.fujiapp.remote.ui

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.fujiapp.remote.wifi.CardPhoto
import com.fujiapp.remote.wifi.WifiPhase

/** Browse the card and import pictures, over the camera's Wi-Fi. */
class PhotosScreen(private val a: MainActivity) {
    private val c = a
    private val status = Kit.text(c, null, 14f, Deco.MUTED)
    private val progress = Kit.progress(c)
    private val grid = Kit.column(c)
    private val selected = LinkedHashSet<Int>()
    private val importSelected = Kit.button(c, "Import selected") { importNow(selectedPhotos()) }
    private val importNewest = Kit.button(c, "Newest 10", Kit.Style.SECONDARY) { importNow(a.wifi.photos.value.take(10).map { it.photo }) }
    private val more = Kit.button(c, "Load more", Kit.Style.QUIET) { a.wifi.loadMore() }
    private var shown: List<CardPhoto>? = null

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.row(c).apply {
            addView(Kit.column(c).apply {
                addView(Kit.label(c, "Over Wi-Fi", Deco.DIM))
                addView(Kit.text(c, "Photos", 30f), Kit.lp(top = Deco.dpi(c, 10)))
            }, Kit.lp(0, weight = 1f))
            addView(Kit.button(c, "Close", Kit.Style.QUIET) { a.closeCameraPage() })
        }, Kit.lp(top = Deco.dpi(c, 4)))
        addView(status, Kit.lp(top = Deco.dpi(c, 14)))
        addView(progress, Kit.lp(top = Deco.dpi(c, 10)))
        addView(Kit.row(c).apply {
            addView(importSelected, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 10) })
            addView(importNewest, Kit.lp(0, weight = 1f))
        }, Kit.lp(top = Deco.dpi(c, 16)))
        addView(grid, Kit.lp(top = Deco.dpi(c, 12)))
        addView(more, Kit.lp(top = Deco.dpi(c, 8)))
    })

    private fun selectedPhotos() = a.wifi.photos.value.filter { it.photo.handle in selected }.map { it.photo }

    private fun importNow(list: List<com.fujiapp.remote.wifi.FujiWifi.Photo>) {
        if (list.isEmpty()) {
            a.showNotice("Tap pictures to select them first.")
            return
        }
        a.wifi.importPhotos(list)
        selected.clear()
        shown = null
        render()
    }

    fun render() {
        val w = a.wifi
        val phase = w.phase.value
        val busy = phase is WifiPhase.Working || w.progress.value?.endsWith("%") == true || w.progress.value?.startsWith("Reading") == true
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text = when (phase) {
            is WifiPhase.Working -> phase.message
            is WifiPhase.Failed -> "Couldn't connect: ${phase.message}"
            WifiPhase.Photos -> w.progress.value ?: "${w.totalOnCard} pictures on the card. Tap to select."
            else -> "Not connected."
        }
        status.setTextColor(if (phase is WifiPhase.Failed) Deco.RED else Deco.MUTED)
        val ready = phase == WifiPhase.Photos
        Kit.setEnabled(importSelected, ready && selected.isNotEmpty())
        importSelected.text = if (selected.isEmpty()) "Import selected" else "Import ${selected.size}"
        Kit.setEnabled(importNewest, ready && w.photos.value.isNotEmpty())
        more.visibility = if (ready && w.canLoadMore) View.VISIBLE else View.GONE
        val list = w.photos.value
        if (list !== shown) {
            shown = list
            drawGrid(list)
        }
    }

    private fun drawGrid(list: List<CardPhoto>) {
        grid.removeAllViews()
        list.chunked(3).forEach { rowItems ->
            grid.addView(Kit.row(c).apply {
                rowItems.forEachIndexed { i, cp ->
                    addView(tile(cp), LinearLayout.LayoutParams(0, Kit.WRAP, 1f).apply { if (i > 0) marginStart = Deco.dpi(c, 6) })
                }
                repeat(3 - rowItems.size) { addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = Deco.dpi(c, 6) }) }
            }, Kit.lp(bottom = Deco.dpi(c, 6)))
        }
    }

    private fun tile(cp: CardPhoto): View = Kit.column(c).apply {
        val on = cp.photo.handle in selected
        background = if (on) Deco.lit(c, 10f) else Deco.box(c, Deco.WELL, Deco.BORDER, 10f)
        val inset = Deco.dpi(c, Deco.Lit.INSET_DP.toInt()) + Deco.dpi(c, 3)
        setPadding(inset, inset, inset, inset)
        addView(ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
            if (cp.thumb != null) setImageBitmap(cp.thumb) else setBackgroundColor(Deco.SURFACE)
        }, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 78)))
        addView(Kit.label(c, cp.photo.name, if (cp.imported) Deco.GREEN else Deco.DIM, 8f).apply { maxLines = 1 }, Kit.lp(top = Deco.dpi(c, 6)))
        isClickable = true
        setOnClickListener {
            if (!selected.remove(cp.photo.handle)) selected.add(cp.photo.handle)
            shown = null
            render()
        }
    }
}

/** Remote shooting over Wi-Fi: live view, exposure, focus and the shutter, on one screen. */
class RemoteScreen(private val a: MainActivity) {
    private val c = a
    private val status = Kit.text(c, null, 13f, Deco.MUTED)
    private val progress = Kit.progress(c)
    private val live = TapImageView(c).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(0xFF000000.toInt())
        contentDescription = "Live view. Tap to focus there."
    }
    private val marker = View(c).apply {
        background = GradientDrawable().apply {
            setColor(0)
            setStroke(Deco.dpi(c, 2), Deco.AMBER_LIGHT)
            cornerRadius = Deco.dp(c, 3f)
        }
        visibility = View.GONE
    }
    private val frame = FrameLayout(c).apply {
        addView(live, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
        addView(marker, FrameLayout.LayoutParams(Deco.dpi(c, MARKER_DP), Deco.dpi(c, MARKER_DP)))
    }
    private val mode = Kit.label(c, "", Deco.DIM, 10f)
    private val shutterValue = value()
    private val apertureValue = value()
    private val exposureValue = value()
    private val iso = Kit.PickerRow(c, "ISO") {
        val choices = a.wifi.isoChoices
        if (choices.isEmpty()) a.showNotice("The camera didn't list its ISO values.") else
            a.choose("ISO", choices.map { v -> val n = v and 0x00FFFFFF; if (v and 0x80000000L != 0L) "Auto $n" else "$n" }, -1) { i -> a.wifi.setIso(choices[i]) }
    }
    private val focusButton = Kit.button(c, "Focus", Kit.Style.SECONDARY) {
        if (a.wifi.focused.value) a.wifi.releaseFocus() else {
            placeMarker(null)
            a.wifi.focus()
        }
    }
    private var shownReadings: Map<String, String>? = null
    private var markerAt: Pair<Float, Float>? = null

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.row(c).apply {
            addView(Kit.column(c).apply {
                addView(Kit.label(c, "Over Wi-Fi", Deco.DIM))
                addView(Kit.text(c, "Remote", 26f), Kit.lp(top = Deco.dpi(c, 6)))
            }, Kit.lp(0, weight = 1f))
            addView(Kit.button(c, "Close", Kit.Style.QUIET) { a.closeCameraPage() })
        })
        addView(status, Kit.lp(top = Deco.dpi(c, 6)))
        addView(progress, Kit.lp(top = Deco.dpi(c, 6)))
        addView(frame, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 216)).apply { topMargin = Deco.dpi(c, 8) })
        addView(mode, Kit.lp(top = Deco.dpi(c, 6)))
        addView(Kit.panel(c, padV = 0).apply {
            addView(stepper("Shutter speed", shutterValue, { a.wifi.stepShutter(false) }, { a.wifi.stepShutter(true) }))
            addView(Kit.hairline(c))
            addView(stepper("Aperture", apertureValue, { a.wifi.stepAperture(false) }, { a.wifi.stepAperture(true) }))
            addView(Kit.hairline(c))
            addView(stepper("Exposure", exposureValue, { a.wifi.stepExposure(false) }, { a.wifi.stepExposure(true) }))
            addView(Kit.hairline(c))
            addView(iso.view)
        }, Kit.lp(top = Deco.dpi(c, 6)))
        addView(Kit.text(c, "Turning a dial on the camera shows up here. A dial set to a value wins over the app.", 12f, Deco.DIM), Kit.lp(top = Deco.dpi(c, 6)))
        addView(Kit.row(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(focusButton, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 16) })
            addView(shutter(), LinearLayout.LayoutParams(Deco.dpi(c, 76), Deco.dpi(c, 76)))
        }, Kit.lp(top = Deco.dpi(c, 10)))
    })

    init {
        live.setOnClickListener {
            imagePoint(live.tapX, live.tapY)?.let { p ->
                placeMarker(p)
                a.wifi.focus(p)
            }
        }
    }

    private fun value() = Kit.text(c, "—", 16f, Deco.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
    }

    private fun stepper(title: String, value: View, down: () -> Unit, up: () -> Unit): View = Kit.row(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, Deco.dpi(c, 5), 0, Deco.dpi(c, 5))
        addView(Kit.text(c, title, 14f, Deco.TEXT_SOFT), Kit.lp(0, weight = 1f))
        addView(stepButton("−", down), LinearLayout.LayoutParams(Deco.dpi(c, 46), Deco.dpi(c, 40)))
        addView(value, LinearLayout.LayoutParams(Deco.dpi(c, 88), Kit.WRAP))
        addView(stepButton("+", up), LinearLayout.LayoutParams(Deco.dpi(c, 46), Deco.dpi(c, 40)))
    }

    private fun stepButton(t: String, onClick: () -> Unit) = Kit.button(c, t, Kit.Style.SECONDARY, onClick).apply {
        minHeight = 0
        setPadding(0, 0, 0, 0)
    }

    private fun shutter(): View = View(c).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Deco.KEY_GLASS)
            setStroke(Deco.dpi(c, 3), Deco.AMBER)
        }
        contentDescription = "Take a picture"
        isClickable = true
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            a.wifi.shoot()
        }
    }

    /** Where a tap lands on the picture itself (0–1 across and down), or null outside it. */
    private fun imagePoint(x: Float, y: Float): Pair<Float, Float>? {
        val d = live.drawable ?: return null
        val vw = live.width.toFloat()
        val vh = live.height.toFloat()
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return null
        val scale = minOf(vw / iw, vh / ih)
        val left = (vw - iw * scale) / 2
        val top = (vh - ih * scale) / 2
        val fx = (x - left) / (iw * scale)
        val fy = (y - top) / (ih * scale)
        return if (fx in 0f..1f && fy in 0f..1f) fx to fy else null
    }

    private fun placeMarker(at: Pair<Float, Float>?) {
        markerAt = at
        val size = Deco.dpi(c, MARKER_DP)
        val d = live.drawable
        var x = (live.width - size) / 2f
        var y = (live.height - size) / 2f
        if (at != null && d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
            val scale = minOf(live.width.toFloat() / d.intrinsicWidth, live.height.toFloat() / d.intrinsicHeight)
            val left = (live.width - d.intrinsicWidth * scale) / 2
            val top = (live.height - d.intrinsicHeight * scale) / 2
            x = left + at.first * d.intrinsicWidth * scale - size / 2f
            y = top + at.second * d.intrinsicHeight * scale - size / 2f
        }
        marker.translationX = x
        marker.translationY = y
        marker.alpha = 0.6f
        marker.visibility = View.VISIBLE
    }

    fun showFrame(frame: Bitmap?) {
        if (frame != null) live.setImageBitmap(frame)
    }

    fun render() {
        val w = a.wifi
        val phase = w.phase.value
        progress.visibility = if (phase is WifiPhase.Working) View.VISIBLE else View.GONE
        status.text = when (phase) {
            is WifiPhase.Working -> phase.message
            is WifiPhase.Failed -> "Couldn't connect: ${phase.message}"
            WifiPhase.Remote -> w.note.value ?: "Connected. Tap the picture to focus there, or the round button to shoot."
            else -> "Not connected."
        }
        status.setTextColor(if (phase is WifiPhase.Failed) Deco.RED else Deco.MUTED)
        val ready = phase == WifiPhase.Remote
        Kit.setEnabled(focusButton, ready)
        val focused = w.focused.value
        focusButton.text = if (focused) "Let go of focus" else "Focus"
        if (focused) marker.alpha = 1f else if (!ready) marker.isVisible = false
        val r = w.readings.value
        if (r != shownReadings) {
            shownReadings = r
            shutterValue.text = r["Shutter"] ?: "—"
            apertureValue.text = r["Aperture"] ?: "—"
            exposureValue.text = r["Exposure"] ?: "—"
            iso.set(r["ISO"] ?: "—")
            mode.text = listOfNotNull(r["Mode"]?.let { "Mode $it" }, r["Battery"]?.let { "Battery $it" }).joinToString("  ·  ")
        }
    }

    private companion object {
        const val MARKER_DP = 44
    }
}

/** The live view: remembers where it was tapped, so a click can focus there. */
private class TapImageView(c: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(c) {
    var tapX = 0f
        private set
    var tapY = 0f
        private set

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapX = event.x
                tapY = event.y
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
