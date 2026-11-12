package com.fujiapp.remote.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.fujiapp.remote.model.CameraInfo

/** Small view builders for the Deco look, so every screen is put together the same way. */
object Kit {

    const val PAGE_MARGIN = 24
    private const val PANEL_RADIUS = 20f

    val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = top
            bottomMargin = bottom
        }

    fun text(c: Context, value: CharSequence? = null, sizeSp: Float = 15f, color: Int = Deco.TEXT, bold: Boolean = false) =
        TextView(c).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = if (bold) Deco.bold(c) else Deco.regular(c)
            includeFontPadding = false
            setLineSpacing(Deco.dp(c, 3f), 1f)
        }

    fun label(c: Context, value: String, color: Int = Deco.MUTED, sizeSp: Float = 11f) =
        TextView(c).apply {
            text = value
            Deco.label(c, this, sizeSp, color)
            includeFontPadding = false
        }

    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun row(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun hairline(c: Context, color: Int = Deco.BORDER_SOFT) = View(c).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(MATCH, Math.max(1, Math.round(Deco.dp(c, 1f))))
    }

    /** A section title: an amber label with room above it. */
    fun sectionTitle(c: Context, title: String) = label(c, title, Deco.AMBER_LIGHT).apply {
        layoutParams = lp(top = Deco.dpi(c, 28), bottom = Deco.dpi(c, 10))
    }

    /** A glass panel to put rows in. */
    fun panel(c: Context, padH: Int = 18, padV: Int = 4) = column(c).apply {
        background = Deco.glass(c, PANEL_RADIUS)
        setPadding(Deco.dpi(c, padH), Deco.dpi(c, padV), Deco.dpi(c, padH), Deco.dpi(c, padV))
    }

    /** A reading: label on the left, value on the right. */
    fun readout(c: Context, label: String, value: String) = row(c).apply {
        setPadding(0, Deco.dpi(c, 13), 0, Deco.dpi(c, 13))
        addView(text(c, label, 14f, Deco.MUTED), lp(0, weight = 1f))
        addView(
            text(c, value, 14f, Deco.TEXT).apply {
                gravity = Gravity.END
                setTextIsSelectable(true)
            },
            lp(0, weight = 1.3f),
        )
    }

    /** A titled glass panel of readings, with hairlines between them. */
    fun sectionPanel(c: Context, section: CameraInfo.Section): View = column(c).apply {
        addView(sectionTitle(c, section.title))
        addView(panel(c).apply {
            section.rows.forEachIndexed { i, r ->
                if (i > 0) addView(hairline(c))
                addView(readout(c, r.label, r.value))
            }
        })
    }

    /** A strip of big readings side by side, like an exposure readout. */
    fun readoutStrip(c: Context, items: List<Pair<String, String>>): View = row(c).apply {
        background = Deco.glass(c, PANEL_RADIUS)
        setPadding(0, Deco.dpi(c, 16), 0, Deco.dpi(c, 16))
        items.forEachIndexed { i, (label, value) ->
            if (i > 0) addView(View(c).apply { setBackgroundColor(Deco.BORDER) }, LinearLayout.LayoutParams(Math.max(1, Deco.dpi(c, 1)), Deco.dpi(c, 36)))
            addView(column(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label(c, label, Deco.DIM, 10f))
                addView(
                    text(c, value, 17f, Deco.TEXT, bold = true).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    lp(top = Deco.dpi(c, 8)),
                )
            }, lp(0, weight = 1f))
        }
    }

    enum class Style { PRIMARY, SECONDARY, QUIET }

    /** A button: lit (primary), glass (secondary) or text only (quiet). */
    fun button(c: Context, title: String, style: Style = Style.PRIMARY, onClick: () -> Unit) = TextView(c).apply {
        text = title
        Deco.label(c, this, 12f, if (style == Style.PRIMARY) Deco.AMBER_LIGHT else if (style == Style.SECONDARY) Deco.TEXT_SOFT else Deco.MUTED)
        gravity = Gravity.CENTER
        minHeight = Deco.dpi(c, 52)
        val inset = if (style == Style.PRIMARY) Deco.dpi(c, Deco.Lit.INSET_DP.toInt()) else 0
        setPadding(Deco.dpi(c, 20) + inset, inset, Deco.dpi(c, 20) + inset, inset)
        background = when (style) {
            Style.PRIMARY -> Deco.pressable(Deco.lit(c, 16f), Deco.lit(c, 16f, pressed = true))
            Style.SECONDARY -> Deco.pressable(Deco.glass(c, 16f), Deco.box(c, Deco.SURFACE_HI, Deco.BORDER_STRONG, 16f))
            Style.QUIET -> Deco.pressable(Deco.box(c, 0, 0, 16f), Deco.box(c, Deco.GLASS, 0, 16f))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.38f
    }

    /** A setting that's on or off: title and explanation on the left, a pill that's lit when on. */
    class Toggle(c: Context, title: String, detail: String?, private val onChange: (Boolean) -> Unit) {
        private val pill = label(c, "Off", Deco.DIM, 10f).apply {
            gravity = Gravity.CENTER
            minWidth = Deco.dpi(c, 64)
            minHeight = Deco.dpi(c, 34)
        }
        private val ctx = c
        var checked = false
            private set

        val view: View = row(c).apply {
            setPadding(0, Deco.dpi(c, 14), 0, Deco.dpi(c, 14))
            addView(column(c).apply {
                addView(text(c, title, 15f, Deco.TEXT))
                if (detail != null) addView(text(c, detail, 13f, Deco.MUTED), lp(top = Deco.dpi(c, 5)))
            }, lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 16) })
            addView(pill, LinearLayout.LayoutParams(WRAP, WRAP))
            isClickable = true
            setOnClickListener { if (isEnabled) onChange(!checked) }
        }

        fun set(on: Boolean?) {
            checked = on == true
            pill.text = when (on) { true -> "On"; false -> "Off"; null -> "—" }
            pill.setTextColor(if (checked) Deco.AMBER_LIGHT else Deco.DIM)
            val inset = Deco.dpi(ctx, Deco.Lit.INSET_DP.toInt())
            pill.background = if (checked) Deco.lit(ctx, 17f) else Deco.box(ctx, Deco.WELL, Deco.BORDER, 14f)
            pill.setPadding(inset, inset, inset, inset)
        }

        fun enable(on: Boolean) = setEnabled(view, on)
    }

    /** A row of choices; the chosen one is lit. */
    class Chips(c: Context, private val options: List<String>, private val onPick: (Int) -> Unit) {
        private val ctx = c
        private val strip = row(c)
        private val chips = options.mapIndexed { i, o ->
            label(c, o, Deco.MUTED, 11f).apply {
                gravity = Gravity.CENTER
                minWidth = Deco.dpi(c, 56)
                minHeight = Deco.dpi(c, 38)
                isClickable = true
                setOnClickListener { onPick(i) }
            }.also { strip.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = Deco.dpi(c, 6) }) }
        }
        val view: View = HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }

        fun select(index: Int?) {
            val inset = Deco.dpi(ctx, Deco.Lit.INSET_DP.toInt())
            chips.forEachIndexed { i, chip ->
                val on = i == index
                chip.setTextColor(if (on) Deco.AMBER_LIGHT else Deco.MUTED)
                chip.background = if (on) Deco.lit(ctx, 19f) else Deco.box(ctx, Deco.WELL, Deco.BORDER, 16f)
                chip.setPadding(inset + Deco.dpi(ctx, 10), inset, inset + Deco.dpi(ctx, 10), inset)
            }
        }

        fun enable(on: Boolean) {
            setEnabled(view, on)
            chips.forEach { it.isEnabled = on }
        }
    }

    /** A setting on a scale: title and value above, an amber slider below. */
    class SliderRow(
        c: Context,
        title: String,
        from: Float,
        to: Float,
        step: Float,
        private val format: (Float) -> String,
        private val onChange: (Float) -> Unit,
    ) {
        private val valueText = label(c, "", Deco.AMBER_LIGHT, 11f)
        private val slider = Slider(c).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            value = from.coerceAtLeast(0f).coerceAtMost(to)
            labelBehavior = LabelFormatter.LABEL_GONE
            isTickVisible = false
            trackActiveTintList = ColorStateList.valueOf(Deco.AMBER)
            trackInactiveTintList = ColorStateList.valueOf(Deco.BORDER_STRONG)
            thumbTintList = ColorStateList.valueOf(Deco.AMBER_LIGHT)
            haloTintList = ColorStateList.valueOf(Deco.KEY_GLASS_HI)
            addOnChangeListener { _, v, fromUser ->
                valueText.text = format(v)
                if (fromUser) onChange(v)
            }
        }
        val view: View = column(c).apply {
            setPadding(0, Deco.dpi(c, 10), 0, Deco.dpi(c, 2))
            addView(row(c).apply {
                addView(text(c, title, 14f, Deco.TEXT_SOFT), lp(0, weight = 1f))
                addView(valueText)
            })
            addView(slider, lp(top = Deco.dpi(c, 2)))
        }

        fun set(v: Float) {
            // The slider refuses a value off its step grid (a slot can hold, say, 5250K on a 100K grid).
            val from = slider.valueFrom
            val step = slider.stepSize
            val snapped = (from + Math.round((v - from) / step) * step).coerceIn(from, slider.valueTo)
            if (slider.value != snapped) slider.value = snapped
            valueText.text = format(snapped)
        }

        fun show(on: Boolean) {
            view.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    /** A setting picked from a list: title on the left, the choice on the right; tap to change. */
    class PickerRow(c: Context, title: String, onClick: () -> Unit) {
        private val valueText = text(c, "", 14f, Deco.TEXT).apply { gravity = Gravity.END }
        val view: View = row(c).apply {
            setPadding(0, Deco.dpi(c, 14), 0, Deco.dpi(c, 14))
            addView(text(c, title, 14f, Deco.TEXT_SOFT), lp(0, weight = 1f))
            addView(valueText, lp(Kit.WRAP))
            addView(text(c, "  >", 14f, Deco.DIM))
            isClickable = true
            background = Deco.pressable(Deco.box(c, 0, 0, 12f), Deco.box(c, Deco.GLASS, 0, 12f))
            setOnClickListener { onClick() }
        }

        fun set(value: String) {
            valueText.text = value
        }

        fun show(on: Boolean) {
            view.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    /** A strip of color blocks: a recipe's palette at a glance. */
    fun palette(c: Context, colors: List<Int>, heightDp: Int = 8): View = row(c).apply {
        colors.forEachIndexed { i, color ->
            addView(View(c).apply { background = Deco.box(c, color, 0, 3f) }, LinearLayout.LayoutParams(0, Deco.dpi(c, heightDp), 1f).apply {
                if (i > 0) marginStart = Deco.dpi(c, 4)
            })
        }
    }

    /** A thin amber progress line. */
    fun progress(c: Context) = LinearProgressIndicator(c).apply {
        isIndeterminate = true
        setIndicatorColor(Deco.AMBER)
        trackColor = Deco.BORDER
        trackThickness = Deco.dpi(c, 2)
        visibility = View.GONE
    }

    /** A scrolling page with the standard margins. */
    fun page(c: Context, content: LinearLayout): View = android.widget.ScrollView(c).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        content.setPadding(Deco.dpi(c, PAGE_MARGIN), Deco.dpi(c, 8), Deco.dpi(c, PAGE_MARGIN), Deco.dpi(c, 40))
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    fun big(c: Context, value: String?, sizeSp: Float = 56f) = text(c, value, sizeSp, Deco.TEXT).apply {
        typeface = Typeface.create(Deco.regular(c), Typeface.NORMAL)
        letterSpacing = -0.01f
    }
}
