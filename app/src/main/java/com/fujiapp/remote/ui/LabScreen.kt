package com.fujiapp.remote.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.fujiapp.remote.preview.PreviewSource
import com.fujiapp.remote.preview.RecipePreview
import com.fujiapp.remote.preview.SampleScene
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
 * Try a recipe on a picture: every setting can be changed and the preview follows. The preview
 * approximates the look on the phone; the camera's own rendering will differ in detail.
 */
@SuppressLint("ClickableViewAccessibility")
class LabScreen(private val a: MainActivity) {
    private val c = a
    var recipe: Recipe = Recipe("MY RECIPE", FilmSim.CLASSIC_CHROME, dynamicRange = DynamicRange.DR200)
        private set

    private var source: PreviewSource? = null
    private var original: Bitmap? = null
    private var rendered: Bitmap? = null
    private var showingOriginal = false
    private var renderJob: Job? = null
    private var updating = false

    private val image = ImageView(c).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Preview. Touch and hold to see the original."
    }
    private val status = Kit.label(c, "Preview · hold to compare", Deco.DIM, 10f)

    private val name = EditText(c).apply {
        setTextColor(Deco.TEXT)
        setHintTextColor(Deco.DIM)
        hint = "Recipe name"
        typeface = Deco.bold(c)
        textSize = 18f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        filters = arrayOf(InputFilter.LengthFilter(Recipe.NAME_MAX), InputFilter.AllCaps())
        background = Deco.box(c, Deco.WELL, Deco.BORDER, 12f)
        setPadding(Deco.dpi(c, 14), Deco.dpi(c, 12), Deco.dpi(c, 14), Deco.dpi(c, 12))
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
    private val wbRed = Kit.SliderRow(c, "WB shift, red", -9f, 9f, 1f, ::signedInt) { change(recipe.copy(wbRed = it.roundToInt())) }
    private val wbBlue = Kit.SliderRow(c, "WB shift, blue", -9f, 9f, 1f, ::signedInt) { change(recipe.copy(wbBlue = it.roundToInt())) }
    private val dynamicRange = Kit.Chips(c, listOf("Auto", "DR100", "DR200", "DR400")) { i -> change(recipe.copy(dynamicRange = DynamicRange.entries[i])) }
    private val highlight = Kit.SliderRow(c, "Highlight", -2f, 4f, 0.5f, ::signedHalf) { change(recipe.copy(highlight = it.toDouble())) }
    private val shadow = Kit.SliderRow(c, "Shadow", -2f, 4f, 0.5f, ::signedHalf) { change(recipe.copy(shadow = it.toDouble())) }
    private val color = Kit.SliderRow(c, "Color", -4f, 4f, 1f, ::signedInt) { change(recipe.copy(color = it.roundToInt())) }
    private val sharpness = Kit.SliderRow(c, "Sharpness", -4f, 4f, 1f, ::signedInt) { change(recipe.copy(sharpness = it.roundToInt())) }
    private val noise = Kit.SliderRow(c, "High ISO NR", -4f, 4f, 1f, ::signedInt) { change(recipe.copy(noiseReduction = it.roundToInt())) }
    private val clarity = Kit.SliderRow(c, "Clarity", -5f, 5f, 1f, ::signedInt) { change(recipe.copy(clarity = it.roundToInt())) }
    private val toneWc = Kit.SliderRow(c, "Toning, warm–cool", -18f, 18f, 1f, ::signedInt) { change(recipe.copy(monoWarmCool = it.roundToInt())) }
    private val toneMg = Kit.SliderRow(c, "Toning, magenta–green", -18f, 18f, 1f, ::signedInt) { change(recipe.copy(monoMagentaGreen = it.roundToInt())) }
    private val chromeRow = labelled("Color chrome", chrome.view)
    private val chromeBlueRow = labelled("Color chrome FX blue", chromeBlue.view)

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.label(c, "Lab", Deco.DIM), Kit.lp(top = Deco.dpi(c, 4)))
        addView(Kit.text(c, "Try a recipe", 30f), Kit.lp(top = Deco.dpi(c, 12)))
        addView(
            Kit.text(c, "Change any setting and the picture follows. It's an approximation of the look on the phone: the camera's own result will differ in detail.", 14f, Deco.MUTED),
            Kit.lp(top = Deco.dpi(c, 12)),
        )
        addView(Kit.column(c).apply {
            background = Deco.glass(c, 18f)
            setPadding(Deco.dpi(c, 6), Deco.dpi(c, 6), Deco.dpi(c, 6), Deco.dpi(c, 10))
            addView(image, Kit.lp())
            addView(status, Kit.lp(top = Deco.dpi(c, 8)).apply { marginStart = Deco.dpi(c, 8) })
        }, Kit.lp(top = Deco.dpi(c, 18)))
        addView(Kit.row(c).apply {
            addView(Kit.button(c, "Your photo", Kit.Style.SECONDARY) { a.pickLabPhoto() }, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 8) })
            addView(Kit.button(c, "Sample", Kit.Style.QUIET) { useSample() }, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 8) })
            addView(Kit.button(c, "Start from", Kit.Style.QUIET) { a.recipeFlow.chooseStart() }, Kit.lp(0, weight = 1f))
        }, Kit.lp(top = Deco.dpi(c, 12)))

        addView(Kit.sectionTitle(c, "Name"))
        addView(name, Kit.lp())

        addView(Kit.sectionTitle(c, "Film"))
        addView(Kit.panel(c).apply {
            addView(filmSim.view)
            addView(Kit.hairline(c))
            addView(labelled("Grain", grain.view))
            addView(chromeRow)
            addView(chromeBlueRow)
            addView(toneWc.view)
            addView(toneMg.view)
        })

        addView(Kit.sectionTitle(c, "White balance"))
        addView(Kit.panel(c).apply {
            addView(whiteBalance.view)
            addView(Kit.hairline(c))
            addView(kelvin.view)
            addView(wbRed.view)
            addView(wbBlue.view)
        })

        addView(Kit.sectionTitle(c, "Tone and detail"))
        addView(Kit.panel(c).apply {
            addView(labelled("Dynamic range", dynamicRange.view))
            addView(highlight.view)
            addView(shadow.view)
            addView(color.view)
            addView(sharpness.view)
            addView(noise.view)
            addView(clarity.view)
        })

        addView(Kit.button(c, "Load into a slot") { a.recipeFlow.load(recipe) }, Kit.lp(top = Deco.dpi(c, 24)))
        addView(Kit.button(c, "Save to my recipes", Kit.Style.SECONDARY) { a.recipeFlow.saveMine(recipe) }, Kit.lp(top = Deco.dpi(c, 10)))
    })

    init {
        image.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { showingOriginal = true; image.setImageBitmap(original); status.text = "Original" }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showingOriginal = false
                    image.setImageBitmap(rendered)
                    status.text = "Preview · hold to compare"
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
        image.setOnClickListener { }
        show()
        useSample()
    }

    private fun labelled(title: String, content: View): View = Kit.column(c).apply {
        setPadding(0, Deco.dpi(c, 12), 0, Deco.dpi(c, 4))
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
        show()
        requestRender()
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
        chromeRow.visibility = if (r.filmSim.mono) View.GONE else View.VISIBLE
        chromeBlueRow.visibility = chromeRow.visibility
        toneWc.show(r.filmSim.toning); toneWc.set(r.monoWarmCool.toFloat())
        toneMg.show(r.filmSim.toning); toneMg.set(r.monoMagentaGreen.toFloat())
        whiteBalance.set(if (r.whiteBalance == WhiteBalance.KELVIN) "${r.kelvin}K" else r.whiteBalance.label)
        kelvin.show(r.whiteBalance == WhiteBalance.KELVIN); kelvin.set(r.kelvin.toFloat())
        wbRed.set(r.wbRed.toFloat()); wbBlue.set(r.wbBlue.toFloat())
        dynamicRange.select(DynamicRange.entries.indexOf(r.dynamicRange))
        highlight.set(r.highlight.toFloat()); shadow.set(r.shadow.toFloat())
        color.show(!r.filmSim.mono); color.set(r.color.toFloat())
        sharpness.set(r.sharpness.toFloat()); noise.set(r.noiseReduction.toFloat()); clarity.set(r.clarity.toFloat())
        updating = false
    }

    fun useSample() = setPicture { SampleScene.pixels(SAMPLE_W, SAMPLE_H).let { Triple(SAMPLE_W, SAMPLE_H, it) } }

    /** Use a picture from the phone (already scaled down). */
    fun usePhoto(bitmap: Bitmap) = setPicture {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        Triple(bitmap.width, bitmap.height, px)
    }

    private fun setPicture(load: () -> Triple<Int, Int, IntArray>) {
        a.lifecycleScope.launch {
            status.text = "Preparing…"
            val (src, orig) = withContext(Dispatchers.Default) {
                val (w, h, px) = load()
                val bmp = createBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
                PreviewSource(w, h, px) to bmp
            }
            source = src
            original = orig
            rendered = createBitmap(src.width, src.height)
            requestRender(immediate = true)
        }
    }

    private fun requestRender(immediate: Boolean = false) {
        val src = source ?: return
        val target = rendered ?: return
        val r = recipe
        renderJob?.cancel()
        renderJob = a.lifecycleScope.launch {
            if (!immediate) delay(RENDER_DEBOUNCE_MS)
            val out = withContext(Dispatchers.Default) { IntArray(src.pixels.size).also { RecipePreview.render(src, r, it) } }
            target.setPixels(out, 0, src.width, 0, 0, src.width, src.height)
            if (!showingOriginal) image.setImageBitmap(target)
            image.invalidate()
            status.text = "Preview · hold to compare"
        }
    }

    companion object {
        const val SAMPLE_W = 900
        const val SAMPLE_H = 600
        /** Longest side a photo is scaled to for the preview. */
        const val PHOTO_MAX = 960
        private const val RENDER_DEBOUNCE_MS = 60L
        private val WB_CHOICES = listOf(
            WhiteBalance.AUTO, WhiteBalance.AUTO_WHITE, WhiteBalance.AUTO_AMBIENCE, WhiteBalance.DAYLIGHT, WhiteBalance.SHADE,
            WhiteBalance.FLUORESCENT_1, WhiteBalance.FLUORESCENT_2, WhiteBalance.FLUORESCENT_3, WhiteBalance.INCANDESCENT,
            WhiteBalance.UNDERWATER, WhiteBalance.KELVIN,
        )

        fun signedInt(v: Float): String = Recipe.signed(v.roundToInt())
        fun signedHalf(v: Float): String = Recipe.signed((v * 2).roundToInt() / 2.0)
    }
}
