package com.fujiapp.remote.ui

import android.annotation.SuppressLint
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
import androidx.core.view.isGone
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.fujiapp.remote.model.CameraInfo

/** Small view builders for the Deco look, so every screen is put together the same way. */
object Kit {

    const val PAGE_MARGIN = 20
    private const val PANEL_RADIUS = 13f
    /** Room at the foot of a page for the floating tab bar (70 dp and its margin, and a little air). */
    const val TAB_SPACE = 104
    /** The least room that clears the tab bar (70 dp and its 14 dp margin), for screens short of height. */
    const val TAB_CLEAR = 88
    /** Buttons are pills, like the references' big rounded actions. */
    private const val BUTTON_RADIUS = 12f

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

    /**
     * A titled glass card of readings, laid out like the references' details: each label above its
     * value, two to a line (a long value takes the line), thin rules between lines.
     */
    fun sectionPanel(c: Context, section: CameraInfo.Section): View = column(c).apply {
        addView(sectionTitle(c, section.title))
        addView(panel(c, padV = 6).apply {
            addView(Layouts.fieldGrid(c, section.rows.map { it.label to it.value }))
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

    /**
     * A button: the amber key (primary), glass (secondary) or text only (quiet). Its label stays on
     * one line, shrinking to fit a narrow button rather than wrapping. [compact] is 44 dp tall
     * instead of 52, for screens that need the room.
     */
    fun button(c: Context, title: String, style: Style = Style.PRIMARY, icon: LineIcon.Kind? = null, compact: Boolean = false, onClick: () -> Unit) = TextView(c).apply {
        text = title
        val color = when (style) {
            Style.PRIMARY -> KeyDrawable.INK
            Style.SECONDARY -> Deco.TEXT
            Style.QUIET -> Deco.MUTED
        }
        Deco.label(c, this, 12f, color)
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(7, 12, 1, TypedValue.COMPLEX_UNIT_SP)
        if (icon != null) {
            val size = Deco.dpi(c, if (compact) 16 else 18)
            val tint = if (style == Style.PRIMARY) KeyDrawable.INK else Deco.AMBER_LIGHT
            setCompoundDrawablesRelative(IconDrawable(icon, tint, c).apply { setBounds(0, 0, size, size) }, null, null, null)
            compoundDrawablePadding = Deco.dpi(c, 8)
        }
        gravity = Gravity.CENTER
        val inset = if (style == Style.PRIMARY) Deco.keyInset(c) else 0
        minHeight = Deco.dpi(c, if (compact) 44 else 52) + 2 * inset
        setPadding(Deco.dpi(c, 14) + inset, inset, Deco.dpi(c, 14) + inset, inset)
        background = when (style) {
            Style.PRIMARY -> Deco.key(c, -1f)
            Style.SECONDARY -> Deco.glassPressable(c, -1f)
            Style.QUIET -> Deco.pressable(Deco.box(c, 0, 0, BUTTON_RADIUS), Deco.glass(c, -1f, GlassDrawable.Tone.WELL))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A round glass bubble with an icon: for steps, closing, small actions (44 dp, thumb-sized). */
    fun iconButton(c: Context, icon: LineIcon.Kind, description: String, sizeDp: Int = 44, onClick: () -> Unit): View =
        LineIcon(c, icon, Deco.AMBER_LIGHT).apply {
            val pad = Deco.dpi(c, sizeDp) * 3 / 10
            setPadding(pad, pad, pad, pad)
            background = Deco.pressable(Deco.bubble(c, sizeDp.toFloat()), Deco.bubble(c, sizeDp.toFloat(), pressed = true))
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(Deco.dpi(c, sizeDp), Deco.dpi(c, sizeDp))
        }

    fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.38f
    }

    /** A small amber icon in a rounded square of warm glass, at the start of a settings row. */
    fun iconSquare(c: Context, icon: LineIcon.Kind): View = LineIcon(c, icon, Deco.AMBER_LIGHT).apply {
        val pad = Deco.dpi(c, 8)
        setPadding(pad, pad, pad, pad)
        background = Deco.glass(c, 8f, GlassDrawable.Tone.LENS)
        layoutParams = LinearLayout.LayoutParams(Deco.dpi(c, 34), Deco.dpi(c, 34)).apply { marginEnd = Deco.dpi(c, 14) }
    }

    /** A status lamp: [sizeDp] is the lamp with its glow (the core is about a third of it). */
    fun led(c: Context, color: Int, sizeDp: Int = 18): View = View(c).apply {
        background = Deco.led(color)
        layoutParams = LinearLayout.LayoutParams(Deco.dpi(c, sizeDp), Deco.dpi(c, sizeDp))
    }

    /** A setting that's on or off: an icon, title and explanation, and a switch. */
    class Toggle(c: Context, title: String, detail: String?, icon: LineIcon.Kind? = null, private val onChange: (Boolean) -> Unit) {
        private val switch = GlassSwitch(c)
        var checked = false
            private set

        val view: View = row(c).apply {
            setPadding(0, Deco.dpi(c, 14), 0, Deco.dpi(c, 14))
            if (icon != null) addView(iconSquare(c, icon))
            addView(column(c).apply {
                addView(text(c, title, 15f, Deco.TEXT))
                if (detail != null) addView(text(c, detail, 12f, Deco.MUTED), lp(top = Deco.dpi(c, 5)))
            }, lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 16) })
            addView(switch, LinearLayout.LayoutParams(WRAP, WRAP))
            isClickable = true
            setOnClickListener { if (isEnabled) onChange(!checked) }
        }

        fun set(on: Boolean?) {
            checked = on == true
            switch.checked = checked
            switch.alpha = if (on == null) 0.4f else 1f
        }

        fun enable(on: Boolean) = setEnabled(view, on)
    }

    /**
     * Choices in a glass capsule (a segmented control): the chosen one sits in a lighter lens with a
     * small amber light under its name.
     */
    class Segmented(c: Context, options: List<String>, private val onPick: (Int) -> Unit) {
        private val ctx = c
        private val items = options.mapIndexed { i, o ->
            label(c, o, Deco.MUTED, 10f).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(7, 10, 1, TypedValue.COMPLEX_UNIT_SP)
                minHeight = Deco.dpi(c, 40)
                isClickable = true
                setOnClickListener { onPick(i) }
            }
        }
        val view: View = row(c).apply {
            background = Deco.glass(c, 12f, GlassDrawable.Tone.WELL)
            val pad = Deco.dpi(c, 4)
            setPadding(pad, pad, pad, pad)
            items.forEach { addView(it, lp(0, weight = 1f)) }
        }

        fun select(index: Int) {
            items.forEachIndexed { i, t ->
                val on = i == index
                t.setTextColor(if (on) Deco.TEXT else Deco.MUTED)
                t.background = if (on) SegmentLens(ctx) else null
            }
        }
    }

    /** A row of choices; the chosen one is lit. */
    class Chips(c: Context, private val options: List<String>, wrap: Boolean = false, private val onPick: (Int) -> Unit) {
        private val ctx = c
        private val strip: ViewGroup = if (wrap) FlowLayout(c, Deco.dpi(c, 6), Deco.dpi(c, 6)) else row(c)
        private val chips = options.mapIndexed { i, o ->
            label(c, o, Deco.MUTED, 11f).apply {
                gravity = Gravity.CENTER
                minWidth = Deco.dpi(c, 56)
                minHeight = Deco.dpi(c, 38)
                isClickable = true
                setOnClickListener { onPick(i) }
            }.also {
                if (wrap) strip.addView(it, ViewGroup.LayoutParams(WRAP, WRAP))
                else strip.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = Deco.dpi(c, 6) })
            }
        }
        /** All choices visible ([wrap]: in lines), or one scrolling row. */
        val view: View = if (wrap) strip else HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }

        private var selected: Int? = null

        fun select(index: Int?) {
            // A newly chosen chip out of sight in a scrolling row is brought into view.
            if (index != selected && index != null && view is HorizontalScrollView) {
                val chip = chips.getOrNull(index)
                if (chip != null) view.post { view.smoothScrollTo(maxOf(0, chip.left - Deco.dpi(ctx, 24)), 0) }
            }
            selected = index
            val inset = Deco.dpi(ctx, Deco.Lit.INSET_DP.toInt())
            chips.forEachIndexed { i, chip ->
                val on = i == index
                chip.setTextColor(if (on) Deco.AMBER_LIGHT else Deco.MUTED)
                chip.background = if (on) Deco.lit(ctx, 10f) else Deco.glass(ctx, 10f)
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
            // A rounded-square thumb, like every other shape (if the slider won't take it, its own round one stays).
            runCatching { setCustomThumbDrawable(RoundedDrawable(0xFFF6E3CB.toInt(), 0, 0f, Deco.dp(c, 5.5f))) }
                .onFailure { com.fujiapp.remote.util.AppLog.add("slider thumb: ${it.message}") }
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

    /** A setting picked from a list: an icon, the title, the choice and a chevron; tap to change. */
    class PickerRow(c: Context, title: String, icon: LineIcon.Kind? = null, onClick: () -> Unit) {
        private val valueText = text(c, "", 13f, Deco.MUTED).apply { gravity = Gravity.END }
        val view: View = row(c).apply {
            setPadding(0, Deco.dpi(c, 14), 0, Deco.dpi(c, 14))
            if (icon != null) addView(iconSquare(c, icon))
            addView(text(c, title, 15f, Deco.TEXT), lp(0, weight = 1f))
            addView(valueText, lp(Kit.WRAP))
            addView(LineIcon(c, LineIcon.Kind.CHEVRON_RIGHT, Deco.DIM), LinearLayout.LayoutParams(Deco.dpi(c, 18), Deco.dpi(c, 18)).apply { marginStart = Deco.dpi(c, 8) })
            isClickable = true
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

    /** A scrolling page with the standard margins; its foot clears the floating tab bar. */
    fun page(c: Context, content: LinearLayout): View = android.widget.ScrollView(c).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        content.setPadding(Deco.dpi(c, PAGE_MARGIN), Deco.dpi(c, 8), Deco.dpi(c, PAGE_MARGIN), Deco.dpi(c, TAB_SPACE + 16))
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    fun big(c: Context, value: String?, sizeSp: Float = 56f) = text(c, value, sizeSp, Deco.TEXT).apply {
        typeface = Typeface.create(Deco.regular(c), Typeface.NORMAL)
        letterSpacing = -0.01f
    }
}

/** Children laid out left to right, wrapping onto new lines: chips that all stay in view. */
@SuppressLint("ViewConstructor") // made in code only
class FlowLayout(c: Context, private val gapX: Int, private val gapY: Int) : ViewGroup(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gapY
                lineHeight = 0
            }
            x += child.measuredWidth + gapX
            widest = maxOf(widest, x - gapX)
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) widest else maxWidth
        setMeasuredDimension(width + paddingLeft + paddingRight, resolveSize(y + lineHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gapY
                lineHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gapX
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}
