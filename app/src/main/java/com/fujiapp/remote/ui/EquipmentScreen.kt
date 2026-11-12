package com.fujiapp.remote.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.VitalsState
import com.fujiapp.remote.model.CameraVitals
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The camera's shutter count and every usage counter it keeps. */
class EquipmentScreen(private val a: MainActivity) {
    private val c = a
    private val headline = Kit.big(c, "—")
    private val life = FrameLayout(c)
    private val status = Kit.text(c, null, 14f, Deco.MUTED)
    private val progress = Kit.progress(c)
    private val read = Kit.button(c, "Read from camera", Kit.Style.SECONDARY) { a.session.fetchVitals() }
    private val sections = Kit.column(c)

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.label(c, "Shots taken", Deco.DIM), Kit.lp(top = Deco.dpi(c, 12)))
        addView(headline, Kit.lp(top = Deco.dpi(c, 10)))
        addView(life, Kit.lp(top = Deco.dpi(c, 20)))
        addView(status, Kit.lp(top = Deco.dpi(c, 20)))
        addView(progress, Kit.lp(top = Deco.dpi(c, 10)))
        addView(read, Kit.lp(top = Deco.dpi(c, 16)))
        addView(sections)
    })

    fun render(state: ConnState, vitals: VitalsState) {
        val connected = state as? ConnState.Connected
        val loaded = (vitals as? VitalsState.Loaded)?.vitals
        progress.visibility = if (vitals is VitalsState.Loading) View.VISIBLE else View.GONE
        headline.text = loaded?.totalShots?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "—"
        status.text = when {
            connected == null -> "Connect to the camera to read its counters."
            !connected.capabilities.vitals -> "This camera doesn't share its counters over Bluetooth."
            vitals is VitalsState.Loading -> "Reading the camera's counters…"
            vitals is VitalsState.Failed -> "Couldn't read the counters: ${vitals.message}"
            vitals is VitalsState.Loaded ->
                "Read at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(vitals.readAt))}" +
                    if (connected.snapshot.vitalDataChanged) " · the camera has newer data" else ""
            else -> "Not read yet."
        }
        status.setTextColor(if (vitals is VitalsState.Failed) Deco.RED else Deco.MUTED)
        Kit.setEnabled(read, connected?.capabilities?.vitals == true && vitals !is VitalsState.Loading)

        if (shownVitals !== loaded || loaded == null) {
            shownVitals = loaded
            life.removeAllViews()
            loaded?.let { life.addView(lifeBar(it)) }
            sections.removeAllViews()
            loaded?.sections()?.forEach { sections.addView(Kit.sectionPanel(c, it)) }
        }
    }

    /** The counters on screen, so they're only rebuilt when a new reading arrives. */
    private var shownVitals: CameraVitals? = null

    /** Shutter actuations against the rated life: a thin amber bar with the numbers either side. */
    private fun lifeBar(v: CameraVitals): View = Kit.column(c).apply {
        val used = v.mechanicalShutter
        val limit = v.shutterLimit?.takeIf { it > 0 }
        addView(Kit.row(c).apply {
            addView(Kit.label(c, "Shutter actuations", Deco.DIM, 10f), Kit.lp(0, weight = 1f))
            addView(Kit.label(c, if (limit != null) "Rated life" else "", Deco.DIM, 10f))
        })
        addView(Kit.row(c).apply {
            addView(Kit.text(c, used?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "—", 20f, Deco.TEXT, bold = true), Kit.lp(0, weight = 1f))
            if (limit != null) {
                addView(Kit.text(c, String.format(Locale.getDefault(), "%,d · %d%%", limit, v.shutterLifeUsedPercent ?: 0), 15f, Deco.TEXT_SOFT))
            }
        }, Kit.lp(top = Deco.dpi(c, 8)))
        if (limit != null && used != null) {
            val fraction = (used.toFloat() / limit).coerceIn(0f, 1f)
            addView(LinearLayout(c).apply {
                background = Deco.box(c, Deco.BORDER, 0, 3f)
                addView(View(c).apply { background = Deco.box(c, Deco.AMBER, 0, 3f) }, LinearLayout.LayoutParams(0, Kit.MATCH, fraction))
                addView(View(c), LinearLayout.LayoutParams(0, Kit.MATCH, 1f - fraction))
            }, LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 5)).apply { topMargin = Deco.dpi(c, 12) })
        }
    }
}
