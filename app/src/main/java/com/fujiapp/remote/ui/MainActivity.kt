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
import androidx.core.graphics.scale
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The one screen of the app: a header with the camera's name, live status and the LOG button;
 * five tabs (Camera, Recipes, Equipment, Backups, Settings); and a notice bar for short messages.
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    val session: CameraSession get() = App.of(application)
    val transfer: com.fujiapp.remote.usb.RecipeTransfer get() = (application as App).transfer
    val myRecipes: com.fujiapp.remote.recipe.MyRecipes get() = (application as App).myRecipes
    val recipeFlow: RecipeFlow by lazy { RecipeFlow(this) }
    val wifi: com.fujiapp.remote.wifi.WifiSession get() = (application as App).wifi
    val developer: com.fujiapp.remote.usb.RawDeveloper get() = (application as App).developer
    private var afterRaf: (() -> Unit)? = null

    /** What the Camera tab shows while connected: its status, or a Wi-Fi page. */
    enum class CameraPage { STATUS, PHOTOS, REMOTE }
    private var cameraPage = CameraPage.STATUS
    private lateinit var photosScreen: PhotosScreen
    private lateinit var remoteScreen: RemoteScreen
    private var afterWifiGrant: (() -> Unit)? = null

    private val tabIcons = HashMap<Tab, LineIcon>()

    private enum class Tab(val title: String, val icon: LineIcon.Kind) {
        CAMERA("Camera", LineIcon.Kind.SHOTS), RECIPES("Recipes", LineIcon.Kind.FILM), EQUIPMENT("Equipment", LineIcon.Kind.GAUGE),
        BACKUPS("Backups", LineIcon.Kind.ARCHIVE), SETTINGS("Settings", LineIcon.Kind.GEAR)
    }

    private lateinit var connectScreen: ConnectScreen
    private lateinit var statusScreen: StatusScreen
    lateinit var recipesScreen: RecipesScreen
        private set
    private lateinit var equipmentScreen: EquipmentScreen
    private lateinit var backupsScreen: BackupsScreen
    private lateinit var settingsScreen: SettingsScreen

    private lateinit var title: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var content: FrameLayout
    private lateinit var notice: TextView
    private lateinit var stage: StageView
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
                AppLog.ui("location access allowed")
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

    /** A picture for the Lab, from the phone's photos (no permission needed with the photo picker). */
    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) loadLabPhoto(uri)
        }

    /** A photo of the user's camera for the Camera tab. */
    private val pickCameraPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) loadCameraPicture(uri)
        }

    fun pickCameraPicture() {
        AppLog.ui("camera picture: pick")
        pickCameraPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun loadCameraPicture(uri: Uri) {
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                decodeScaled(uri, CAMERA_PICTURE_MAX)?.let { CameraPicture.save(this@MainActivity, it); true } ?: false
            }
            if (ok) render() else showNotice("Couldn't open that picture.")
        }
    }

    /** A RAW (RAF) to develop on the camera. */
    private val pickRaf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val then = afterRaf
            afterRaf = null
            if (uri == null) return@registerForActivityResult
            val name = developer.nameOf(uri)
            if (!name.endsWith(".raf", ignoreCase = true)) {
                AppLog.ui("develop: not a RAF: $name")
                showNotice("That's not a RAW file. Pick a .RAF this camera shot.")
                return@registerForActivityResult
            }
            developer.useRaf(uri)
            recipesScreen.lab.useRafPicture(uri)
            then?.invoke()
        }

    fun pickRawFile(then: () -> Unit) {
        afterRaf = then
        pickRaf.launch(arrayOf("image/x-fuji-raf", "image/*", "application/octet-stream"))
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
        recipesScreen = RecipesScreen(this)
        photosScreen = PhotosScreen(this)
        remoteScreen = RemoteScreen(this)
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
                    merge(
                        session.state, session.vitals, session.backup, session.restore, session.recipes, transfer.state,
                        wifi.phase, wifi.photos, wifi.progress, wifi.readings, wifi.focused, wifi.note, wifi.capturing,
                    ).collect { render() }
                }
                launch {
                    wifi.frame.collect { if (cameraPage == CameraPage.REMOTE) remoteScreen.showFrame(it) }
                }
                launch {
                    wifi.postview.collect { remoteScreen.showPostview(it) }
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

    override fun onResume() {
        super.onResume()
        stage.setRunning(true)
    }

    override fun onPause() {
        super.onPause()
        stage.setRunning(false)
    }

    // ---- layout ----

    private fun buildRoot(): View {
        val c = this
        // The header is see-through: the stage's ceiling lamps hang above the title.
        val header = Kit.column(c).apply {
            setPadding(Deco.dpi(c, Kit.PAGE_MARGIN), Deco.dpi(c, HEADER_TOP), Deco.dpi(c, Kit.PAGE_MARGIN), Deco.dpi(c, 16))
            addView(Kit.row(c).apply {
                addView(Kit.column(c).apply {
                    addView(Kit.label(c, "Fuji Remote", Deco.AMBER_LIGHT, 10f))
                    title = Kit.text(c, "No camera", 26f, Deco.TEXT, bold = true).apply { maxLines = 1 }
                    addView(title, Kit.lp(top = Deco.dpi(c, 8)))
                }, Kit.lp(0, weight = 1f))
                addView(Kit.label(c, "Log", Deco.TEXT_SOFT, 11f).apply {
                    gravity = Gravity.CENTER
                    minWidth = Deco.dpi(c, 64)
                    minHeight = Deco.dpi(c, 40)
                    setPadding(Deco.dpi(c, 14), 0, Deco.dpi(c, 16), 0)
                    val size = Deco.dpi(c, 16)
                    setCompoundDrawablesRelative(IconDrawable(LineIcon.Kind.LOG, Deco.AMBER_LIGHT, c).apply { setBounds(0, 0, size, size) }, null, null, null)
                    compoundDrawablePadding = Deco.dpi(c, 8)
                    background = Deco.pressable(Deco.glass(c, 20f), Deco.lit(c, 20f, pressed = true))
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
            val icon = LineIcon(c, t.icon, Deco.DIM)
            tabIcons[t] = icon
            val label = Kit.label(c, t.title, Deco.DIM, 10f).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                // Five tabs on a narrow phone: let a long label shrink rather than wrap.
                setAutoSizeTextTypeUniformWithConfiguration(7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            tabRow.addView(Kit.column(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, Deco.dpi(c, 12))
                addView(indicator, LinearLayout.LayoutParams(Deco.dpi(c, 24), Deco.dpi(c, 2)))
                addView(icon, LinearLayout.LayoutParams(Deco.dpi(c, 22), Deco.dpi(c, 22)).apply { topMargin = Deco.dpi(c, 10) })
                addView(label, Kit.lp(top = Deco.dpi(c, 6)))
                isClickable = true
                setOnClickListener { select(t) }
            }, Kit.lp(0, weight = 1f))
            tabViews[t] = indicator to label
        }
        val tabBar = Kit.column(c).apply {
            setBackgroundColor(TAB_BAR)
            addView(Kit.hairline(c, Deco.BORDER))
            addView(tabRow)
        }

        val column = Kit.column(c).apply {
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

        stage = StageView(c)
        val root = FrameLayout(c).apply {
            setBackgroundColor(Deco.BG)
            addView(stage, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
            addView(column, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
            addView(notice, FrameLayout.LayoutParams(Kit.MATCH, Kit.WRAP, Gravity.BOTTOM).apply {
                val m = Deco.dpi(c, 16)
                setMargins(m, m, m, Deco.dpi(c, 96))
            })
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, bars.top + Deco.dpi(c, HEADER_TOP), header.paddingRight, header.paddingBottom)
            stage.ceiling = bars.top
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
            tabIcons[k]?.tint = if (on) Deco.AMBER_LIGHT else Deco.DIM
        }
        if (t == Tab.BACKUPS) backupsScreen.refreshList()
        render()
    }

    /** Draw the header and the current tab from the session's state. */
    fun render() {
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
            // A Wi-Fi page stays up while Bluetooth is down: the camera closes Bluetooth for Wi-Fi.
            Tab.CAMERA -> when {
                cameraPage == CameraPage.PHOTOS -> photosScreen.view
                cameraPage == CameraPage.REMOTE -> remoteScreen.view
                connected == null -> connectScreen.view
                else -> statusScreen.view
            }
            Tab.RECIPES -> recipesScreen.view
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
            Tab.CAMERA -> when {
                cameraPage == CameraPage.PHOTOS -> photosScreen.render()
                cameraPage == CameraPage.REMOTE -> remoteScreen.render()
                connected == null -> connectScreen.render(state)
                else -> statusScreen.render(connected, session.vitals.value)
            }
            Tab.RECIPES -> recipesScreen.render(state, session.recipes.value, session.vitals.value)
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

    // ---- recipes ----

    fun showRecipes() = select(Tab.RECIPES)

    // ---- photos and remote over Wi-Fi ----

    private val wifiPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = afterWifiGrant
            afterWifiGrant = null
            if (results.values.all { it }) action?.invoke()
            else showNotice("Photos and remote need permission to connect to the camera's Wi-Fi (Nearby devices).")
        }

    fun openCameraPage(page: CameraPage) {
        val needed = when {
            Build.VERSION.SDK_INT >= 33 -> listOf("android.permission.NEARBY_WIFI_DEVICES")
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            afterWifiGrant = { openCameraPage(page) }
            wifiPermission.launch(needed.toTypedArray())
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showNotice("On this Android version, join the camera's Wi-Fi in Settings first.")
        }
        cameraPage = page
        when (page) {
            CameraPage.PHOTOS -> wifi.openPhotos()
            CameraPage.REMOTE -> wifi.openRemote()
            CameraPage.STATUS -> Unit
        }
        render()
    }

    fun closeCameraPage() {
        wifi.close()
        cameraPage = CameraPage.STATUS
        render()
    }

    fun refreshBackups() = backupsScreen.refreshList()

    /** A list to pick one from; [checked] marks the current choice (−1 for none). */
    fun choose(title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
        val b = MaterialAlertDialogBuilder(this).setTitle(title).setNegativeButton("Cancel", null)
        if (checked >= 0) {
            b.setSingleChoiceItems(items.toTypedArray(), checked) { d, i -> d.dismiss(); onPick(i) }
        } else {
            b.setItems(items.toTypedArray()) { _, i -> onPick(i) }
        }
        b.show()
    }

    fun pickLabPhoto() {
        AppLog.ui("lab: pick photo")
        pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun loadLabPhoto(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeScaled(uri, LabScreen.PHOTO_MAX) }
            if (bitmap != null) recipesScreen.lab.usePhoto(bitmap) else showNotice("Couldn't open that picture.")
        }
    }

    /** Decode a picture no larger than [max] pixels on its long side, as a bitmap whose pixels can be read. */
    private fun decodeScaled(uri: Uri, max: Int): android.graphics.Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, max.toFloat() / maxOf(w, h))
                d.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            decoded?.let {
                val scale = minOf(1f, max.toFloat() / maxOf(it.width, it.height))
                if (scale < 1f) it.scale((it.width * scale).toInt(), (it.height * scale).toInt()) else it
            }
        }
    }.onFailure { AppLog.error("lab photo", it) }.getOrNull()

    // ---- settings ----

    fun onLocationSyncToggled(on: Boolean) {
        if (!on || session.location.hasPermission()) {
            session.setLocationSync(on)
        } else {
            locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // ---- backups ----

    /** Name a new backup: downloaded now, or ([fromRecipes]) the settings file already read for the recipes. */
    fun askBackupName(fromRecipes: Boolean = false) {
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
                val name = input.text.toString().ifBlank { default }
                if (fromRecipes) {
                    runCatching { session.saveRecipesAsBackup(name) }.onFailure { showNotice("Couldn't save the backup: ${it.message}") }
                    backupsScreen.refreshList()
                } else if (!session.startBackup(name)) {
                    showNotice("Wait for the current transfer to finish.")
                }
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

    private val importBackupFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) importBackup(uri) }

    /** Bring in a backup saved elsewhere (shared from this app, or the camera's own file). */
    fun pickBackupToImport() {
        AppLog.ui("backup: import")
        importBackupFile.launch(arrayOf("application/octet-stream", "*/*"))
    }

    private fun importBackup(uri: Uri) {
        lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?.substringBeforeLast('.')?.trim()?.ifBlank { null } ?: "Imported backup"
                    val data = contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_IMPORT_BYTES) throw java.io.IOException("That file is too big to be a camera backup.")
                        }
                        out.toByteArray()
                    } ?: throw java.io.IOException("Couldn't open that file.")
                    val before = session.store.list().map { it.id }.toSet()
                    val meta = session.store.import(name, data) ?: throw java.io.IOException("That isn't a camera settings backup.")
                    meta to (meta.id !in before)
                }
            }
            result.onSuccess { (meta, added) ->
                AppLog.add("backup imported: ${meta.name} (${meta.cameraModel}, ${meta.size} bytes)")
                backupsScreen.refreshList()
                showNotice(if (added) "Imported \"${meta.name}\"." else "That backup is already here: \"${meta.name}\".")
            }.onFailure {
                AppLog.add("backup import failed: $it")
                showNotice(it.message ?: "Couldn't import that file.")
            }
        }
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

    private companion object {
        /** Room above the title for the stage's ceiling lamps, in dp. */
        const val HEADER_TOP = 24
        /** The tab bar: the night at 85%, so the haze shows faintly through it. */
        const val TAB_BAR = 0xD9070A10.toInt()
        /** A camera backup is a few KB compressed, about 70 KB unpacked. */
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        /** Longest side the camera picture is kept at. */
        const val CAMERA_PICTURE_MAX = 1400
    }
}
