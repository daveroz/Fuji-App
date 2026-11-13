package com.fujiapp.remote.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.VitalsState
import com.fujiapp.remote.model.CameraVitals
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The camera's shutter count and every usage counter it keeps: the shutter's actuations as a ring
 * of lamps lit up to the share of its rated life, like a lighting rig seen head on.
 */
class EquipmentScreen(private val a: MainActivity) {
    private val c = a
    private val ring = LedRing(c)
    private val actuations = Kit.text(c, "—", 38f, Deco.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(20, 38, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val lifeText = Kit.label(c, "", Deco.AMBER_LIGHT, 9f).apply { gravity = Gravity.CENTER }
    private val shotsText = Kit.text(c, "—", 20f, Deco.TEXT)
    private val readText = Kit.text(c, "—", 13f, Deco.MUTED).apply { gravity = Gravity.END }
    private val status = Kit.text(c, null, 13f, Deco.MUTED)
    private val progress = Kit.progress(c)
    private val read = Kit.button(c, "Read from camera", Kit.Style.SECONDARY, LineIcon.Kind.REFRESH) { a.session.fetchVitals() }
    private val sections = Kit.column(c)

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Layouts.hero(c).apply {
            setPadding(Deco.dpi(c, 16), Deco.dpi(c, 18), Deco.dpi(c, 16), Deco.dpi(c, 16))
            addView(FrameLayout(c).apply {
                addView(ring, FrameLayout.LayoutParams(Deco.dpi(c, RING_DP), Deco.dpi(c, RING_DP), Gravity.CENTER))
                addView(Kit.column(c).apply {
                    gravity = Gravity.CENTER
                    addView(Kit.label(c, "Actuations", Deco.DIM, 8f).apply { gravity = Gravity.CENTER })
                    addView(actuations, LinearLayout.LayoutParams(Deco.dpi(c, RING_DP - 90), Kit.WRAP).apply { topMargin = Deco.dpi(c, 8) })
                    addView(lifeText, Kit.lp(Kit.WRAP, top = Deco.dpi(c, 8)))
                }, FrameLayout.LayoutParams(Kit.WRAP, Kit.WRAP, Gravity.CENTER))
            }, Kit.lp(h = Deco.dpi(c, RING_DP)))
            addView(Kit.hairline(c), Kit.lp(top = Deco.dpi(c, 14)))
            addView(Kit.row(c).apply {
                addView(Kit.column(c).apply {
                    addView(Kit.label(c, "Shots taken", Deco.DIM, 8f))
                    addView(shotsText, Kit.lp(top = Deco.dpi(c, 6)))
                }, Kit.lp(0, weight = 1f))
                addView(Kit.column(c).apply {
                    gravity = Gravity.END
                    addView(Kit.label(c, "Read", Deco.DIM, 8f))
                    addView(readText, Kit.lp(Kit.WRAP, top = Deco.dpi(c, 8)))
                }, Kit.lp(0, weight = 1f))
            }, Kit.lp(top = Deco.dpi(c, 14)))
        }, Kit.lp(top = Deco.dpi(c, 8)))
        addView(status, Kit.lp(top = Deco.dpi(c, 16)))
        addView(progress, Kit.lp(top = Deco.dpi(c, 10)))
        addView(read, Kit.lp(top = Deco.dpi(c, 14)))
        addView(sections)
    })

    fun render(state: ConnState, vitals: VitalsState) {
        val connected = state as? ConnState.Connected
        val loaded = (vitals as? VitalsState.Loaded)?.vitals
        progress.visibility = if (vitals is VitalsState.Loading) View.VISIBLE else View.GONE
        status.text = when {
            connected == null -> "Connect to the camera to read its counters."
            !connected.capabilities.vitals -> "This camera doesn't share its counters over Bluetooth."
            vitals is VitalsState.Loading -> "Reading the camera's counters…"
            vitals is VitalsState.Failed -> "Couldn't read the counters: ${vitals.message}"
            vitals is VitalsState.Loaded -> if (connected.snapshot.vitalDataChanged) "The camera has newer data: read it again." else ""
            else -> "Not read yet."
        }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        status.setTextColor(if (vitals is VitalsState.Failed) Deco.RED else Deco.MUTED)
        readText.text = if (vitals is VitalsState.Loaded) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(vitals.readAt)) else "—"
        Kit.setEnabled(read, connected?.capabilities?.vitals == true && vitals !is VitalsState.Loading)

        if (shownVitals !== loaded || loaded == null) {
            shownVitals = loaded
            showLife(loaded)
            sections.removeAllViews()
            loaded?.sections()?.forEach { sections.addView(Kit.sectionPanel(c, it)) }
        }
    }

    /** The counters on screen, so they're only rebuilt when a new reading arrives. */
    private var shownVitals: CameraVitals? = null

    /** Shutter actuations against the rated life: the ring lit to the share used, the numbers inside. */
    private fun showLife(v: CameraVitals?) {
        val used = v?.mechanicalShutter
        val limit = v?.shutterLimit?.takeIf { it > 0 }
        actuations.text = used?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "—"
        shotsText.text = v?.totalShots?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "—"
        ring.fraction = if (used != null && limit != null) used.toFloat() / limit else null
        lifeText.text = when {
            limit != null -> String.format(Locale.getDefault(), "%d%% of %,d", v.shutterLifeUsedPercent ?: 0, limit)
            used != null -> "Rated life unknown"
            else -> ""
        }
    }

    private companion object {
        const val RING_DP = 248
    }
}
