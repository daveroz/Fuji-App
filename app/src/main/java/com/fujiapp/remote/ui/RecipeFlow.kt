package com.fujiapp.remote.ui

import android.app.Dialog
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.fujiapp.remote.BackupState
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.RecipesState
import com.fujiapp.remote.recipe.LibraryRecipe
import com.fujiapp.remote.recipe.Recipe
import com.fujiapp.remote.recipe.RecipeLibrary
import com.fujiapp.remote.usb.UsbCamera
import com.fujiapp.remote.usb.UsbJob
import com.fujiapp.remote.util.AppLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Loading a recipe into a slot, step by step: pick the slot, back up first (the app always asks),
 * connect by USB, write, then a report with Undo. Also reading the slots over USB, and the Lab's
 * "start from" and "save" actions.
 */
class RecipeFlow(private val a: MainActivity) {
    private val transfer get() = a.transfer
    private var progress: Dialog? = null

    fun openInLab(recipe: Recipe) {
        a.showRecipes()
        a.recipesScreen.openInLab(recipe)
    }

    fun saveMine(recipe: Recipe) {
        val problems = recipe.problems()
        if (problems.isNotEmpty()) {
            a.showNotice(problems.first())
            return
        }
        AppLog.ui("recipe saved: ${recipe.cameraName}")
        a.myRecipes.save(recipe.copy(name = recipe.cameraName), "")
        a.recipesScreen.library.refresh()
        a.showNotice("Saved \"${recipe.cameraName}\" to My recipes.")
    }

    fun deleteMine(item: LibraryRecipe) {
        MaterialAlertDialogBuilder(a)
            .setTitle(item.title)
            .setMessage("Delete this recipe from the phone?")
            .setPositiveButton("Delete") { _, _ ->
                a.myRecipes.delete(item.id)
                a.recipesScreen.library.refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The Lab's starting point: a library recipe, one of mine, or a slot read over USB. */
    fun chooseStart() {
        val options = RecipeLibrary.all.map { it.title to it.recipe } +
            a.myRecipes.list().map { "Mine: ${it.title}" to it.recipe } +
            transfer.lastSlots.mapNotNull { s -> s.recipe?.let { "C${s.number}: ${s.name.ifBlank { "not named" }}" to it } }
        a.choose("Start from", options.map { it.first }, -1) { i -> a.recipesScreen.lab.load(options[i].second) }
    }

    // ---- reading the slots ----

    fun readSlotsOverUsb() {
        if (transfer.busy) return
        if (!transfer.cameraPlugged()) {
            usbInstructions("Read all settings") { startReading() }
            return
        }
        startReading()
    }

    private fun startReading() {
        transfer.readSlots()
        watch()
    }

    // ---- loading a recipe ----

    fun load(recipe: Recipe) {
        val problems = recipe.problems()
        if (problems.isNotEmpty()) {
            a.showNotice("Can't load this recipe: ${problems.first()}.")
            return
        }
        if (transfer.busy) {
            a.showNotice("Wait for the current USB transfer to finish.")
            return
        }
        val names = slotNames()
        val items = (1..7).map { k -> names[k]?.takeIf { it.isNotBlank() }?.let { "C$k   $it" } ?: "C$k" }
        AppLog.ui("load ${recipe.cameraName}: choose slot")
        a.choose("Load \"${recipe.cameraName}\" into", items, -1) { i -> askBackupFirst(recipe, i + 1, names[i + 1]) }
    }

    /** The slot names we know: from a USB read, a Bluetooth read, or the newest backup. */
    private fun slotNames(): Map<Int, String> {
        transfer.lastSlots.takeIf { it.isNotEmpty() }?.let { slots -> return slots.associate { it.number to it.name } }
        (a.session.recipes.value as? RecipesState.Loaded)?.let { l -> return l.file.customSlots.associate { it.number to it.name } }
        val latest = a.session.store.list().firstNotNullOfOrNull { a.session.store.settings(it) }
        return latest?.customSlots?.associate { it.number to it.name }.orEmpty()
    }

    private fun askBackupFirst(recipe: Recipe, slot: Int, currentName: String?) {
        val what = currentName?.takeIf { it.isNotBlank() }?.let { "C$slot ($it)" } ?: "C$slot"
        MaterialAlertDialogBuilder(a)
            .setTitle("Back up first?")
            .setMessage(
                "Loading \"${recipe.cameraName}\" replaces the settings in $what.\n\n" +
                    "Back up the camera first: a backup holds every setting, all seven slots included, " +
                    "so you can always go back."
            )
            .setPositiveButton("Back up first") { _, _ -> backupThenConnect(recipe, slot) }
            .setNeutralButton("Load without a backup") { _, _ ->
                AppLog.ui("load ${recipe.cameraName} into C$slot without a backup")
                usbInstructions("Load") { write(recipe, slot, usbBackup = false) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Back up over Bluetooth when the camera is connected that way, otherwise over USB just before writing. */
    private fun backupThenConnect(recipe: Recipe, slot: Int) {
        val connected = a.session.state.value as? ConnState.Connected
        if (connected == null || !connected.capabilities.backup) {
            usbInstructions("Back up and load") { write(recipe, slot, usbBackup = true) }
            return
        }
        val name = "Before ${recipe.cameraName} in C$slot"
        if (!a.session.startBackup(name)) {
            a.showNotice("Wait for the current transfer to finish, then try again.")
            return
        }
        showProgress("Backing up over Bluetooth…")
        a.lifecycleScope.launch {
            val result = a.session.backup.first { it !is BackupState.Running }
            dismissProgress()
            when (result) {
                is BackupState.Done -> {
                    a.refreshBackups()
                    usbInstructions("Load", "Backed up as \"${result.meta.name}\".") { write(recipe, slot, usbBackup = false) }
                }
                is BackupState.Failed -> MaterialAlertDialogBuilder(a)
                    .setTitle("The backup didn't finish")
                    .setMessage("${result.message}\n\nThe app can back up over USB instead, right before loading the recipe.")
                    .setPositiveButton("Back up over USB") { _, _ -> usbInstructions("Back up and load") { write(recipe, slot, usbBackup = true) } }
                    .setNegativeButton("Cancel", null)
                    .show()
                else -> Unit
            }
        }
    }

    /** Tell the user how to connect by USB, then run [go]. */
    private fun usbInstructions(action: String, note: String? = null, go: () -> Unit) {
        val plugged = transfer.cameraPlugged()
        val steps = "1. On the camera, set the USB connection mode to USB RAW CONV./BACKUP RESTORE " +
            "(in its network/USB settings).\n2. Connect the camera to this phone with a USB-C cable.\n" +
            "3. Allow the app to use the camera when Android asks.\n\nWhile it's connected this way the camera's own menus are off. " +
            "Unplug the cable when it's done."
        MaterialAlertDialogBuilder(a)
            .setTitle(if (plugged) "Camera connected by USB" else "Connect by USB")
            .setMessage(listOfNotNull(note, steps).joinToString("\n\n"))
            .setPositiveButton(action) { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun write(recipe: Recipe, slot: Int, usbBackup: Boolean) {
        transfer.write(slot, recipe, usbBackup)
        watch()
    }

    private fun undo() {
        transfer.undo()
        watch()
    }

    // ---- progress and results ----

    /** Follow the USB job until it ends, then show what happened. */
    private fun watch() {
        val first = transfer.state.value
        showProgress((first as? UsbJob.Working)?.message ?: "Working…")
        a.lifecycleScope.launch {
            transfer.state
                .takeWhile { it is UsbJob.Working || it is UsbJob.Idle }
                .collect { job -> if (job is UsbJob.Working) updateProgress(job.message) }
            dismissProgress()
            result(transfer.state.value)
            transfer.clear()
        }
    }

    private fun result(job: UsbJob) {
        when (job) {
            is UsbJob.Wrote -> wrote(job)
            is UsbJob.Restored -> {
                val r = job.report
                MaterialAlertDialogBuilder(a)
                    .setTitle(if (r.ok) "C${r.slot} is back as it was" else "C${r.slot} is mostly back")
                    .setMessage(if (r.ok) "Every setting and the name read back as before." else issues(r))
                    .setPositiveButton("OK", null)
                    .show()
                a.session.forgetRecipes()
            }
            is UsbJob.SlotsRead -> {
                a.showNotice("Read C1–C7 over USB.")
                a.render()
            }
            is UsbJob.Failed -> MaterialAlertDialogBuilder(a)
                .setTitle("USB")
                .setMessage(job.message)
                .setPositiveButton("OK", null)
                .show()
            else -> Unit
        }
    }

    private fun wrote(job: UsbJob.Wrote) {
        val r = job.report
        val backup = job.backupName?.let { "\n\nBackup saved first: \"$it\" (in Backups)." } ?: ""
        val message = if (r.ok) {
            "${r.written.size} settings written and read back correctly" +
                (if (r.nameWritten) ", and the slot is named \"${job.recipeName}\"." else ".") + backup +
                "\n\nUnplug the cable to use the camera."
        } else {
            issues(r) + backup
        }
        a.session.forgetRecipes()
        a.refreshBackups()
        MaterialAlertDialogBuilder(a)
            .setTitle(if (r.ok) "C${r.slot} is now ${job.recipeName}" else "C${r.slot} loaded, with exceptions")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Undo") { _, _ -> undo() }
            .show()
    }

    private fun issues(r: UsbCamera.WriteReport): String {
        val lines = mutableListOf<String>()
        lines += "${r.written.size} settings written."
        r.refused.forEach { (w, code) -> lines += "• " + UsbCamera.refusalReason(w, code) }
        if (r.mismatches.isNotEmpty()) lines += "• Read back differently: ${r.mismatches.joinToString()}"
        return lines.joinToString("\n")
    }

    private fun showProgress(message: String) {
        dismissProgress()
        val c = a
        val text = Kit.text(c, message, 15f, Deco.TEXT)
        val bar = Kit.progress(c).apply { visibility = View.VISIBLE }
        val box = Kit.column(c).apply {
            val pad = Deco.dpi(c, 24)
            setPadding(pad, pad, pad, pad / 2)
            addView(text)
            addView(bar, Kit.lp(top = Deco.dpi(c, 16)))
        }
        progress = MaterialAlertDialogBuilder(a).setView(box).setCancelable(false).create().also { it.show() }
        progressText = text
    }

    private var progressText: android.widget.TextView? = null

    private fun updateProgress(message: String) {
        progressText?.text = message
    }

    private fun dismissProgress() {
        progress?.dismiss()
        progress = null
        progressText = null
    }
}
