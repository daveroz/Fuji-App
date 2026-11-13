package com.fujiapp.remote.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.RecipesState
import com.fujiapp.remote.VitalsState
import com.fujiapp.remote.model.BackupMeta
import com.fujiapp.remote.model.SettingsFile
import java.text.DateFormat
import java.util.Date

/**
 * The C1–C7 custom settings on the camera: names from its settings file over Bluetooth (offline,
 * the newest backup stands in), and every setting of every slot over USB.
 */
class CameraRecipes(private val a: MainActivity) {
    private val c = a
    private val source = Kit.text(c, null, 14f, Deco.MUTED)
    private val progress = Kit.progress(c)
    private val read = Kit.button(c, "Read from camera", Kit.Style.SECONDARY, LineIcon.Kind.BLUETOOTH) { a.session.readRecipes() }
    private val save = Kit.button(c, "Save as backup", Kit.Style.QUIET, LineIcon.Kind.ARCHIVE) { a.askBackupName(fromRecipes = true) }
    private val slots = Kit.column(c)
    private var shownKey: Any? = null
    private val usbSlots = Kit.column(c)
    private val usbStatus = Kit.text(c, null, 14f, Deco.MUTED)
    private val usbRead = Kit.button(c, "Read all settings over USB", Kit.Style.SECONDARY, LineIcon.Kind.DOWNLOAD) { a.recipeFlow.readSlotsOverUsb() }
    private var usbShown: Any? = null

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.label(c, "Custom settings", Deco.DIM), Kit.lp(top = Deco.dpi(c, 4)))
        addView(Kit.text(c, "On the camera", 30f), Kit.lp(top = Deco.dpi(c, 12)))
        addView(
            Kit.text(c, "Your C1–C7 custom settings, read from the camera's settings file.", 14f, Deco.MUTED),
            Kit.lp(top = Deco.dpi(c, 14)),
        )
        addView(source, Kit.lp(top = Deco.dpi(c, 22)))
        addView(progress, Kit.lp(top = Deco.dpi(c, 10)))
        addView(Kit.row(c).apply {
            addView(read, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 10) })
            addView(save, Kit.lp(Kit.WRAP))
        }, Kit.lp(top = Deco.dpi(c, 16)))
        addView(slots, Kit.lp(top = Deco.dpi(c, 8)))

        addView(Kit.sectionTitle(c, "Every setting, over USB"))
        addView(
            Kit.text(
                c,
                "Bluetooth only carries the slot names. With a USB-C cable the app reads each slot's film simulation, " +
                    "white balance, tones and the rest, and can load recipes into a slot. On the camera, set the USB " +
                    "connection mode to USB RAW CONV./BACKUP RESTORE, then connect it to this phone.",
                14f, Deco.MUTED,
            )
        )
        addView(usbRead, Kit.lp(top = Deco.dpi(c, 14)))
        addView(usbStatus, Kit.lp(top = Deco.dpi(c, 10)))
        addView(usbSlots, Kit.lp(top = Deco.dpi(c, 4)))
    })

    fun render(state: ConnState, recipes: RecipesState, vitals: VitalsState) {
        val connected = state as? ConnState.Connected
        val canRead = connected?.capabilities?.backup == true
        val busy = a.session.isTransferring

        // Read once per connection when the screen is open, after the shutter count had its turn.
        if (canRead && recipes is RecipesState.Idle && !busy && (vitals !is VitalsState.Idle || !connected!!.capabilities.vitals)) {
            a.session.readRecipes(user = false)
        }

        progress.visibility = if (recipes is RecipesState.Loading) View.VISIBLE else View.GONE
        Kit.setEnabled(read, canRead && recipes !is RecipesState.Loading && !busy)
        save.visibility = if (recipes is RecipesState.Loaded) View.VISIBLE else View.GONE

        val loaded = recipes as? RecipesState.Loaded
        val backup = if (loaded == null) latestBackup(connected?.info?.model) else null
        val file = loaded?.file ?: backup?.second
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        source.text = when {
            recipes is RecipesState.Loading -> "Reading the camera's settings file…"
            loaded != null -> "Read from the camera at ${time.format(Date(loaded.readAt))}."
            recipes is RecipesState.Failed && backup != null ->
                "Couldn't read the camera (${recipes.message}). From your backup \"${backup.first.name}\", ${dateTime.format(Date(backup.first.savedAt))}."
            recipes is RecipesState.Failed -> "Couldn't read the camera: ${recipes.message}"
            backup != null -> "From your backup \"${backup.first.name}\", ${dateTime.format(Date(backup.first.savedAt))}."
            connected == null -> "Connect to the camera to read its recipes."
            !canRead -> "This camera doesn't share its settings file over Bluetooth."
            else -> null
        }
        source.visibility = if (source.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        source.setTextColor(if (recipes is RecipesState.Failed) Deco.RED else Deco.MUTED)

        val key = loaded ?: backup?.first
        if (key != shownKey) {
            shownKey = key
            drawSlots(file)
        }
        renderUsb()
    }

    private fun renderUsb() {
        val t = a.transfer
        Kit.setEnabled(usbRead, !t.busy)
        val job = t.state.value
        usbStatus.text = when (job) {
            is com.fujiapp.remote.usb.UsbJob.Working -> job.message
            is com.fujiapp.remote.usb.UsbJob.SlotsRead -> "Read over USB at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(job.readAt))}."
            else -> if (t.lastSlots.isEmpty()) null else "Last read over USB."
        }
        usbStatus.visibility = if (usbStatus.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val slotsNow = t.lastSlots
        if (slotsNow !== usbShown) {
            usbShown = slotsNow
            usbSlots.removeAllViews()
            for (slot in slotsNow) usbSlots.addView(usbSlot(slot), Kit.lp(top = Deco.dpi(c, 12)))
        }
    }

    private fun usbSlot(slot: com.fujiapp.remote.recipe.BankCodec.Slot): View = Kit.panel(c, padV = 14).apply {
        val recipe = slot.recipe
        addView(Kit.row(c).apply {
            addView(Kit.label(c, "C${slot.number}", Deco.AMBER_LIGHT, 12f), LinearLayout.LayoutParams(Deco.dpi(c, 52), Kit.WRAP))
            addView(Kit.text(c, slot.name.ifBlank { "Not named" }, 18f, Deco.TEXT, bold = true), Kit.lp(0, weight = 1f))
        })
        if (recipe == null) {
            addView(Kit.text(c, "This slot's settings couldn't be decoded.", 13f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 8)))
            return@apply
        }
        addView(Kit.hairline(c), Kit.lp(top = Deco.dpi(c, 12)))
        recipe.rows().forEachIndexed { i, (label, value) ->
            if (i > 0) addView(Kit.hairline(c))
            addView(Kit.readout(c, label, value))
        }
        addView(Kit.button(c, "Open in Lab", Kit.Style.QUIET, LineIcon.Kind.LAB) { a.recipeFlow.openInLab(recipe) }, Kit.lp(top = Deco.dpi(c, 8)))
    }

    private var backupLookup: Pair<String?, Int>? = null
    private var backupFound: Pair<BackupMeta, SettingsFile>? = null

    /** The newest backup that holds recipes (from the connected model, when there is one). */
    private fun latestBackup(model: String?): Pair<BackupMeta, SettingsFile>? {
        val store = a.session.store
        val lookup = model to store.revision
        if (lookup != backupLookup) {
            backupLookup = lookup
            backupFound = store.list()
                .filter { model == null || it.cameraModel.isBlank() || it.cameraModel.equals(model, ignoreCase = true) }
                .firstNotNullOfOrNull { meta -> store.settings(meta)?.takeIf { it.customSlots.isNotEmpty() }?.let { meta to it } }
        }
        return backupFound
    }

    private fun drawSlots(file: SettingsFile?) {
        slots.removeAllViews()
        val list = file?.customSlots.orEmpty()
        if (file != null && list.isEmpty()) {
            slots.addView(
                Kit.text(c, "The custom settings in this file couldn't be found (${file.model}, ${file.size} bytes).", 14f, Deco.MUTED),
                Kit.lp(top = Deco.dpi(c, 16)),
            )
            return
        }
        if (list.isEmpty()) return
        slots.addView(Kit.sectionTitle(c, "On the camera"))
        slots.addView(Kit.panel(c, padV = 6).apply {
            list.forEachIndexed { i, slot ->
                if (i > 0) addView(Kit.hairline(c))
                addView(Kit.row(c).apply {
                    setPadding(0, Deco.dpi(c, 16), 0, Deco.dpi(c, 16))
                    addView(Kit.label(c, slot.label, Deco.AMBER_LIGHT, 12f), LinearLayout.LayoutParams(Deco.dpi(c, 52), Kit.WRAP))
                    addView(
                        Kit.text(c, slot.name.ifBlank { "Not named" }, 20f, if (slot.name.isBlank()) Deco.DIM else Deco.TEXT, bold = slot.name.isNotBlank())
                            .apply { gravity = Gravity.CENTER_VERTICAL },
                        Kit.lp(0, weight = 1f),
                    )
                })
            }
        })
    }
}
