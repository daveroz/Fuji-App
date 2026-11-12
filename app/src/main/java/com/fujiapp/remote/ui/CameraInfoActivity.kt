package com.fujiapp.remote.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fujiapp.remote.App
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.R
import com.fujiapp.remote.VitalsState
import com.fujiapp.remote.databinding.ActivityInfoBinding
import com.fujiapp.remote.databinding.ItemInfoRowBinding
import com.fujiapp.remote.model.CameraInfo
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/** Everything the camera reports about itself over Bluetooth, including its shutter count. */
class CameraInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInfoBinding
    private val session: CameraSession get() = App.of(application)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.refreshButton.setOnClickListener {
            session.refreshInfo()
            session.fetchVitals()
        }
        binding.vitalsButton.setOnClickListener { session.fetchVitals() }

        // Read the counters once when the screen opens, as XApp's equipment status does.
        val conn = session.state.value as? ConnState.Connected
        if (conn?.capabilities?.vitals == true && session.vitals.value is VitalsState.Idle) session.fetchVitals()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.state, session.vitals) { s, v -> s to v }.collect { (state, vitals) ->
                    val connected = state as? ConnState.Connected
                    binding.refreshButton.isEnabled = connected != null
                    binding.empty.visibility = if (connected == null) View.VISIBLE else View.GONE
                    render(connected?.info)
                    renderVitals(connected, vitals)
                }
            }
        }
    }

    private fun renderVitals(connected: ConnState.Connected?, vitals: VitalsState) {
        val supported = connected?.capabilities?.vitals == true
        binding.vitalsProgress.visibility = if (vitals is VitalsState.Loading) View.VISIBLE else View.GONE
        binding.vitalsButton.visibility =
            if (supported && vitals !is VitalsState.Loading && vitals !is VitalsState.Loaded) View.VISIBLE else View.GONE
        binding.vitalsRows.removeAllViews()

        val loaded = (vitals as? VitalsState.Loaded)?.vitals
        val headline = loaded?.mechanicalShutter ?: loaded?.totalShots
        binding.shutterHeadline.visibility = if (headline != null) View.VISIBLE else View.GONE
        binding.shutterHeadline.text = headline?.let { String.format(Locale.getDefault(), "%,d", it) }

        val status = when {
            connected == null -> getString(R.string.not_connected_hint)
            !supported -> getString(R.string.vitals_unsupported)
            vitals is VitalsState.Loading -> getString(R.string.vitals_loading)
            vitals is VitalsState.Failed -> getString(R.string.vitals_failed, vitals.message)
            else -> null
        }
        binding.vitalsStatus.text = status
        binding.vitalsStatus.visibility = if (status == null) View.GONE else View.VISIBLE

        loaded?.rows()?.forEach { row -> addRow(binding.vitalsRows, row) }
    }

    private fun render(info: CameraInfo?) {
        binding.title.text = info?.displayName ?: getString(R.string.camera_info)
        binding.sections.removeAllViews()
        info ?: return
        for (section in info.sections()) {
            binding.sections.addView(
                TextView(this).apply {
                    text = section.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(20), 0, dp(4))
                }
            )
            section.rows.forEach { addRow(binding.sections, it) }
        }
    }

    private fun addRow(parent: LinearLayout, row: CameraInfo.Row) {
        val r = ItemInfoRowBinding.inflate(layoutInflater, parent, true)
        r.label.text = row.label
        r.value.text = row.value
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
