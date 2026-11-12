package com.fujiapp.remote.ui

import android.os.Build
import android.view.View
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.ble.FujiGatt
import com.fujiapp.remote.util.AppLog

/** The app's own settings, the camera's Bluetooth settings, and the log. */
class SettingsScreen(private val a: MainActivity) {
    private val c = a

    private val syncTime: Kit.Toggle = Kit.Toggle(
        c, "Set the camera clock",
        "When the camera asks, send the phone's date, time and time zone (as XApp does).",
    ) { on ->
        a.session.setSyncTime(on)
        syncTimeSet(on)
    }

    private val locationSync = Kit.Toggle(
        c, "Location sync",
        "Answer the camera's location requests so pictures are geotagged. Needs location access. " +
            "Off: the app ignores the requests, which keeps transfers stable.",
    ) { on -> a.onLocationSyncToggled(on) }

    private val cameraLocation = Kit.Toggle(c, "Smartphone location sync", "The camera's own setting: whether it asks a phone for its location.") { on ->
        a.session.setCameraLocationSync(on)
    }
    private val intervals = FujiGatt.LOCATION_SYNC_INTERVALS
    private val intervalChips = Kit.Chips(c, intervals.map { if (it < 60) "${it}s" else "${it / 60}m" }) { i ->
        a.session.setCameraLocationInterval(intervals[i])
    }
    private val connectWhileOff = Kit.Toggle(c, "Connect while power off", "The camera keeps Bluetooth on in standby, so the app can reach it while it's off.") { on ->
        a.session.setCameraConnectWhileOff(on)
    }
    private val cameraNote = Kit.text(c, null, 13f, Deco.MUTED)
    private val cameraPanel = Kit.panel(c)

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.label(c, "Preferences", Deco.DIM), Kit.lp(top = Deco.dpi(c, 12)))
        addView(Kit.text(c, "Settings", 30f), Kit.lp(top = Deco.dpi(c, 12)))

        addView(Kit.sectionTitle(c, "In this app"))
        addView(Kit.panel(c).apply {
            addView(syncTime.view)
            addView(Kit.hairline(c))
            addView(locationSync.view)
        })

        addView(Kit.sectionTitle(c, "On the camera"))
        addView(cameraPanel.apply {
            addView(cameraLocation.view)
            addView(Kit.hairline(c))
            addView(Kit.column(c).apply {
                setPadding(0, Deco.dpi(c, 14), 0, Deco.dpi(c, 14))
                addView(Kit.text(c, "Location interval", 15f))
                addView(Kit.text(c, "How often the camera asks for the location.", 13f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 5)))
                addView(intervalChips.view, Kit.lp(top = Deco.dpi(c, 12)))
            })
            addView(Kit.hairline(c))
            addView(connectWhileOff.view)
        })
        addView(cameraNote, Kit.lp(top = Deco.dpi(c, 10)))

        addView(Kit.sectionTitle(c, "Log"))
        addView(
            Kit.text(
                c,
                "The app records what it does with the camera, including every Bluetooth exchange and error. " +
                    "If something looks off, save the log and send it. It holds no locations or passwords.",
                14f, Deco.MUTED,
            )
        )
        addView(Kit.button(c, "Save log to Downloads") { a.saveLog() }, Kit.lp(top = Deco.dpi(c, 16)))
        addView(Kit.row(c).apply {
            addView(Kit.button(c, "Share log", Kit.Style.SECONDARY) { a.shareLog() }, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 10) })
            addView(Kit.button(c, "Clear log", Kit.Style.QUIET) { a.confirmClearLog() }, Kit.lp(0, weight = 1f))
        }, Kit.lp(top = Deco.dpi(c, 10)))

        addView(Kit.sectionTitle(c, "About"))
        addView(Kit.panel(c).apply {
            addView(Kit.readout(c, "App", AppLog.header(c).substringBefore(" · ")))
            addView(Kit.hairline(c))
            addView(Kit.readout(c, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
            addView(Kit.hairline(c))
            addView(Kit.readout(c, "Phone", "${Build.MANUFACTURER} ${Build.MODEL}"))
        })
    })

    private fun syncTimeSet(on: Boolean) {
        syncTime.set(on)
    }

    fun render(state: ConnState) {
        syncTime.set(a.session.settings.syncTime)
        locationSync.set(a.session.settings.locationSync)
        val connected = state as? ConnState.Connected
        val busy = a.session.isTransferring
        val s = connected?.snapshot
        cameraLocation.set(s?.locationSyncOn)
        connectWhileOff.set(s?.info?.connectWhilePowerOff)
        intervalChips.select(s?.locationSyncInterval?.let { intervals.indexOf(it) }?.takeIf { it >= 0 })
        val on = connected != null && !busy
        cameraLocation.enable(on && s?.get(FujiGatt.LOCATION_SYNC_SETTING) != null)
        intervalChips.enable(on && s?.get(FujiGatt.LOCATION_SYNC_CYCLE) != null)
        connectWhileOff.enable(on && s?.get(FujiGatt.REMOTE_BOOT_SETTING) != null)
        cameraNote.text = when {
            connected == null -> "Connect to the camera to change its settings."
            busy -> "Wait for the current transfer to finish."
            else -> "Changes are written to the camera straight away."
        }
    }
}
