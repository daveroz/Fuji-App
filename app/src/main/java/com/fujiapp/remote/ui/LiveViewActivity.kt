package com.fujiapp.remote.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fujiapp.remote.App
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.R
import com.fujiapp.remote.databinding.ActivityLiveBinding
import kotlinx.coroutines.launch

class LiveViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveBinding
    private val session: CameraSession get() = App.of(application)
    private var streaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!session.isConnected) {
            Toast.makeText(this, R.string.not_connected_hint, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.liveToggle.setOnClickListener { toggleLive() }
        binding.captureButton.setOnClickListener {
            lifecycleScope.launch {
                val ok = session.capture()
                Toast.makeText(
                    this@LiveViewActivity,
                    if (ok) "Captured" else "Capture failed",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        binding.shutterUp.setOnClickListener { lifecycleScope.launch { session.stepShutter(true) } }
        binding.shutterDown.setOnClickListener { lifecycleScope.launch { session.stepShutter(false) } }
        binding.apertureUp.setOnClickListener { lifecycleScope.launch { session.stepAperture(true) } }
        binding.apertureDown.setOnClickListener { lifecycleScope.launch { session.stepAperture(false) } }
        binding.evUp.setOnClickListener { lifecycleScope.launch { session.stepExposureComp(true) } }
        binding.evDown.setOnClickListener { lifecycleScope.launch { session.stepExposureComp(false) } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.frame.collect { jpeg ->
                    if (jpeg != null) {
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        if (bmp != null) {
                            binding.liveImage.setImageBitmap(bmp)
                            binding.hint.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun toggleLive() {
        streaming = !streaming
        if (streaming) {
            session.startLiveView(this)
            binding.liveToggle.setText(R.string.stop_live)
            binding.hint.setText(R.string.start_live)
        } else {
            session.stopLiveView()
            binding.liveToggle.setText(R.string.start_live)
            binding.liveImage.setImageDrawable(null)
            binding.hint.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (streaming) session.stopLiveView()
    }
}
