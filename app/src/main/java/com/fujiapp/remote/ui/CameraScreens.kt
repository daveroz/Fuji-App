package com.fujiapp.remote.ui

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.VitalsState
import com.fujiapp.remote.ble.FoundCamera
import com.fujiapp.remote.ble.FujiAdvert
import com.fujiapp.remote.ble.FujiGatt
import com.fujiapp.remote.ble.FujiProtocol
import com.fujiapp.remote.ble.PowerState
import java.util.Locale

/** Find and pair a camera, or reconnect to a paired one. */
@SuppressLint("MissingPermission")
class ConnectScreen(private val a: MainActivity) {
    private val c = a
    private val status = Kit.text(c, null, 15f, Deco.TEXT_SOFT)
    private val progress = Kit.progress(c)
    private val art = CameraView(c, lit = false)
    private val find = Kit.button(c, "Find camera", icon = LineIcon.Kind.BLUETOOTH) { a.onFindPressed() }
    private val pairedTitle = Kit.sectionTitle(c, "Paired cameras")
    private val paired = Kit.column(c)
    private val foundTitle = Kit.sectionTitle(c, "Nearby")
    private val found = Kit.column(c)

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Layouts.hero(c).apply { addView(art, Kit.lp()) }, Kit.lp(top = Deco.dpi(c, 4)))
        addView(Kit.label(c, "Bluetooth", Deco.DIM), Kit.lp(top = Deco.dpi(c, 18)))
        addView(Kit.text(c, "Connect your camera", 30f), Kit.lp(top = Deco.dpi(c, 12)))
        addView(
            Kit.text(
                c,
                "First time: on the camera, open PAIRING REGISTRATION in the Bluetooth settings, tap Find camera " +
                    "and pick it, then confirm the six-digit code on the camera (MENU/OK) and on the phone.\n\n" +
                    "After that, tap your camera under Paired cameras and switch the camera on.",
                14f, Deco.MUTED,
            ),
            Kit.lp(top = Deco.dpi(c, 14)),
        )
        addView(status, Kit.lp(top = Deco.dpi(c, 28)))
        addView(progress, Kit.lp(top = Deco.dpi(c, 12)))
        addView(find, Kit.lp(top = Deco.dpi(c, 18)))
        addView(pairedTitle)
        addView(paired)
        addView(foundTitle)
        addView(found)
    })

    fun render(state: ConnState) {
        art.refresh()
        val busy = state is ConnState.Connecting || (state is ConnState.Scanning && state.active)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text = when (state) {
            is ConnState.Idle -> "Not connected."
            is ConnState.Scanning -> if (state.active) "Searching for Fujifilm cameras…" else "Search finished. Tap a camera or search again."
            is ConnState.Connecting -> state.message
            is ConnState.Connected -> "Connected."
            is ConnState.Error -> state.message
        }
        status.setTextColor(if (state is ConnState.Error) Deco.RED else Deco.TEXT_SOFT)
        find.text = when {
            state is ConnState.Scanning && state.active -> "Stop searching"
            state is ConnState.Scanning -> "Search again"
            state is ConnState.Connecting -> "Cancel"
            else -> "Find camera"
        }

        val showLists = state !is ConnState.Connecting && state !is ConnState.Connected
        val pairedDevices = if (showLists && a.hasConnectPermission()) a.session.pairedCameras() else emptyList()
        pairedTitle.visibility = if (pairedDevices.isEmpty()) View.GONE else View.VISIBLE
        paired.removeAllViews()
        pairedDevices.forEach { d ->
            paired.addView(cameraRow(runCatching { d.name }.getOrNull() ?: "Fujifilm camera", "Paired · tap, then switch the camera on") {
                a.connectTo(d, waitForCamera = true)
            })
        }

        val cams = (state as? ConnState.Scanning)?.cameras.orEmpty().sortedByDescending { it.rssi }
        foundTitle.visibility = if (cams.isEmpty()) View.GONE else View.VISIBLE
        found.removeAllViews()
        cams.forEach { cam -> found.addView(cameraRow(title(cam), subtitle(cam)) { a.onCameraPicked(cam) }) }
        if (state is ConnState.Scanning && !state.active && cams.isEmpty()) {
            found.addView(Kit.text(c, "No camera found. Is it in PAIRING REGISTRATION, or just switched on?", 14f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 16)))
        }
    }

    private fun title(cam: FoundCamera) = listOf(cam.name, cam.advert.tag).filter { it.isNotBlank() }.distinct().joinToString(" · ")

    private fun subtitle(cam: FoundCamera) = when {
        cam.advert.kind == FujiAdvert.Kind.LEGACY -> "Older Bluetooth firmware · update the camera"
        cam.bonded -> "Paired with this phone · tap to connect"
        cam.advert.pairingMode -> "Ready to pair · tap to pair"
        else -> "Not in pairing mode"
    }

    private fun cameraRow(title: String, subtitle: String, onClick: () -> Unit): View = Kit.row(c).apply {
        background = Deco.pressable(Deco.glass(c, 18f), Deco.box(c, Deco.SURFACE_HI, Deco.BORDER_STRONG, 18f))
        setPadding(Deco.dpi(c, 18), Deco.dpi(c, 16), Deco.dpi(c, 18), Deco.dpi(c, 16))
        addView(Kit.column(c).apply {
            addView(Kit.text(c, title, 16f, Deco.TEXT, bold = true))
            addView(Kit.text(c, subtitle, 13f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 6)))
        }, Kit.lp(0, weight = 1f))
        addView(Kit.text(c, ">", 18f, Deco.DIM)) // Carbon+ has no ›
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = Kit.lp(bottom = Deco.dpi(c, 10))
    }
}

/** Everything the connected camera reports, live, plus the remote release. */
class StatusScreen(private val a: MainActivity) {
    private val c = a
    private val art = CameraView(c)
    private val strip = FrameLayout(c)
    private val sections = Kit.column(c)
    private val remoteCard = Kit.column(c)

    private val heroTitle = Layouts.wideTitle(c, "", 18f)
    private val heroDetail = Kit.label(c, "", Deco.DIM, 9f)

    // Laid out like Hasselblad's app: the camera in a dark panel, its name set wide, a row of spec
    // icons, then the two things to do with it as panels with an arrow.
    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Layouts.hero(c).apply {
            addView(art, Kit.lp())
            addView(heroTitle, Kit.lp(top = Deco.dpi(c, 12)))
            addView(heroDetail, Kit.lp(top = Deco.dpi(c, 6)))
            addView(Kit.hairline(c), Kit.lp(top = Deco.dpi(c, 14)))
            addView(strip, Kit.lp(top = Deco.dpi(c, 14)))
        }, Kit.lp(top = Deco.dpi(c, 2)))
        addView(Kit.row(c).apply {
            addView(tile("Photos", "Browse and download over Wi-Fi") { a.openCameraPage(MainActivity.CameraPage.PHOTOS) }, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 10) })
            addView(tile("Remote", "Live view, exposure, shutter") { a.openCameraPage(MainActivity.CameraPage.REMOTE) }, Kit.lp(0, weight = 1f))
        }, Kit.lp(top = Deco.dpi(c, 12)))
        addView(remoteCard)
        addView(sections)
        addView(Kit.row(c).apply {
            addView(Kit.button(c, "Refresh", Kit.Style.SECONDARY, LineIcon.Kind.REFRESH) { a.session.refreshAll() }, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 10) })
            addView(Kit.button(c, "Disconnect", Kit.Style.QUIET, LineIcon.Kind.CLOSE) { a.session.disconnect() }, Kit.lp(0, weight = 1f))
        }, Kit.lp(top = Deco.dpi(c, 28)))
    })

    init {
        remoteCard.addView(Kit.sectionTitle(c, "Remote release"))
        remoteCard.addView(Kit.panel(c, padV = 18).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(shutterButton(), LinearLayout.LayoutParams(Deco.dpi(c, 92), Deco.dpi(c, 92)))
            addView(Kit.label(c, "Hold to focus · release to shoot", Deco.DIM, 10f).apply { gravity = Gravity.CENTER }, Kit.lp(top = Deco.dpi(c, 14)))
        })
    }

    private var shown: Pair<ConnState.Connected, VitalsState>? = null

    fun render(state: ConnState.Connected, vitals: VitalsState) {
        art.refresh()
        // Render runs on every change anywhere in the app (transfer progress too); only rebuild
        // when the camera has told us something new.
        if (shown == (state to vitals)) return
        shown = state to vitals
        val s = state.snapshot
        val shots = (vitals as? VitalsState.Loaded)?.vitals?.totalShots
        val power = when (FujiProtocol.powerState(s[FujiGatt.POWER_KEY_STATE])) {
            PowerState.ON -> "On"
            PowerState.OFF -> "Off"
            PowerState.ASLEEP_STANDBY, PowerState.OFF_STANDBY -> "Standby"
            null -> "—"
        }
        heroTitle.text = (state.info.model ?: state.info.displayName).uppercase()
        heroDetail.text = listOfNotNull("FUJIFILM", state.info.cameraName, state.info.firmware?.let { "FW $it" }).joinToString("  ·  ")
        strip.removeAllViews()
        strip.addView(
            Layouts.specRow(
                c,
                listOf(
                    Layouts.Spec(LineIcon.Kind.SHOTS, shots?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "—", "SHOTS"),
                    Layouts.Spec(LineIcon.Kind.POWER, power, "POWER"),
                    Layouts.Spec(LineIcon.Kind.WIFI, FujiProtocol.apState(s[FujiGatt.AP_STATE])?.substringBefore(',') ?: "—", "WI-FI"),
                    Layouts.Spec(LineIcon.Kind.CHIP, state.info.firmware ?: "—", "FIRMWARE"),
                ),
            )
        )
        remoteCard.visibility = if (state.hasShutter) View.VISIBLE else View.GONE
        sections.removeAllViews()
        s.sections().forEach { sections.addView(Kit.sectionPanel(c, it)) }
    }

    /** A panel to go somewhere, like Hasselblad's "Discover": the title set wide, a line, an arrow. */
    private fun tile(title: String, detail: String, onClick: () -> Unit): View = Kit.column(c).apply {
        background = Deco.pressable(Deco.glass(c, Layouts.CARD_RADIUS), Deco.box(c, Deco.SURFACE_HI, Deco.BORDER_STRONG, Layouts.CARD_RADIUS))
        setPadding(Deco.dpi(c, 16), Deco.dpi(c, 14), Deco.dpi(c, 16), Deco.dpi(c, 14))
        addView(Kit.row(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Kit.label(c, title, Deco.AMBER_LIGHT, 12f), Kit.lp(0, weight = 1f))
            addView(Kit.text(c, "→", 16f, Deco.AMBER_LIGHT))
        })
        addView(Kit.hairline(c), Kit.lp(top = Deco.dpi(c, 10)))
        addView(Kit.text(c, detail, 12f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 10)))
        isClickable = true
        contentDescription = title
        setOnClickListener { onClick() }
    }

    /** A round shutter: amber ring, lit while held. Half press on touch, full press on release. */
    @SuppressLint("ClickableViewAccessibility")
    private fun shutterButton(): View {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Deco.KEY_GLASS)
            setStroke(Deco.dpi(c, 2), Deco.AMBER_DEEP)
        }
        val held = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Deco.KEY_GLASS_HI)
            setStroke(Deco.dpi(c, 3), Deco.AMBER)
        }
        return View(c).apply {
            background = ring
            contentDescription = "Shutter"
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = held
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        a.session.shutterPress()
                    }
                    MotionEvent.ACTION_UP -> {
                        v.background = ring
                        val inside = e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()
                        a.session.shutterRelease(shoot = inside)
                        if (inside) v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.background = ring
                        a.session.shutterRelease(shoot = false)
                    }
                }
                true
            }
        }
    }
}
