package com.fujiapp.remote.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.fujiapp.remote.App
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val session: CameraSession get() = App.of(application)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener {
            when (session.state.value) {
                is ConnState.Connected -> session.disconnect()
                is ConnState.Connecting -> {}
                else -> session.connect(this)
            }
        }
        binding.liveButton.setOnClickListener {
            startActivity(Intent(this, LiveViewActivity::class.java))
        }
        binding.recipesButton.setOnClickListener {
            startActivity(Intent(this, RecipeActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { render(it) }
            }
        }
    }

    private fun render(state: ConnState) {
        val connecting = state is ConnState.Connecting
        binding.progress.visibility = if (connecting) View.VISIBLE else View.GONE
        binding.liveButton.isEnabled = state is ConnState.Connected
        when (state) {
            is ConnState.Disconnected -> {
                binding.status.text = getString(com.fujiapp.remote.R.string.status_disconnected)
                binding.connectButton.setText(com.fujiapp.remote.R.string.connect)
            }
            is ConnState.Connecting -> {
                binding.status.text = state.message
                binding.connectButton.setText(com.fujiapp.remote.R.string.connect)
            }
            is ConnState.Connected -> {
                binding.status.text = "Connected: ${state.cameraName}"
                binding.connectButton.setText(com.fujiapp.remote.R.string.disconnect)
            }
            is ConnState.Error -> {
                binding.status.text = state.message
                binding.connectButton.setText(com.fujiapp.remote.R.string.connect)
            }
        }
    }
}
