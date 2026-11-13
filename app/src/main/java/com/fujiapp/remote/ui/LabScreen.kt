package com.fujiapp.remote.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.lifecycleScope
import com.fujiapp.remote.preview.PreviewSource
import com.fujiapp.remote.preview.RecipePreview
import com.fujiapp.remote.preview.SampleScene
import com.fujiapp.remote.preview.Vectorscope
import com.fujiapp.remote.recipe.DynamicRange
import com.fujiapp.remote.recipe.Effect
import com.fujiapp.remote.recipe.FilmSim
import com.fujiapp.remote.recipe.Grain
import com.fujiapp.remote.recipe.Recipe
import com.fujiapp.remote.recipe.WhiteBalance
import com.fujiapp.remote.usb.DevelopState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Try a recipe on a picture. The preview stays in view while the settings, grouped in five tabs
 * (Film, Color, Light, Tone, Detail), change it; nothing needs scrolling. The sample is a color
 * chart with skin tones, and the vectorscope shows where the colors land. The preview
 * approximates the look on the phone: the camera's own rendering will differ in detail.
 */
@SuppressLint("ClickableViewAccessibility")
class LabScreen(private val a: MainActivity) {
    private val c = a
    var recipe: Recipe = Recipe("MY RECIPE", FilmSim.CLASSIC_CHROME, dynamicRange = DynamicRange.DR200)
        private set

    private var source: PreviewSource? = null
    private var original: Bitmap? = null
    private var rendered: Bitmap? = null
    private var scopeBitmap: Bitmap? = null
    private var showingOriginal = false
    /** The camera's own rendering of the recipe on a RAW, while it's showing. */
    private var developed: Bitmap? = null
    private var showScope = true
    private var renderJob: Job? = null
    private var updating = false
    /** The vectorscope of [developed]. */
    private var developedScope: IntArray? = null
    /** The picture is a RAW's own JPEG: the camera's rendering when it was shot. */
    private var asShot = false
    /** A camera development waiting for the controls to settle. */
    private var autoJob: Job? = null
    private var lastDone: DevelopState.Done? = null
    /** The last development was started here (not from the Camera button), so its failure is told here. */
    private var autoStarted = false
    /** An automatic development failed: wait for the Camera button before trying again. */
    private var autoFailed = false

    private val image = RoundedImageView(c, Deco.dp(c, 12f)).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Preview. Touch and hold to see the original."
    }
    private val scope = ImageView(c).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Vectorscope with the skin-tone line"
    }
    private val status = Kit.label(c, "Hold to compare", Deco.DIM, 9f)

    private val name = EditText(c).apply {
        setTextColor(Deco.TEXT)
        setHintTextColor(Deco.DIM)
        hint = "Recipe name"
        typeface = Deco.bold(c)
        textSize = 15f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        filters = arrayOf(InputFilter.LengthFilter(Recipe.NAME_MAX), InputFilter.AllCaps())
        background = Deco.box(c, Deco.WELL, Deco.BORDER, 12f)
        setPadding(Deco.dpi(c, 12), Deco.dpi(c, 8), Deco.dpi(c, 12), Deco.dpi(c, 8))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, co: Int, af: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, be: Int, co: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!updating) recipe = recipe.copy(name = s?.toString().orEmpty())
            }
        })
    }

    /** Film simulations as a strip of thumbnails of the picture, each in its look (a phone camera's filter row). */
    private val filmSim = FilmStrip(c, SIMS.map { it.second }) { i ->
        val sim = SIMS[i].first
        change(recipe.copy(filmSim = sim, monoWarmCool = if (sim.toning) recipe.monoWarmCool else 0, monoMagentaGreen = if (sim.toning) recipe.monoMagentaGreen else 0))
    }
    private var thumbSource: PreviewSource? = null
    private var thumbJob: Job? = null

    /** Render every film simulation's thumbnail from the picture, with the rest of the recipe as it is. */
    private fun requestThumbs() {
        val src = thumbSource ?: return
        val r = recipe
        thumbJob?.cancel()
        thumbJob = a.lifecycleScope.launch {
            delay(THUMB_DEBOUNCE_MS)
            val bitmaps = withContext(Dispatchers.Default) {
                SIMS.map { (sim, _) ->
                    val px = IntArray(src.pixels.size)
                    RecipePreview.render(src, r.copy(filmSim = sim, monoWarmCool = if (sim.toning) r.monoWarmCool else 0, monoMagentaGreen = if (sim.toning) r.monoMagentaGreen else 0), px)
                    createBitmap(src.width, src.height).apply { setPixels(px, 0, src.width, 0, 0, src.width, src.height) }
                }
            }
            filmSim.setThumbs(bitmaps)
        }
    }
    private val grain = Kit.Chips(c, listOf("Off", "Weak S", "Strong S", "Weak L", "Strong L")) { i -> change(recipe.copy(grain = Grain.entries[i])) }
    private val chrome = Kit.Chips(c, listOf("Off", "Weak", "Strong")) { i -> change(recipe.copy(colorChrome = Effect.entries[i])) }
    private val chromeBlue = Kit.Chips(c, listOf("Off", "Weak", "Strong")) { i -> change(recipe.copy(colorChromeBlue = Effect.entries[i])) }
    private val whiteBalance = Kit.PickerRow(c, "White balance") {
        a.choose("White balance", WB_CHOICES.map { it.label }, WB_CHOICES.indexOf(recipe.whiteBalance)) { i -> change(recipe.copy(whiteBalance = WB_CHOICES[i])) }
    }
    private val kelvin = Kit.SliderRow(c, "Color temperature", 2500f, 10000f, 100f, { "${it.roundToInt()}K" }) { change(recipe.copy(kelvin = it.roundToInt())) }
    private val wbRed = Kit.SliderRow(c, "Shift, red", -9f, 9f, 1f, ::signedInt) { change(recipe.copy(wbRed = it.roundToInt())) }
    private val wbBlue = Kit.SliderRow(c, "Shift, blue", -9f, 9f, 1f, ::signedInt) { change(recipe.copy(wbBlue = it.roundToInt())) }
    private val dynamicRange = Kit.Chips(c, listOf("Auto", "DR100", "DR200", "DR400")) { i -> change(recipe.copy(dynamicRange = DynamicRange.entries[i])) }
    private val highlight = Kit.SliderRow(c, "Highlight", -2f, 4f, 0.5f, ::signedHalf) { change(recipe.copy(highlight = it.toDouble())) }
    private val shadow = Kit.SliderRow(c, "Shadow", -2f, 4f, 0.5f, ::signedHalf) { change(recipe.copy(shadow = it.toDouble())) }
    private val color = Kit.SliderRow(c, "Color", -4f, 4f, 1f, ::signedInt) { change(recipe.copy(color = it.roundToInt())) }
    private val sharpness = Kit.SliderRow(c, "Sharpness", -4f, 4f, 1f, ::signedInt) { change(recipe.copy(sharpness = it.roundToInt())) }
    private val noise = Kit.SliderRow(c, "High ISO NR", -4f, 4f, 1f, ::signedInt) { change(recipe.copy(noiseReduction = it.roundToInt())) }
    private val clarity = Kit.SliderRow(c, "Clarity", -5f, 5f, 1f, ::signedInt) { change(recipe.copy(clarity = it.roundToInt())) }
    private val toneWc = Kit.SliderRow(c, "Toning, warm–cool", -18f, 18f, 1f, ::signedInt) { change(recipe.copy(monoWarmCool = it.roundToInt())) }
    private val toneMg = Kit.SliderRow(c, "Toning, magenta–green", -18f, 18f, 1f, ::signedInt) { change(recipe.copy(monoMagentaGreen = it.roundToInt())) }
    private val grainRow = labelled("Grain", grain.view)
    private val chromeRow = labelled("Color chrome", chrome.view)
    private val chromeBlueRow = labelled("Color chrome FX blue", chromeBlue.view)
    private val monoNote = Kit.text(c, "Monochrome: color and the color chrome effects don't apply.", 13f, Deco.MUTED)

    private val groups: List<View> = listOf(
        group(labelled("Film simulation", filmSim), grainRow, toneWc.view, toneMg.view),
        group(color.view, chromeRow, chromeBlueRow, monoNote),
        group(whiteBalance.view, kelvin.view, wbRed.view, wbBlue.view),
        group(labelled("Dynamic range", dynamicRange.view), highlight.view, shadow.view),
        group(sharpness.view, clarity.view, noise.view),
    )
    private val controls = FrameLayout(c)
    private val tabs = Kit.Segmented(c, listOf("Film", "Color", "Light", "Tone", "Detail")) { showGroup(it) }
    private val scopeToggle = Kit.Chips(c, listOf("Scope")) { toggleScope() }

    /** The preview's box: the picture sits inside it with an even margin and matching corners. */
    private val frame = FrameLayout(c).apply {
        background = Deco.glass(c, Layouts.CARD_RADIUS)
        val pad = Deco.dpi(c, FRAME_PAD_DP)
        setPadding(pad, pad, pad, pad)
        addView(image, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
        addView(scope, FrameLayout.LayoutParams(Deco.dpi(c, 104), Deco.dpi(c, 104), Gravity.TOP or Gravity.END).apply {
            setMargins(0, Deco.dpi(c, 4), Deco.dpi(c, 4), 0)
        })
        addView(status, FrameLayout.LayoutParams(Kit.WRAP, Kit.WRAP, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(Deco.dpi(c, 10), 0, Deco.dpi(c, 10), Deco.dpi(c, 8))
        })
    }

    val view: View = Kit.column(c).apply {
        val m = Deco.dpi(c, Kit.PAGE_MARGIN)
        // Its foot clears the floating tab bar.
        setPadding(m, Deco.dpi(c, 10), m, Deco.dpi(c, Kit.TAB_SPACE))
        addView(Kit.row(c).apply {
            addView(name, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 8) })
            addView(small("Picture") {
                a.choose("Picture", listOf("Color chart with skin tones", "A photo from the phone"), -1) { i ->
                    if (i == 0) useSample() else a.pickLabPhoto()
                }
            })
            addView(small("Library") { a.recipeFlow.chooseStart() })
            addView(small("Camera") { a.recipeFlow.develop(recipe) })
        })
        addView(frame, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, FRAME_DP)).apply { topMargin = Deco.dpi(c, 10) })
        addView(Kit.row(c).apply {
            addView(tabs.view, Kit.lp(0, weight = 1f))
            addView(scopeToggle.view, Kit.lp(Kit.WRAP).apply { marginStart = Deco.dpi(c, 4) })
        }, Kit.lp(top = Deco.dpi(c, 10)))
        addView(ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            addView(controls, FrameLayout.LayoutParams(Kit.MATCH, Kit.WRAP))
        }, Kit.lp(h = 0, weight = 1f).apply { topMargin = Deco.dpi(c, 4) })
        addView(Kit.row(c).apply {
            addView(Kit.button(c, "Load into a slot", icon = LineIcon.Kind.UPLOAD) { a.recipeFlow.load(recipe) }, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 8) })
            addView(Kit.button(c, "Save", Kit.Style.SECONDARY, LineIcon.Kind.CHECK) { a.recipeFlow.saveMine(recipe) }, Kit.lp(Kit.WRAP))
        }, Kit.lp(top = Deco.dpi(c, 8)))
    }

    init {
        image.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showingOriginal = true
                    image.setImageBitmap(original)
                    status.text = if (asShot) "As shot (the RAW's own JPEG)" else "Original"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showingOriginal = false
                    image.setImageBitmap(developed ?: rendered)
                    status.text = idleStatus()
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
        image.setOnClickListener { }
        showGroup(0)
        scopeToggle.select(0)
        show()
        useSample()
        // Every development the camera finishes, started here or from the Camera button.
        a.lifecycleScope.launch {
            a.developer.state.collect { s ->
                when (s) {
                    is DevelopState.Working -> if (!showingOriginal && (developed != null || autoStarted)) {
                        status.text = if (s.message.startsWith("Uploading") || s.message.startsWith("Connecting")) s.message else DEVELOPING
                    }
                    is DevelopState.Done -> if (s !== lastDone) {
                        lastDone = s
                        autoStarted = false
                        autoFailed = false
                        showDeveloped(s.jpeg, s.recipe)
                    }
                    is DevelopState.Failed -> {
                        if (autoStarted) {
                            a.showNotice("The camera stopped developing: ${s.message}")
                            autoFailed = true
                        }
                        autoStarted = false
                        if (developed != null) {
                            // The camera is gone (unplugged, or it gave up): back to the phone preview.
                            developed = null
                            developedScope = null
                            requestRender(immediate = true)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun idleStatus(): String = when {
        developed != null -> "Developed by the camera · hold for the original"
        asShot -> "Phone preview on the RAW's JPEG · hold for the original"
        else -> "Hold to compare"
    }

    /**
     * The camera can develop the next change: the RAW is on it, or (after a slot load or read, which
     * closes the session) the camera is still plugged in and the RAW can go back up by itself.
     */
    private fun canDevelop(): Boolean {
        val dev = a.developer
        return dev.ready || (dev.raf != null && !autoFailed && !a.transfer.busy && a.transfer.cameraPlugged())
    }

    /** With a RAW on the camera, each change is developed there once the controls settle. */
    private fun scheduleDevelop() {
        val dev = a.developer
        if (!canDevelop()) return
        autoJob?.cancel()
        autoJob = a.lifecycleScope.launch {
            delay(AUTO_DEVELOP_MS)
            dev.state.first { it !is DevelopState.Working }
            if (canDevelop() && recipe.problems().isEmpty()) {
                autoStarted = true
                dev.develop(recipe)
            }
        }
    }

    private fun same(x: Recipe, y: Recipe) = x.copy(name = "") == y.copy(name = "")

    private fun small(title: String, onClick: () -> Unit): View =
        Kit.button(c, title, Kit.Style.QUIET, onClick = onClick).apply { minWidth = 0; minimumWidth = 0; setPadding(Deco.dpi(c, 10), 0, Deco.dpi(c, 10), 0) }

    private fun group(vararg views: View): View = Kit.column(c).apply { views.forEach { addView(it) } }

    private fun showGroup(i: Int) {
        tabs.select(i)
        controls.removeAllViews()
        (groups[i].parent as? FrameLayout)?.removeView(groups[i])
        controls.addView(groups[i], FrameLayout.LayoutParams(Kit.MATCH, Kit.WRAP))
    }

    private fun toggleScope() {
        showScope = !showScope
        scopeToggle.select(if (showScope) 0 else null)
        scope.visibility = if (showScope) View.VISIBLE else View.GONE
        if (!showScope) return
        val px = developedScope
        val target = scopeBitmap
        if (developed != null && px != null && target != null) {
            target.setPixels(px, 0, SCOPE, 0, 0, SCOPE, SCOPE)
            scope.setImageBitmap(target)
            scope.invalidate()
        } else {
            requestRender(immediate = true)
        }
    }

    private fun labelled(title: String, content: View): View = Kit.column(c).apply {
        setPadding(0, Deco.dpi(c, 10), 0, Deco.dpi(c, 4))
        addView(Kit.text(c, title, 14f, Deco.TEXT_SOFT))
        addView(content, Kit.lp(top = Deco.dpi(c, 8)))
    }

    /** Start from [r]: its settings fill the controls. */
    fun load(r: Recipe) = change(r.copy(name = r.cameraName))

    private fun change(r: Recipe) {
        val simOnly = r.copy(filmSim = recipe.filmSim, monoWarmCool = recipe.monoWarmCool, monoMagentaGreen = recipe.monoMagentaGreen) == recipe
        recipe = r
        show()
        // The thumbnails show the other settings too; picking a film simulation alone doesn't change them.
        if (!simOnly) requestThumbs()
        if (canDevelop()) {
            // Keep the camera's last picture up until the new one arrives (about a second).
            if (developed != null && !showingOriginal) status.text = DEVELOPING
            scheduleDevelop()
        } else {
            developed = null
            developedScope = null
        }
        requestRender()
    }

    /** Show the camera's own rendering of [r] (a JPEG it developed from a RAW), with its vectorscope. */
    private fun showDeveloped(jpeg: ByteArray, r: Recipe) {
        // A development of an older version: the newer one is on its way.
        if (!same(r, recipe) && a.developer.ready) return
        a.lifecycleScope.launch {
            val (bmp, scopePixels) = withContext(Dispatchers.Default) {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DEVELOPED_MAX) sample *= 2
                val b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@withContext null to null
                val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                b to IntArray(SCOPE * SCOPE).also { Vectorscope.render(px, SCOPE, it) }
            }
            if (bmp == null) {
                a.showNotice("Couldn't show the developed picture.")
                return@launch
            }
            if (!same(r, recipe) && a.developer.ready) return@launch
            developed = bmp
            developedScope = scopePixels
            if (!showingOriginal) {
                image.setImageBitmap(bmp)
                status.text = idleStatus()
            }
            fitFrame(bmp.width, bmp.height)
            val target = scopeBitmap
            if (scopePixels != null && target != null && showScope) {
                target.setPixels(scopePixels, 0, SCOPE, 0, 0, SCOPE, SCOPE)
                scope.setImageBitmap(target)
            }
        }
    }

    /** Put the recipe's values into the controls. */
    private fun show() {
        updating = true
        val r = recipe
        if (name.text.toString() != r.name) name.setText(r.name)
        filmSim.select(SIMS.indexOfFirst { it.first == r.filmSim }.takeIf { it >= 0 })
        grain.select(Grain.entries.indexOf(r.grain))
        chrome.select(Effect.entries.indexOf(r.colorChrome))
        chromeBlue.select(Effect.entries.indexOf(r.colorChromeBlue))
        val mono = r.filmSim.mono
        chromeRow.visibility = if (mono) View.GONE else View.VISIBLE
        chromeBlueRow.visibility = chromeRow.visibility
        monoNote.visibility = if (mono) View.VISIBLE else View.GONE
        toneWc.show(r.filmSim.toning); toneWc.set(r.monoWarmCool.toFloat())
        toneMg.show(r.filmSim.toning); toneMg.set(r.monoMagentaGreen.toFloat())
        whiteBalance.set(if (r.whiteBalance == WhiteBalance.KELVIN) "${r.kelvin}K" else r.whiteBalance.label)
        kelvin.show(r.whiteBalance == WhiteBalance.KELVIN); kelvin.set(r.kelvin.toFloat())
        wbRed.set(r.wbRed.toFloat()); wbBlue.set(r.wbBlue.toFloat())
        dynamicRange.select(DynamicRange.entries.indexOf(r.dynamicRange))
        highlight.set(r.highlight.toFloat()); shadow.set(r.shadow.toFloat())
        color.show(!mono); color.set(r.color.toFloat())
        sharpness.set(r.sharpness.toFloat()); noise.set(r.noiseReduction.toFloat()); clarity.set(r.clarity.toFloat())
        updating = false
    }

    fun useSample() = setPicture(asShot = false) { Triple(SAMPLE_W, SAMPLE_H, SampleScene.pixels(SAMPLE_W, SAMPLE_H)) }

    /** The phone preview works on the RAW's own JPEG, so holding and the scope show the same photo. */
    fun useRafPicture(uri: android.net.Uri) {
        autoFailed = false
        a.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { a.developer.embeddedJpeg(uri)?.let { decodeJpeg(it, PHOTO_MAX) } }
            if (bitmap == null) {
                a.showNotice("Couldn't read the RAW's preview: the phone preview stays on the current picture.")
                return@launch
            }
            setPicture(asShot = true) {
                val px = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                Triple(bitmap.width, bitmap.height, px)
            }
        }
    }

    /** Use a picture from the phone (already scaled down). */
    fun usePhoto(bitmap: Bitmap) = setPicture(asShot = false) {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        Triple(bitmap.width, bitmap.height, px)
    }

    private fun setPicture(asShot: Boolean, load: () -> Triple<Int, Int, IntArray>) {
        developed = null
        developedScope = null
        this.asShot = asShot
        a.lifecycleScope.launch {
            status.text = "Preparing…"
            val (src, orig) = withContext(Dispatchers.Default) {
                val (w, h, px) = load()
                PreviewSource(w, h, px) to createBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
            }
            source = src
            original = orig
            thumbSource = withContext(Dispatchers.Default) {
                val tw = THUMB_W
                val th = maxOf(1, orig.height * tw / orig.width)
                val small = orig.scale(tw, th)
                val px = IntArray(tw * th).also { small.getPixels(it, 0, tw, 0, 0, tw, th) }
                PreviewSource(tw, th, px)
            }
            requestThumbs()
            rendered = createBitmap(src.width, src.height)
            scopeBitmap = createBitmap(SCOPE, SCOPE)
            fitFrame(src.width, src.height)
            requestRender(immediate = true)
        }
    }

    /** Size the box to the picture's shape (within limits), so the margin around it is even. */
    private fun fitFrame(w: Int, h: Int) {
        frame.post {
            val pad = Deco.dpi(c, FRAME_PAD_DP)
            val inner = frame.width - 2 * pad
            if (inner <= 0 || w <= 0 || h <= 0) return@post
            val want = (inner.toFloat() * h / w).toInt() + 2 * pad
            val height = want.coerceIn(Deco.dpi(c, FRAME_MIN_DP), Deco.dpi(c, FRAME_MAX_DP))
            val lp = frame.layoutParams
            if (lp.height != height) {
                lp.height = height
                frame.layoutParams = lp
            }
        }
    }

    private fun requestRender(immediate: Boolean = false) {
        val src = source ?: return
        val target = rendered ?: return
        val scopeTarget = scopeBitmap
        val r = recipe
        val withScope = showScope
        renderJob?.cancel()
        renderJob = a.lifecycleScope.launch {
            if (!immediate) delay(RENDER_DEBOUNCE_MS)
            val (out, scopePixels) = withContext(Dispatchers.Default) {
                val px = IntArray(src.pixels.size).also { RecipePreview.render(src, r, it) }
                px to if (withScope) IntArray(SCOPE * SCOPE).also { Vectorscope.render(px, SCOPE, it) } else null
            }
            target.setPixels(out, 0, src.width, 0, 0, src.width, src.height)
            if (!showingOriginal && developed == null) image.setImageBitmap(target)
            image.invalidate()
            // While the camera's picture shows, the scope stays on it.
            if (scopePixels != null && scopeTarget != null && developed == null) {
                scopeTarget.setPixels(scopePixels, 0, SCOPE, 0, 0, SCOPE, SCOPE)
                scope.setImageBitmap(scopeTarget)
                scope.invalidate()
            }
            if (developed == null && !showingOriginal) status.text = idleStatus()
        }
    }

    companion object {
        /** Longest side the camera's developed JPEG is shown at. */
        private const val DEVELOPED_MAX = 1400
        private const val DEVELOPING = "Developing on the camera…"
        private const val THUMB_W = 128
        private const val THUMB_DEBOUNCE_MS = 250L
        /** How long the controls must rest before the camera develops again. */
        private const val AUTO_DEVELOP_MS = 600L
        private const val FRAME_DP = 224
        private const val FRAME_MIN_DP = 180
        private const val FRAME_MAX_DP = 280
        private const val FRAME_PAD_DP = 4
        /** Film simulations in the camera's menu order, with short names for the chips. */
        private val SIMS = listOf(
            FilmSim.PROVIA to "Provia", FilmSim.VELVIA to "Velvia", FilmSim.ASTIA to "Astia",
            FilmSim.CLASSIC_CHROME to "Classic Chrome", FilmSim.REALA_ACE to "Reala Ace",
            FilmSim.PRO_NEG_HI to "Pro Neg Hi", FilmSim.PRO_NEG_STD to "Pro Neg Std",
            FilmSim.CLASSIC_NEG to "Classic Neg", FilmSim.NOSTALGIC_NEG to "Nostalgic Neg",
            FilmSim.ETERNA to "Eterna", FilmSim.BLEACH_BYPASS to "Bleach Bypass",
            FilmSim.ACROS to "Acros", FilmSim.ACROS_YE to "Acros Ye", FilmSim.ACROS_R to "Acros R", FilmSim.ACROS_G to "Acros G",
            FilmSim.MONOCHROME to "Mono", FilmSim.MONOCHROME_YE to "Mono Ye", FilmSim.MONOCHROME_R to "Mono R",
            FilmSim.MONOCHROME_G to "Mono G", FilmSim.SEPIA to "Sepia",
        )

        /** Decode a JPEG to at most [max] pixels on its long side. */
        fun decodeJpeg(jpeg: ByteArray, max: Int): Bitmap? {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
            val b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = max.toFloat() / maxOf(b.width, b.height)
            return if (scale >= 1f) b else b.scale(maxOf(1, (b.width * scale).toInt()), maxOf(1, (b.height * scale).toInt()))
        }
        const val SAMPLE_W = 900
        const val SAMPLE_H = 600
        /** Longest side a photo is scaled to for the preview. */
        const val PHOTO_MAX = 960
        private const val SCOPE = 256
        private const val RENDER_DEBOUNCE_MS = 50L
        private val WB_CHOICES = listOf(
            WhiteBalance.AUTO, WhiteBalance.AUTO_WHITE, WhiteBalance.AUTO_AMBIENCE, WhiteBalance.DAYLIGHT, WhiteBalance.SHADE,
            WhiteBalance.FLUORESCENT_1, WhiteBalance.FLUORESCENT_2, WhiteBalance.FLUORESCENT_3, WhiteBalance.INCANDESCENT,
            WhiteBalance.UNDERWATER, WhiteBalance.KELVIN,
        )

        fun signedInt(v: Float): String = Recipe.signed(v.roundToInt())
        fun signedHalf(v: Float): String = Recipe.signed((v * 2).roundToInt() / 2.0)
    }
}

/** An ImageView whose picture has rounded corners where it's actually drawn (FIT_CENTER included). */
@SuppressLint("ViewConstructor", "UseKtx") // made in code only; super.onDraw can't go in withClip's lambda
private class RoundedImageView(c: android.content.Context, private val radius: Float) : androidx.appcompat.widget.AppCompatImageView(c) {
    private val clip = android.graphics.Path()
    private val box = android.graphics.RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = drawable
        if (d == null || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
            super.onDraw(canvas)
            return
        }
        box.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrix.mapRect(box)
        box.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        clip.reset()
        clip.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }
}

/**
 * A scrolling row of film simulations, each a small picture in its look with the name under it;
 * the chosen one is lit and scrolled into view.
 */
@SuppressLint("ViewConstructor") // made in code only
private class FilmStrip(c: android.content.Context, names: List<String>, private val onPick: (Int) -> Unit) : android.widget.HorizontalScrollView(c) {
    private val row = Kit.row(c)
    private val images = mutableListOf<ImageView>()
    private val labels = mutableListOf<android.widget.TextView>()
    private val cells = mutableListOf<View>()
    private var selected: Int? = null

    init {
        isHorizontalScrollBarEnabled = false
        names.forEachIndexed { i, name ->
            val image = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Deco.SURFACE)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, Deco.dp(c, 6f))
                    }
                }
                clipToOutline = true
            }
            val label = Kit.label(c, name, Deco.DIM, 8f).apply { gravity = Gravity.CENTER; maxLines = 1 }
            val cell = Kit.column(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = Deco.dpi(c, 3)
                setPadding(pad, pad, pad, pad)
                addView(image, LinearLayout.LayoutParams(Deco.dpi(c, 72), Deco.dpi(c, 50)))
                addView(label, LinearLayout.LayoutParams(Deco.dpi(c, 76), Kit.WRAP).apply { topMargin = Deco.dpi(c, 5) })
                isClickable = true
                contentDescription = name
                setOnClickListener { onPick(i) }
            }
            images += image
            labels += label
            cells += cell
            row.addView(cell, LinearLayout.LayoutParams(Kit.WRAP, Kit.WRAP).apply { marginEnd = Deco.dpi(c, 4) })
        }
        addView(row)
    }

    fun setThumbs(bitmaps: List<Bitmap>) {
        bitmaps.forEachIndexed { i, b -> images.getOrNull(i)?.setImageBitmap(b) }
    }

    fun select(index: Int?) {
        if (index != selected && index != null) {
            cells.getOrNull(index)?.let { cell -> post { smoothScrollTo(maxOf(0, cell.left - Deco.dpi(context, 24)), 0) } }
        }
        selected = index
        cells.forEachIndexed { i, cell ->
            val on = i == index
            cell.background = if (on) Deco.lit(context, 10f) else null
            labels[i].setTextColor(if (on) Deco.AMBER_LIGHT else Deco.DIM)
        }
    }
}
