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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val image = ImageView(c).apply {
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

    private val filmSim = Kit.PickerRow(c, "Film simulation") {
        a.choose("Film simulation", FilmSim.entries.map { it.label }, FilmSim.entries.indexOf(recipe.filmSim)) { i ->
            val sim = FilmSim.entries[i]
            change(recipe.copy(filmSim = sim, monoWarmCool = if (sim.toning) recipe.monoWarmCool else 0, monoMagentaGreen = if (sim.toning) recipe.monoMagentaGreen else 0))
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
        group(filmSim.view, grainRow, toneWc.view, toneMg.view),
        group(color.view, chromeRow, chromeBlueRow, monoNote),
        group(whiteBalance.view, kelvin.view, wbRed.view, wbBlue.view),
        group(labelled("Dynamic range", dynamicRange.view), highlight.view, shadow.view),
        group(sharpness.view, clarity.view, noise.view),
    )
    private val controls = FrameLayout(c)
    private val tabs = Kit.Chips(c, listOf("Film", "Color", "Light", "Tone", "Detail")) { showGroup(it) }
    private val scopeToggle = Kit.Chips(c, listOf("Scope")) { toggleScope() }

    val view: View = Kit.column(c).apply {
        val m = Deco.dpi(c, Kit.PAGE_MARGIN)
        setPadding(m, Deco.dpi(c, 10), m, Deco.dpi(c, 10))
        addView(Kit.row(c).apply {
            addView(name, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 8) })
            addView(small("Picture") {
                a.choose("Picture", listOf("Color chart with skin tones", "A photo from the phone"), -1) { i ->
                    if (i == 0) useSample() else a.pickLabPhoto()
                }
            })
            addView(small("Start") { a.recipeFlow.chooseStart() })
            addView(small("Camera") { a.recipeFlow.develop(recipe) })
        })
        addView(FrameLayout(c).apply {
            background = Deco.glass(c, 16f)
            setPadding(Deco.dpi(c, 4), Deco.dpi(c, 4), Deco.dpi(c, 4), Deco.dpi(c, 4))
            addView(image, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
            addView(scope, FrameLayout.LayoutParams(Deco.dpi(c, 104), Deco.dpi(c, 104), Gravity.TOP or Gravity.END).apply {
                setMargins(0, Deco.dpi(c, 4), Deco.dpi(c, 4), 0)
            })
            addView(status, FrameLayout.LayoutParams(Kit.WRAP, Kit.WRAP, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(Deco.dpi(c, 8), 0, 0, Deco.dpi(c, 6))
            })
        }, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 224)).apply { topMargin = Deco.dpi(c, 10) })
        addView(Kit.row(c).apply {
            addView(tabs.view, Kit.lp(0, weight = 1f))
            addView(scopeToggle.view, Kit.lp(Kit.WRAP).apply { marginStart = Deco.dpi(c, 4) })
        }, Kit.lp(top = Deco.dpi(c, 10)))
        addView(ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            addView(controls, FrameLayout.LayoutParams(Kit.MATCH, Kit.WRAP))
        }, Kit.lp(h = 0, weight = 1f).apply { topMargin = Deco.dpi(c, 4) })
        addView(Kit.row(c).apply {
            addView(Kit.button(c, "Load into a slot") { a.recipeFlow.load(recipe) }, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 8) })
            addView(Kit.button(c, "Save", Kit.Style.SECONDARY) { a.recipeFlow.saveMine(recipe) }, Kit.lp(Kit.WRAP))
        }, Kit.lp(top = Deco.dpi(c, 8)))
    }

    init {
        image.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showingOriginal = true
                    image.setImageBitmap(if (developed != null) rendered else original)
                    status.text = if (developed != null) "Phone preview" else "Original"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showingOriginal = false
                    image.setImageBitmap(developed ?: rendered)
                    status.text = if (developed != null) "Developed by the camera · hold for the phone preview" else "Hold to compare"
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
    }

    private fun small(title: String, onClick: () -> Unit): View =
        Kit.button(c, title, Kit.Style.QUIET, onClick).apply { minWidth = 0; minimumWidth = 0; setPadding(Deco.dpi(c, 10), 0, Deco.dpi(c, 10), 0) }

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
        if (showScope) requestRender(immediate = true)
    }

    private fun labelled(title: String, content: View): View = Kit.column(c).apply {
        setPadding(0, Deco.dpi(c, 10), 0, Deco.dpi(c, 4))
        addView(Kit.text(c, title, 14f, Deco.TEXT_SOFT))
        addView(content, Kit.lp(top = Deco.dpi(c, 8)))
    }

    /** Start from [r]: its settings fill the controls. */
    fun load(r: Recipe) {
        recipe = r.copy(name = r.cameraName)
        show()
        requestRender()
    }

    private fun change(r: Recipe) {
        recipe = r
        developed = null // a changed recipe needs a new development
        show()
        requestRender()
    }

    /** Show the camera's own rendering of [r] (a JPEG it developed from a RAW), with its vectorscope. */
    fun showDeveloped(jpeg: ByteArray, r: Recipe) {
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
            developed = bmp
            if (recipe != r) { recipe = r.copy(name = r.cameraName); show() }
            image.setImageBitmap(bmp)
            status.text = "Developed by the camera · hold for the phone preview"
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
        filmSim.set(r.filmSim.label)
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

    fun useSample() = setPicture { Triple(SAMPLE_W, SAMPLE_H, SampleScene.pixels(SAMPLE_W, SAMPLE_H)) }

    /** Use a picture from the phone (already scaled down). */
    fun usePhoto(bitmap: Bitmap) = setPicture {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        Triple(bitmap.width, bitmap.height, px)
    }

    private fun setPicture(load: () -> Triple<Int, Int, IntArray>) {
        developed = null
        a.lifecycleScope.launch {
            status.text = "Preparing…"
            val (src, orig) = withContext(Dispatchers.Default) {
                val (w, h, px) = load()
                PreviewSource(w, h, px) to createBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
            }
            source = src
            original = orig
            rendered = createBitmap(src.width, src.height)
            scopeBitmap = createBitmap(SCOPE, SCOPE)
            requestRender(immediate = true)
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
            if (scopePixels != null && scopeTarget != null) {
                scopeTarget.setPixels(scopePixels, 0, SCOPE, 0, 0, SCOPE, SCOPE)
                scope.setImageBitmap(scopeTarget)
                scope.invalidate()
            }
            if (developed == null) status.text = "Hold to compare"
        }
    }

    companion object {
        /** Longest side the camera's developed JPEG is shown at. */
        private const val DEVELOPED_MAX = 1400
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
