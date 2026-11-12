package com.fujiapp.remote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fujiapp.remote.App
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.R
import com.fujiapp.remote.ble.FoundCamera
import com.fujiapp.remote.ble.FujiAdvert
import com.fujiapp.remote.databinding.ActivityMainBinding
import com.fujiapp.remote.databinding.ItemCameraBinding
import com.fujiapp.remote.util.ProtocolLog
import kotlinx.coroutines.launch
import java.io.File

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val session: CameraSession get() = App.of(application)
    private var afterGrant: (() -> Unit)? = null

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = afterGrant
            afterGrant = null
            if (results.values.all { it }) action?.invoke()
            else Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val action = afterGrant
            afterGrant = null
            if (session.adapter?.isEnabled == true) action?.invoke()
            else Toast.makeText(this, R.string.bluetooth_off, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.findButton.setOnClickListener {
            when (val s = session.state.value) {
                is ConnState.Scanning -> if (s.active) session.stopScan() else withBluetooth { session.startScan() }
                is ConnState.Connecting -> session.disconnect()
                else -> withBluetooth { session.startScan() }
            }
        }
        binding.infoButton.setOnClickListener { startActivity(Intent(this, CameraInfoActivity::class.java)) }
        binding.backupsButton.setOnClickListener { startActivity(Intent(this, BackupsActivity::class.java)) }
        binding.disconnectButton.setOnClickListener { session.disconnect() }
        binding.shareLogButton.setOnClickListener { shareLog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { render(it) }
            }
        }
    }

    private fun render(state: ConnState) {
        val connected = state is ConnState.Connected
        val busy = state is ConnState.Connecting || (state is ConnState.Scanning && state.active)
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.connectedPanel.visibility = if (connected) View.VISIBLE else View.GONE
        binding.howtoCard.visibility = if (connected) View.GONE else View.VISIBLE
        binding.findButton.visibility = if (connected) View.GONE else View.VISIBLE

        binding.status.text = when (state) {
            is ConnState.Idle -> getString(R.string.status_idle)
            is ConnState.Scanning -> getString(if (state.active) R.string.status_searching else R.string.status_search_done)
            is ConnState.Connecting -> state.message
            is ConnState.Connected -> getString(R.string.status_connected, state.info.displayName)
            is ConnState.Error -> state.message
        }
        binding.findButton.setText(
            when {
                state is ConnState.Scanning && state.active -> R.string.stop_searching
                state is ConnState.Scanning -> R.string.search_again
                state is ConnState.Connecting -> R.string.cancel
                else -> R.string.find_camera
            }
        )

        val showLists = state is ConnState.Idle || state is ConnState.Scanning || state is ConnState.Error
        renderPaired(if (showLists && hasConnectPermission()) session.pairedCameras() else emptyList())
        val found = (state as? ConnState.Scanning)?.cameras.orEmpty()
        renderFound(found)
        binding.noneFound.visibility =
            if (state is ConnState.Scanning && found.isEmpty() && !state.active) View.VISIBLE else View.GONE
    }

    private fun renderPaired(devices: List<BluetoothDevice>) {
        binding.pairedHeader.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        binding.pairedList.removeAllViews()
        for (d in devices) {
            addRow(binding.pairedList, runCatching { d.name }.getOrNull() ?: "Fujifilm camera", getString(R.string.cam_paired_wait)) {
                withBluetooth { session.connect(d, waitForCamera = true) }
            }
        }
    }

    private fun renderFound(cameras: List<FoundCamera>) {
        binding.foundHeader.visibility = if (cameras.isEmpty()) View.GONE else View.VISIBLE
        binding.foundList.removeAllViews()
        for (cam in cameras.sortedByDescending { it.rssi }) {
            val title = listOf(cam.name, cam.advert.tag).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            val subtitle = getString(
                when {
                    cam.advert.kind == FujiAdvert.Kind.LEGACY -> R.string.cam_legacy
                    cam.bonded -> R.string.cam_paired
                    cam.advert.pairingMode -> R.string.cam_pairing_mode
                    else -> R.string.cam_not_pairing
                }
            )
            addRow(binding.foundList, title, subtitle) {
                if (cam.advert.kind == FujiAdvert.Kind.LEGACY) {
                    Toast.makeText(this, R.string.toast_legacy, Toast.LENGTH_LONG).show()
                } else {
                    // Even when the camera doesn't look ready to pair, try: Android doesn't always
                    // resolve a bonded camera's rotating address in scan results.
                    withBluetooth { session.connect(cam.device) }
                }
            }
        }
    }

    private fun addRow(parent: LinearLayout, title: String, subtitle: String, onClick: () -> Unit) {
        val row = ItemCameraBinding.inflate(layoutInflater, parent, true)
        row.name.text = title
        row.subtitle.text = subtitle
        row.root.setOnClickListener { onClick() }
    }

    // ---- permissions ----

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Run [action] once Bluetooth permissions are granted and Bluetooth is on. */
    private fun withBluetooth(action: () -> Unit) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            afterGrant = { withBluetooth(action) }
            permissionRequest.launch(missing.toTypedArray())
            return
        }
        val adapter = session.adapter
        if (adapter == null) {
            Toast.makeText(this, R.string.bluetooth_off, Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            afterGrant = action
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        action()
    }

    // ---- log ----

    private fun shareLog() {
        val file = File(cacheDir, "fuji-bluetooth-log.txt")
        file.writeText(ProtocolLog.text().ifEmpty { "(empty)" })
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_log)))
    }
}
