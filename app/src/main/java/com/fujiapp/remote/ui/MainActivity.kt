package com.fujiapp.remote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fujiapp.remote.App
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.ble.FoundCamera
import com.fujiapp.remote.ble.FujiAdvert
import com.fujiapp.remote.model.BackupMeta
import com.fujiapp.remote.util.AppLog
import com.fujiapp.remote.util.LogExporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The one screen of the app: a header with the camera's name, live status and the LOG button;
 * four tabs (Camera, Equipment, Backups, Settings); and a notice bar for short messages.
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    val session: CameraSession get() = App.of(application)

    private enum class Tab(val title: String) { CAMERA("Camera"), EQUIPMENT("Equipment"), BACKUPS("Backups"), SETTINGS("Settings") }

    private lateinit var connectScreen: ConnectScreen
    private lateinit var statusScreen: StatusScreen
    private lateinit var equipmentScreen: EquipmentScreen
    private lateinit var backupsScreen: BackupsScreen
    private lateinit var settingsScreen: SettingsScreen

    private lateinit var title: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var content: FrameLayout
    private lateinit var notice: TextView
    private val tabViews = HashMap<Tab, Pair<View, TextView>>()
    private var tab = Tab.CAMERA
    private var shown: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var noticeAction: (() -> Unit)? = null
    private var afterGrant: (() -> Unit)? = null

    private val bluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = afterGrant
            afterGrant = null
            if (results.values.all { it }) action?.invoke()
            else showNotice("The app needs the Nearby devices (Bluetooth) permission to find your camera.")
        }

    private val locationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                session.setLocationSync(true)
            } else {
                showNotice("Location sync needs location access.")
            }
            render()
        }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val action = afterGrant
            afterGrant = null
            if (session.adapter?.isEnabled == true) action?.invoke() else showNotice("Turn Bluetooth on, then try again.")
        }

    /** Saving the log on Android 9 and lower: the user picks where. */
    private val createLogFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri != null) {
                runCatching { LogExporter.writeTo(this, uri) }
                    .onSuccess { showNotice("Log saved.") }
                    .onFailure { showNotice("Couldn't save the log: ${it.message}") }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        connectScreen = ConnectScreen(this)
        statusScreen = StatusScreen(this)
        equipmentScreen = EquipmentScreen(this)
        backupsScreen = BackupsScreen(this)
        settingsScreen = SettingsScreen(this)
        setContentView(buildRoot())
        session.clearTransferResults()
        backupsScreen.refreshList()
        AppLog.ui("app opened")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(session.state, session.vitals, session.backup, session.restore) { _, _, _, _ -> }
                        .collect { render() }
                }
                launch {
                    session.notice.collect { message ->
                        if (message != null) {
                            showNotice(message)
                            session.clearNotice()
                        }
                    }
                }
            }
        }
    }

    // ---- layout ----

    private fun buildRoot(): View {
        val c = this
        val header = Kit.column(c).apply {
            setBackgroundColor(Deco.HEADER)
            setPadding(Deco.dpi(c, Kit.PAGE_MARGIN), Deco.dpi(c, 16), Deco.dpi(c, Kit.PAGE_MARGIN), Deco.dpi(c, 16))
            addView(Kit.row(c).apply {
                addView(Kit.column(c).apply {
                    addView(Kit.label(c, "Fuji Remote", Deco.AMBER_LIGHT, 10f))
                    title = Kit.text(c, "No camera", 26f, Deco.TEXT, bold = true).apply { maxLines = 1 }
                    addView(title, Kit.lp(top = Deco.dpi(c, 8)))
                }, Kit.lp(0, weight = 1f))
                addView(Kit.label(c, "Log", Deco.TEXT_SOFT, 11f).apply {
                    gravity = Gravity.CENTER
                    minWidth = Deco.dpi(c, 64)
                    minHeight = Deco.dpi(c, 38)
                    background = Deco.pressable(Deco.glass(c, 19f), Deco.box(c, Deco.SURFACE_HI, Deco.BORDER_STRONG, 19f))
                    isClickable = true
                    contentDescription = "Save the log to Downloads"
                    setOnClickListener { saveLog() }
                })
            })
            addView(Kit.row(c).apply {
                statusDot = View(c)
                addView(statusDot, LinearLayout.LayoutParams(Deco.dpi(c, 7), Deco.dpi(c, 7)).apply { marginEnd = Deco.dpi(c, 10) })
                statusText = Kit.label(c, "Offline", Deco.MUTED, 10f)
                addView(statusText)
            }, Kit.lp(top = Deco.dpi(c, 12)))
        }

        content = FrameLayout(c)

        val tabRow = Kit.row(c)
        for (t in Tab.entries) {
            val indicator = View(c)
            val label = Kit.label(c, t.title, Deco.DIM, 10f).apply { gravity = Gravity.CENTER }
            tabRow.addView(Kit.column(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, Deco.dpi(c, 16))
                addView(indicator, LinearLayout.LayoutParams(Deco.dpi(c, 24), Deco.dpi(c, 2)))
                addView(label, Kit.lp(top = Deco.dpi(c, 14)))
                isClickable = true
                setOnClickListener { select(t) }
            }, Kit.lp(0, weight = 1f))
            tabViews[t] = indicator to label
        }
        val tabBar = Kit.column(c).apply {
            setBackgroundColor(Deco.HEADER)
            addView(Kit.hairline(c, Deco.BORDER))
            addView(tabRow)
        }

        val column = Kit.column(c).apply {
            setBackgroundColor(Deco.BG)
            addView(header)
            addView(Kit.hairline(c, Deco.BORDER))
            addView(content, Kit.lp(h = 0, weight = 1f))
            addView(tabBar)
        }

        notice = Kit.text(c, null, 14f, Deco.TEXT).apply {
            background = Deco.box(c, 0xF20D1422.toInt(), Deco.BORDER_STRONG, 16f)
            setPadding(Deco.dpi(c, 18), Deco.dpi(c, 14), Deco.dpi(c, 18), Deco.dpi(c, 14))
            visibility = View.GONE
            setOnClickListener {
                noticeAction?.invoke()
                hideNotice()
            }
        }

        val root = FrameLayout(c).apply {
            setBackgroundColor(Deco.BG)
            addView(column, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
            addView(notice, FrameLayout.LayoutParams(Kit.MATCH, Kit.WRAP, Gravity.BOTTOM).apply {
                val m = Deco.dpi(c, 16)
                setMargins(m, m, m, Deco.dpi(c, 96))
            })
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, bars.top + Deco.dpi(c, 16), header.paddingRight, header.paddingBottom)
            tabBar.setPadding(0, 0, 0, bars.bottom)
            (notice.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + Deco.dpi(c, 96)
            insets
        }
        select(Tab.CAMERA)
        return root
    }

    private fun select(t: Tab) {
        if (t != tab) AppLog.ui("tab: ${t.title}")
        tab = t
        tabViews.forEach { (k, v) ->
            val on = k == t
            v.first.background = if (on) Deco.box(this, Deco.AMBER, 0, 1f) else null
            v.second.setTextColor(if (on) Deco.TEXT else Deco.DIM)
        }
        if (t == Tab.BACKUPS) backupsScreen.refreshList()
        render()
    }

    /** Draw the header and the current tab from the session's state. */
    private fun render() {
        if (!::content.isInitialized) return
        val state = session.state.value
        val connected = state as? ConnState.Connected

        title.text = connected?.info?.displayName ?: "No camera"
        val (dot, label) = when (state) {
            is ConnState.Connected -> Deco.GREEN to listOfNotNull("Connected", connected?.info?.model, connected?.info?.firmware?.let { "FW $it" }).joinToString(" · ")
            is ConnState.Connecting -> Deco.AMBER to "Connecting"
            is ConnState.Scanning -> Deco.AMBER to if (state.active) "Searching" else "Offline"
            is ConnState.Error -> Deco.RED to "Not connected"
            is ConnState.Idle -> Deco.DIM to "Offline"
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dot)
        }
        statusText.text = label

        val view = when (tab) {
            Tab.CAMERA -> if (connected != null) statusScreen.view else connectScreen.view
            Tab.EQUIPMENT -> equipmentScreen.view
            Tab.BACKUPS -> backupsScreen.view
            Tab.SETTINGS -> settingsScreen.view
        }
        if (shown !== view) {
            content.removeAllViews()
            content.addView(view, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
            shown = view
        }
        when (tab) {
            Tab.CAMERA -> if (connected != null) statusScreen.render(connected, session.vitals.value) else connectScreen.render(state)
            Tab.EQUIPMENT -> equipmentScreen.render(state, session.vitals.value)
            Tab.BACKUPS -> backupsScreen.render(state, session.backup.value, session.restore.value)
            Tab.SETTINGS -> settingsScreen.render(state)
        }
    }

    // ---- notices ----

    private val hideNoticeRunnable = Runnable { hideNotice() }

    fun showNotice(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        notice.text = if (action != null) "$message   ·   ${action.uppercase()}" else message
        noticeAction = onAction
        notice.visibility = View.VISIBLE
        handler.removeCallbacks(hideNoticeRunnable)
        handler.postDelayed(hideNoticeRunnable, if (action != null) 7_000L else 4_000L)
    }

    private fun hideNotice() {
        notice.visibility = View.GONE
        noticeAction = null
    }

    // ---- connecting ----

    fun onFindPressed() {
        AppLog.ui("find pressed")
        when (val s = session.state.value) {
            is ConnState.Scanning -> if (s.active) session.stopScan() else withBluetooth { session.startScan() }
            is ConnState.Connecting -> session.disconnect()
            else -> withBluetooth { session.startScan() }
        }
    }

    fun onCameraPicked(cam: FoundCamera) {
        AppLog.ui("camera picked: ${cam.name}")
        if (cam.advert.kind == FujiAdvert.Kind.LEGACY) {
            showNotice("This camera uses the older Bluetooth pairing. Update the camera firmware first.")
        } else {
            // Even when the camera doesn't look ready to pair, try: Android doesn't always
            // resolve a bonded camera's rotating address in scan results.
            withBluetooth { session.connect(cam.device) }
        }
    }

    fun connectTo(device: BluetoothDevice, waitForCamera: Boolean) {
        AppLog.ui("connect to paired camera")
        withBluetooth { session.connect(device, waitForCamera) }
    }

    private fun bluetoothPermissionList(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Run [action] once Bluetooth permissions are granted and Bluetooth is on. */
    private fun withBluetooth(action: () -> Unit) {
        val missing = bluetoothPermissionList().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            afterGrant = { withBluetooth(action) }
            bluetoothPermissions.launch(missing.toTypedArray())
            return
        }
        val adapter = session.adapter
        if (adapter == null) {
            showNotice("This phone has no Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            afterGrant = action
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        action()
    }

    // ---- settings ----

    fun onLocationSyncToggled(on: Boolean) {
        if (!on) {
            session.setLocationSync(false)
            render()
            return
        }
        if (session.location.hasPermission()) {
            session.setLocationSync(true)
            render()
        } else {
            locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // ---- backups ----

    fun askBackupName() {
        val info = (session.state.value as? ConnState.Connected)?.info
        val default = listOfNotNull(
            info?.model,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()),
        ).joinToString(" ")
        val input = EditText(this).apply {
            setText(default)
            setSelection(text.length)
            setTextColor(Deco.TEXT)
            typeface = Deco.regular(this@MainActivity)
        }
        val box = FrameLayout(this).apply {
            val pad = Deco.dpi(this@MainActivity, 22)
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Back up camera settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (!session.startBackup(input.text.toString().ifBlank { default })) showNotice("Wait for the current transfer to finish.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirmRestore(meta: BackupMeta) {
        if (session.isTransferring) {
            showNotice("Wait for the current transfer to finish.")
            return
        }
        session.restoreBlocker(meta)?.let {
            showNotice(it)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore \"${meta.name}\"?")
            .setMessage(
                "This replaces ALL the camera's settings, including C1–C7, with the ones in this backup. " +
                    "Keep the camera on and nearby until it finishes.\n\nTip: back up the current settings first."
            )
            .setPositiveButton("Restore") { _, _ ->
                if (!session.startRestore(meta) && session.isTransferring) showNotice("Wait for the current transfer to finish.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirmDelete(meta: BackupMeta) {
        MaterialAlertDialogBuilder(this)
            .setTitle(meta.name)
            .setMessage("Delete this backup from the phone?")
            .setPositiveButton("Delete") { _, _ ->
                AppLog.ui("backup deleted: ${meta.name}")
                session.store.delete(meta)
                backupsScreen.refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun shareBackup(meta: BackupMeta) {
        AppLog.ui("backup shared: ${meta.name}")
        share(session.store.exportToCache(meta), "application/octet-stream", meta.name)
    }

    // ---- log ----

    /** Save the whole recording as a .txt in Downloads (or where the user picks, before Android 10). */
    fun saveLog() {
        AppLog.ui("save log")
        if (LogExporter.canSaveToDownloads) {
            runCatching { LogExporter.saveToDownloads(this) }
                .onSuccess { (uri, name) ->
                    showNotice("Saved to Downloads: $name", "Share") { shareUri(uri, "text/plain", name) }
                }
                .onFailure {
                    AppLog.error("save log", it)
                    showNotice("Couldn't save the log: ${it.message}")
                }
        } else {
            createLogFile.launch(LogExporter.fileName())
        }
    }

    fun shareLog() {
        AppLog.ui("share log")
        val file = File(cacheDir, LogExporter.fileName())
        file.writeText("${AppLog.header(this)}\n\n${AppLog.text()}")
        share(file, "text/plain", file.name)
    }

    fun confirmClearLog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear the log?")
            .setMessage("The recording so far is deleted. New activity keeps being recorded.")
            .setPositiveButton("Clear") { _, _ ->
                AppLog.clear()
                showNotice("Log cleared.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun share(file: File, type: String, subject: String) =
        shareUri(FileProvider.getUriForFile(this, "$packageName.fileprovider", file), type, subject)

    private fun shareUri(uri: Uri, type: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }
}
