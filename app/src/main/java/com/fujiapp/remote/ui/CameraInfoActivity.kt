package com.fujiapp.remote.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fujiapp.remote.App
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.R
import com.fujiapp.remote.databinding.ActivityInfoBinding
import com.fujiapp.remote.databinding.ItemInfoRowBinding
import com.fujiapp.remote.model.CameraInfo
import kotlinx.coroutines.launch

/** Everything the camera reports about itself over Bluetooth. */
class CameraInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInfoBinding
    private val session: CameraSession get() = App.of(application)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.refreshButton.setOnClickListener { session.refreshInfo() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { state ->
                    val info = (state as? ConnState.Connected)?.info
                    binding.refreshButton.isEnabled = info != null
                    binding.empty.visibility = if (info == null) View.VISIBLE else View.GONE
                    render(info)
                }
            }
        }
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
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(20), 0, dp(4))
                }
            )
            for (row in section.rows) {
                val r = ItemInfoRowBinding.inflate(layoutInflater, binding.sections, true)
                r.label.text = row.label
                r.value.text = row.value
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
