package com.fujiapp.remote.ui

import android.graphics.Bitmap
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.fujiapp.remote.wifi.CardPhoto
import com.fujiapp.remote.wifi.FujiWifi
import com.fujiapp.remote.wifi.WifiPhase
import kotlinx.coroutines.launch

/** Browse the card and download pictures, over the camera's Wi-Fi. */
class PhotosScreen(private val a: MainActivity) {
    private val c = a
    private val status = Kit.text(c, null, 14f, Deco.MUTED)
    private val progress = Kit.progress(c)
    private val grid = Kit.column(c)
    private val selected = LinkedHashSet<Int>()
    // Chosen pictures are imported even if they were before (a damaged copy can be replaced);
    // "Newest 10" skips the ones already on the phone.
    private val importSelected = Kit.button(c, "Download selected", icon = LineIcon.Kind.DOWNLOAD) { importNow(selectedPhotos(), again = true) }
    private val importNewest = Kit.button(c, "Newest 10", Kit.Style.SECONDARY, LineIcon.Kind.DOWNLOAD) { importNow(a.wifi.photos.value.take(10).map { it.photo }, again = false) }
    private val more = Kit.button(c, "Load more", Kit.Style.QUIET) { a.wifi.loadMore() }
    private var shown: List<CardPhoto>? = null

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.row(c).apply {
            addView(Kit.column(c).apply {
                addView(Kit.label(c, "Over Wi-Fi", Deco.DIM))
                addView(Kit.text(c, "Photos", 30f), Kit.lp(top = Deco.dpi(c, 10)))
            }, Kit.lp(0, weight = 1f))
            addView(Kit.iconButton(c, LineIcon.Kind.CLOSE, "Close") { a.closeCameraPage() })
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

    private fun importNow(list: List<com.fujiapp.remote.wifi.FujiWifi.Photo>, again: Boolean) {
        if (list.isEmpty()) {
            a.showNotice("Tap pictures to select them first.")
            return
        }
        a.wifi.importPhotos(list, again)
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
        importSelected.text = if (selected.isEmpty()) "Download selected" else "Download ${selected.size}"
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
        background = if (on) Deco.lit(c, 10f) else Deco.glass(c, 10f)
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

/**
 * Remote shooting over Wi-Fi, on one screen: the live view, a readout laid out like a Hasselblad's
 * rear screen (aperture and shutter speed large, ISO, the exposure scale, mode and shots left),
 * a row of setting tiles like Leica's (film simulation, white balance, flash, timer, ISO), focus
 * and the shutter.
 */
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
        background = SquircleDrawable(0x1FEB9438, Deco.AMBER_LIGHT, Deco.dp(c, 2f), Deco.dp(c, 9f))
        visibility = View.GONE
    }
    /** The picture just taken, shown for a moment over the live view. */
    private val postview = ImageView(c).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(0xFF000000.toInt())
        visibility = View.GONE
        contentDescription = "The picture just taken"
    }
    /** "Saving", over the live view while the camera finishes a shot. */
    private val saving = Kit.label(c, "Saving…", Deco.AMBER_LIGHT, 10f).apply {
        gravity = Gravity.CENTER
        background = Deco.glass(c, -1f, GlassDrawable.Tone.BAR)
        setPadding(Deco.dpi(c, 18), Deco.dpi(c, 10), Deco.dpi(c, 18), Deco.dpi(c, 10))
        visibility = View.GONE
    }
    /** The live view set into its glass card: an even glass margin, rounded to match. */
    private val frame = FrameLayout(c).apply {
        background = Deco.glass(c, Layouts.CARD_RADIUS)
        val pad = Deco.dpi(c, FRAME_PAD_DP)
        setPadding(pad, pad, pad, pad)
        addView(SquircleFrame(c, Deco.dp(c, Layouts.CARD_RADIUS - FRAME_PAD_DP)).apply {
            addView(live, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
            addView(postview, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
            addView(marker, FrameLayout.LayoutParams(Deco.dpi(c, MARKER_DP), Deco.dpi(c, MARKER_DP)))
            addView(saving, FrameLayout.LayoutParams(Kit.WRAP, Kit.WRAP, Gravity.CENTER))
        }, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
    }

    // The readout (Hasselblad rear screen).
    private val topLine = Kit.label(c, "", Deco.MUTED, 9f).apply { maxLines = 1 }
    private val battery = Kit.label(c, "", Deco.MUTED, 9f).apply { gravity = Gravity.END }
    private val batteryLed = Kit.led(c, Deco.AMBER, 15)
    private val apertureValue = big().apply { gravity = Gravity.CENTER }
    private val shutterValue = big().apply { gravity = Gravity.CENTER }
    private val isoValue = Kit.label(c, "", Deco.TEXT, 12f).apply {
        gravity = Gravity.END
        maxLines = 1
        letterSpacing = 0.12f
    }
    private val evScale = EvScale(c)
    private val evValue = Kit.label(c, "EV 0", Deco.TEXT_SOFT, 8f).apply { gravity = Gravity.CENTER }
    private val modeValue = Kit.text(c, "—", 20f, Deco.TEXT, bold = true).apply { letterSpacing = 0.2f }
    private val shotsValue = Kit.text(c, "", 15f, Deco.TEXT_SOFT, bold = true).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val size = Deco.dpi(c, 16)
        setCompoundDrawablesRelative(IconDrawable(LineIcon.Kind.SHOTS, Deco.MUTED, c).apply { setBounds(0, 0, size, size) }, null, null, null)
        compoundDrawablePadding = Deco.dpi(c, 8)
    }
    private val apertureDown = Kit.iconButton(c, LineIcon.Kind.CHEVRON_LEFT, "Smaller aperture number", STEP_DP) { a.wifi.stepAperture(false) }
    private val apertureUp = Kit.iconButton(c, LineIcon.Kind.CHEVRON_RIGHT, "Larger aperture number", STEP_DP) { a.wifi.stepAperture(true) }
    private val shutterDown = Kit.iconButton(c, LineIcon.Kind.CHEVRON_LEFT, "Slower shutter speed", STEP_DP) { a.wifi.stepShutter(false) }
    private val shutterUp = Kit.iconButton(c, LineIcon.Kind.CHEVRON_RIGHT, "Faster shutter speed", STEP_DP) { a.wifi.stepShutter(true) }
    /** In place of the steps when the camera picks the value (its mode, or a dial on A). */
    private val apertureAuto = autoBadge()
    private val shutterAuto = autoBadge()

    // The tiles (Leica's grid), as XApp's remote offers these settings.
    private val filmTile = Layouts.Tile(c, LineIcon.Kind.FILM, "Film sim") { pickSetting(FujiWifi.FILM_SIMULATION, "Film simulation") }
    private val wbTile = Layouts.Tile(c, LineIcon.Kind.WB, "White bal.") { pickSetting(FujiWifi.WHITE_BALANCE, "White balance") }
    private val flashTile = Layouts.Tile(c, LineIcon.Kind.FLASH, "Flash") { pickSetting(FujiWifi.FLASH, "Flash") }
    private val timerTile = Layouts.Tile(c, LineIcon.Kind.TIMER, "Timer") { pickSetting(FujiWifi.SELF_TIMER, "Self-timer") }
    private val isoTile = Layouts.Tile(c, LineIcon.Kind.ISO, "ISO") { pickIso() }

    private val focusButton = Kit.button(c, "Focus", Kit.Style.SECONDARY, LineIcon.Kind.FOCUS) {
        if (a.wifi.focused.value) a.wifi.releaseFocus() else {
            placeMarker(null)
            a.wifi.focus()
        }
    }
    private var shownReadings: Map<String, String>? = null
    private var markerAt: Pair<Float, Float>? = null
    private val shutterButton by lazy { shutter() }

    val view: View = Kit.page(c, Kit.column(c).apply {
        // What's happening, and the way out; the header above names the camera and the link.
        addView(Kit.row(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(status, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 12) })
            addView(Kit.iconButton(c, LineIcon.Kind.CLOSE, "Close the remote", 40) { a.closeCameraPage() })
        })
        addView(progress, Kit.lp(top = Deco.dpi(c, 4)))
        addView(frame, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, LIVE_DP)).apply { topMargin = Deco.dpi(c, 8) })
        // The readout, as a camera's rear screen.
        addView(Layouts.hero(c).apply {
            setPadding(Deco.dpi(c, 16), Deco.dpi(c, 14), Deco.dpi(c, 16), Deco.dpi(c, 12))
            addView(Kit.row(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(topLine, Kit.lp(0, weight = 1f))
                addView(batteryLed)
                addView(battery, Kit.lp(Kit.WRAP))
            })
            // Aperture on the left, shutter speed on the right, each with its steps on its sides
            // (‹ f/2.2 ›); when the camera picks one, its steps give way to AUTO under it.
            addView(Kit.row(c).apply {
                gravity = Gravity.TOP
                addView(valueGroup(apertureValue, apertureDown, apertureUp, apertureAuto, Gravity.START), Kit.lp(Kit.WRAP))
                addView(View(c), Kit.lp(0, weight = 1f))
                addView(valueGroup(shutterValue, shutterDown, shutterUp, shutterAuto, Gravity.END), Kit.lp(Kit.WRAP))
            }, Kit.lp(top = Deco.dpi(c, 12)))
            addView(Kit.row(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(Kit.iconButton(c, LineIcon.Kind.CHEVRON_LEFT, "Less exposure", STEP_DP) { a.wifi.stepExposure(false) })
                addView(Kit.column(c).apply {
                    addView(evScale, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 28)))
                    addView(evValue, Kit.lp(top = Deco.dpi(c, 3)))
                }, Kit.lp(0, weight = 1f).apply {
                    marginStart = Deco.dpi(c, 6)
                    marginEnd = Deco.dpi(c, 6)
                })
                addView(Kit.iconButton(c, LineIcon.Kind.CHEVRON_RIGHT, "More exposure", STEP_DP) { a.wifi.stepExposure(true) })
                addView(Kit.column(c).apply {
                    gravity = Gravity.END
                    addView(Kit.label(c, "ISO", Deco.DIM, 8f))
                    addView(isoValue, Kit.lp(Kit.WRAP, top = Deco.dpi(c, 5)))
                }, Kit.lp(Kit.WRAP).apply { marginStart = Deco.dpi(c, 12) })
            }, Kit.lp(top = Deco.dpi(c, 12)))
            addView(Kit.hairline(c), Kit.lp(top = Deco.dpi(c, 12)))
            addView(Kit.row(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(modeValue, Kit.lp(0, weight = 1f))
                addView(shotsValue, Kit.lp(Kit.WRAP))
            }, Kit.lp(top = Deco.dpi(c, 10)))
        }, Kit.lp(top = Deco.dpi(c, 10)))
        addView(Layouts.tileRow(c, listOf(filmTile, wbTile, flashTile, timerTile, isoTile)), Kit.lp(top = Deco.dpi(c, 12)))
        addView(Kit.row(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(focusButton, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 14) })
            addView(shutterButton, LinearLayout.LayoutParams(Deco.dpi(c, SHUTTER_DP), Deco.dpi(c, SHUTTER_DP)))
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

    private fun big() = Kit.text(c, "—", 32f, Deco.TEXT, bold = true).apply {
        maxLines = 1
        gravity = Gravity.CENTER
        // Between its two steps the value shrinks rather than wraps (1/4000 is long).
        setAutoSizeTextTypeUniformWithConfiguration(16, 32, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

    /**
     * A value with its steps on its sides: ‹ f/2.2 ›. When the camera picks the value, the steps
     * give way to an AUTO badge under it, and the value moves to the group's outer edge.
     */
    private fun valueGroup(value: android.widget.TextView, down: View, up: View, auto: View, side: Int): View = Kit.column(c).apply {
        gravity = side
        addView(Kit.row(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(down)
            addView(value, LinearLayout.LayoutParams(Deco.dpi(c, VALUE_DP), Deco.dpi(c, 44)).apply {
                marginStart = Deco.dpi(c, 2)
                marginEnd = Deco.dpi(c, 2)
            })
            addView(up)
        }, Kit.lp(Kit.WRAP))
        addView(auto, Kit.lp(Kit.WRAP).apply { topMargin = Deco.dpi(c, 6) })
    }

    /** "AUTO", lit: the camera picks this value. */
    private fun autoBadge(): View = Kit.label(c, "Auto", Deco.AMBER_LIGHT, 10f).apply {
        gravity = Gravity.CENTER
        background = Deco.lit(c, 10f)
        val inset = Deco.dpi(c, Deco.Lit.INSET_DP.toInt())
        setPadding(Deco.dpi(c, 18) + inset, Deco.dpi(c, 7) + inset, Deco.dpi(c, 18) + inset, Deco.dpi(c, 7) + inset)
        visibility = View.GONE
        contentDescription = "Set by the camera"
    }

    private fun pickIso() {
        val choices = a.wifi.isoChoices
        if (choices.isEmpty()) a.showNotice("The camera doesn't offer ISO values right now (its ISO dial may be set to a value).") else
            a.choose("ISO", choices.map(::isoName), choices.indexOf(a.wifi.currentValue(FujiWifi.ISO) ?: -1L)) { i -> a.wifi.setIso(choices[i]) }
    }

    private fun pickSetting(property: Int, title: String) {
        if (a.wifi.phase.value != WifiPhase.Remote) return
        a.lifecycleScope.launch {
            val desc = a.wifi.describeSetting(property)
            val values = desc?.values.orEmpty()
            if (desc == null || values.isEmpty()) {
                a.showNotice("The camera doesn't offer $title right now. A custom setting (C1–C7) or a dial may decide it.")
                return@launch
            }
            val current = a.wifi.currentValue(property) ?: desc.current
            a.choose(title, values.map { settingLabel(property, it) }, values.indexOf(current)) { i ->
                a.wifi.setSetting(property, values[i], desc.width, title)
            }
        }
    }

    private fun shutter(): View = View(c).apply {
        background = Deco.pressable(ShutterDrawable(c), ShutterDrawable(c, pressed = true))
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

    /** The picture just taken, over the live view for a moment; null hides it. */
    fun showPostview(shot: Bitmap?) {
        if (shot != null) postview.setImageBitmap(shot) else postview.setImageDrawable(null)
        postview.isVisible = shot != null
    }



    fun render() {
        val w = a.wifi
        val phase = w.phase.value
        progress.visibility = if (phase is WifiPhase.Working) View.VISIBLE else View.GONE
        status.text = when (phase) {
            is WifiPhase.Working -> phase.message
            is WifiPhase.Failed -> "Couldn't connect: ${phase.message}"
            WifiPhase.Remote -> w.note.value ?: "Tap the picture to focus there, the round button to shoot."
            else -> "Not connected."
        }
        status.setTextColor(if (phase is WifiPhase.Failed) Deco.RED else Deco.MUTED)
        val ready = phase == WifiPhase.Remote
        Kit.setEnabled(focusButton, ready)
        listOf(filmTile to FujiWifi.FILM_SIMULATION, wbTile to FujiWifi.WHITE_BALANCE, flashTile to FujiWifi.FLASH, timerTile to FujiWifi.SELF_TIMER).forEach { (t, p) ->
            t.enable(ready && w.offers(p))
            t.set(w.currentValue(p)?.let { shortLabel(p, it) } ?: "—")
        }
        isoTile.enable(ready)
        isoTile.set(w.currentValue(FujiWifi.ISO)?.let(::isoName)?.uppercase() ?: "—")
        // In P and A the camera picks the shutter speed; in P and S, the aperture: say so, plainly.
        val exposureMode = w.currentValue(FujiWifi.SHOOTING_MODE)
        val shutterIsAuto = exposureMode == 2L || exposureMode == 3L
        val apertureIsAuto = exposureMode == 2L || exposureMode == 4L
        shutterDown.isGone = shutterIsAuto
        shutterUp.isGone = shutterIsAuto
        shutterAuto.isVisible = shutterIsAuto
        apertureDown.isGone = apertureIsAuto
        apertureUp.isGone = apertureIsAuto
        apertureAuto.isVisible = apertureIsAuto
        shutterValue.gravity = if (shutterIsAuto) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        apertureValue.gravity = if (apertureIsAuto) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        // While a shot saves the camera is left alone (as XApp does): say so on the picture.
        val capturing = w.capturing.value
        saving.isVisible = capturing
        shutterButton.alpha = if (capturing) 0.4f else 1f
        val focused = w.focused.value
        focusButton.text = if (focused) "Let go of focus" else "Focus"
        if (focused) marker.alpha = 1f else if (!ready) marker.isVisible = false
        val r = w.readings.value
        if (r != shownReadings) {
            shownReadings = r
            apertureValue.text = r["Aperture"] ?: "f/—"
            shutterValue.text = r["Shutter"] ?: "—"
            isoValue.text = r["ISO"]?.uppercase() ?: "—"
            val ev = w.currentValue(FujiWifi.EXPOSURE_BIAS)?.let { it.toInt().toShort() / 1000.0 } ?: 0.0
            evScale.set(ev)
            val evText = "EV ${r["Exposure"] ?: "0"}"
            evValue.text = evText
            modeValue.text = r["Mode"] ?: "—"
            shotsValue.text = r["Shots"] ?: ""
            battery.text = r["Battery"]?.let { "BATTERY $it" } ?: ""
            batteryLed.isVisible = r["Battery"] != null
            topLine.text = listOfNotNull(
                w.currentValue(FujiWifi.WHITE_BALANCE)?.let { "WB " + shortLabel(FujiWifi.WHITE_BALANCE, it) },
                w.currentValue(FujiWifi.FOCUS_MODE)?.let { shortLabel(FujiWifi.FOCUS_MODE, it) },
                w.currentValue(FujiWifi.FILM_SIMULATION)?.let { shortLabel(FujiWifi.FILM_SIMULATION, it) },
            ).joinToString("   ")
        }
    }

    private companion object {
        const val MARKER_DP = 44
        const val STEP_DP = 42
        /** Each value's width between its steps: two groups fit side by side in M. */
        const val VALUE_DP = 80
        const val LIVE_DP = 214
        const val FRAME_PAD_DP = 6
        const val SHUTTER_DP = 78
    }
}

/** The exposure scale of a camera's rear screen: −3 … +3 in thirds, a mark at the value. */
@android.annotation.SuppressLint("ViewConstructor") // made in code only
private class EvScale(c: android.content.Context) : View(c) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var ev = 0.0

    fun set(value: Double) {
        if (value != ev) {
            ev = value
            invalidate()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val pad = 4 * d
        val span = w - 2 * pad
        val at = ((ev.coerceIn(-3.0, 3.0) + 3) / 6.0 * 18).toFloat() // in thirds from −3
        p.strokeCap = android.graphics.Paint.Cap.ROUND
        // A row of lamps: the third the exposure sits at lit and glowing, its neighbours warm.
        for (k in 0..18) {
            val x = pad + span * k / 18f
            val whole = (k - 9) % 3 == 0
            val near = kotlin.math.abs(k - at)
            val top = h - (if (whole) 16 else 10) * d
            p.strokeWidth = 2 * d
            p.color = if (near < 1.5f) ((0x66 + (0x40 * (1 - near / 1.5f)).toInt()) shl 24) or (Deco.AMBER_LIGHT and 0xFFFFFF)
            else if (whole) 0x73D2D4D8 else 0x47D2D4D8
            canvas.drawLine(x, top, x, h - d, p)
        }
        val x = pad + span * at / 18f
        p.strokeWidth = 8 * d
        p.color = 0x24EB9438
        canvas.drawLine(x, d * 2, x, h - d, p)
        p.strokeWidth = 3 * d
        p.color = Deco.AMBER_LIGHT
        canvas.drawLine(x, d * 2, x, h - d, p)
    }
}

/** ISO as XApp shows it: Auto with its ceiling, or the value (0x40000000 marks L/H). */
internal fun isoName(v: Long): String {
    val n = v and 0x00FFFFFF
    return when {
        v and 0x80000000L != 0L -> "Auto $n"
        v and 0x40000000L != 0L -> if (n <= 400) "L $n" else "H $n"
        else -> "$n"
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

/** Film simulations by the codes the remote uses, named as XApp names them. */
private val FILM_NAMES = mapOf(
    1L to "Provia / Standard", 2L to "Velvia / Vivid", 3L to "Astia / Soft", 4L to "Monochrome", 5L to "Sepia",
    20L to "Reala Ace", 6L to "Pro Neg. Hi", 7L to "Pro Neg. Std", 8L to "Monochrome + Ye", 9L to "Monochrome + R",
    10L to "Monochrome + G", 11L to "Classic Chrome", 17L to "Classic Neg.", 16L to "Eterna / Cinema", 12L to "Acros",
    13L to "Acros + Ye", 14L to "Acros + R", 15L to "Acros + G", 18L to "Eterna Bleach Bypass", 19L to "Nostalgic Neg.",
)

/** White balance by the remote's codes (not the custom-setting ones), named as XApp names them. */
private val WB_NAMES = mapOf(
    2L to "Auto", 0x800DL to "Auto, white priority", 0x800EL to "Auto, ambience priority", 4L to "Daylight",
    0x8006L to "Shade", 0x8001L to "Fluorescent 1", 0x8002L to "Fluorescent 2", 0x8003L to "Fluorescent 3",
    6L to "Incandescent", 0x800AL to "Underwater", 0x800BL to "Color temperature", 0x800CL to "Custom 1",
)

/** A readable name for a camera setting's value (XApp's names); the number when it isn't known. */
internal fun settingLabel(property: Int, v: Long): String = when (property) {
    FujiWifi.FILM_SIMULATION -> FILM_NAMES[v]
    FujiWifi.WHITE_BALANCE -> WB_NAMES[v]
    FujiWifi.FOCUS_MODE -> when (v) {
        1L -> "Manual focus"
        0x8001L -> "Single AF"
        0x8002L -> "Continuous AF"
        else -> null
    }
    FujiWifi.FLASH -> when (v) {
        0x8009L -> "Off"
        0x800AL -> "On"
        else -> null
    }
    FujiWifi.SELF_TIMER -> when (v) {
        0L -> "Off"
        2L -> "2 seconds"
        4L -> "10 seconds"
        else -> null
    }
    FujiWifi.IMAGE_QUALITY -> when (v) {
        1L -> "RAW"
        2L -> "Fine"
        3L -> "Normal"
        4L -> "RAW + Fine"
        5L -> "RAW + Normal"
        else -> null
    }
    else -> null
} ?: "Value %X".format(v)

/** The short form a tile or the readout's top line shows. */
internal fun shortLabel(property: Int, v: Long): String = when (property) {
    FujiWifi.FILM_SIMULATION -> FILM_NAMES[v]?.substringBefore(" /")?.replace(" + ", "+")?.uppercase()
    FujiWifi.WHITE_BALANCE -> when (v) {
        2L -> "AUTO"
        0x800DL -> "AUTO W"
        0x800EL -> "AUTO A"
        0x800BL -> "K"
        0x800CL -> "C1"
        else -> WB_NAMES[v]?.uppercase()
    }
    FujiWifi.FOCUS_MODE -> when (v) {
        1L -> "MF"
        0x8001L -> "AF-S"
        0x8002L -> "AF-C"
        else -> null
    }
    FujiWifi.FLASH -> settingLabel(property, v).uppercase()
    FujiWifi.SELF_TIMER -> when (v) {
        0L -> "OFF"
        2L -> "2 S"
        4L -> "10 S"
        else -> null
    }
    else -> null
} ?: "%X".format(v)
