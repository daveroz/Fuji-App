package com.fujiapp.remote.usb

import android.content.Context
import android.hardware.usb.UsbManager
import com.fujiapp.remote.ble.DownloadedFile
import com.fujiapp.remote.data.BackupStore
import com.fujiapp.remote.model.CameraInfo
import com.fujiapp.remote.model.SettingsFile
import com.fujiapp.remote.recipe.BankCodec
import com.fujiapp.remote.recipe.Recipe
import com.fujiapp.remote.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** Where a USB job stands. */
sealed interface UsbJob {
    data object Idle : UsbJob
    data class Working(val message: String) : UsbJob
    data class Wrote(val report: UsbCamera.WriteReport, val recipeName: String, val backupName: String?) : UsbJob
    data class Restored(val report: UsbCamera.WriteReport) : UsbJob
    data class SlotsRead(val slots: List<BankCodec.Slot>, val readAt: Long) : UsbJob
    data class Failed(val message: String) : UsbJob
}

/**
 * Custom setting slots over USB: read all seven, load a recipe into one (after a backup), and put
 * a slot back. One job at a time; jobs run in the app's scope so leaving the screen doesn't cut a
 * write short.
 */
class RecipeTransfer(context: Context, private val store: BackupStore) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager: UsbManager? get() = app.getSystemService(UsbManager::class.java)

    private val _state = MutableStateFlow<UsbJob>(UsbJob.Idle)
    val state: StateFlow<UsbJob> = _state.asStateFlow()

    /** The last slots read over USB, kept for the Recipes screen. */
    var lastSlots: List<BankCodec.Slot> = emptyList()
        private set

    /** The slot as it was before the last recipe was loaded, for Undo. */
    var undoSnapshot: BankCodec.Slot? = null
        private set

    val busy: Boolean get() = _state.value is UsbJob.Working

    fun cameraPlugged(): Boolean = manager?.let { UsbCamera.find(it) } != null

    fun clear() {
        if (!busy) _state.value = UsbJob.Idle
    }

    fun readSlots() = run("read slots") { cam ->
        val slots = (1..SLOTS).map { k ->
            _state.value = UsbJob.Working("Reading C$k…")
            cam.readSlot(k)
        }
        lastSlots = slots
        UsbJob.SlotsRead(slots, System.currentTimeMillis())
    }

    /** Load [recipe] into C[slot]; with [backupFirst], download the camera's settings backup first. */
    fun write(slot: Int, recipe: Recipe, backupFirst: Boolean) = run("load ${recipe.cameraName} into C$slot") { cam ->
        var backupName: String? = null
        if (backupFirst) {
            _state.value = UsbJob.Working("Backing up the camera…")
            val raw = try {
                cam.downloadBackup()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw java.io.IOException("The backup over USB failed (${t.message}). Nothing was written.", t)
            }
            val model = SettingsFile.parse(raw)?.model
            backupName = "Before ${recipe.cameraName} in C$slot"
            store.save(backupName, CameraInfo(model = model), DownloadedFile("backup.dat", deflate(raw)))
            AppLog.add("usb: backup saved as \"$backupName\" (${raw.size} bytes)")
        }
        _state.value = UsbJob.Working("Loading ${recipe.cameraName} into C$slot…")
        val report = cam.writeRecipe(slot, recipe)
        undoSnapshot = report.before
        lastSlots = lastSlots.map { if (it.number == slot) report.after else it }
        UsbJob.Wrote(report, recipe.cameraName, backupName)
    }

    /** Put the slot changed last back the way it was. */
    fun undo() {
        val snapshot = undoSnapshot ?: return
        run("restore C${snapshot.number}") { cam ->
            _state.value = UsbJob.Working("Putting C${snapshot.number} back…")
            val report = cam.restoreSlot(snapshot)
            if (report.ok) undoSnapshot = null
            lastSlots = lastSlots.map { if (it.number == snapshot.number) report.after else it }
            UsbJob.Restored(report)
        }
    }

    private fun run(what: String, job: suspend (UsbCamera) -> UsbJob) {
        if (busy) return
        AppLog.ui("usb: $what")
        _state.value = UsbJob.Working("Looking for the camera on USB…")
        scope.launch {
            _state.value = try {
                val m = manager ?: throw java.io.IOException("This phone can't connect to USB devices.")
                val device = UsbCamera.find(m) ?: throw java.io.IOException(NOT_PLUGGED)
                _state.value = UsbJob.Working("Allow the app to use the camera…")
                if (!UsbCamera.requestPermission(app, m, device)) throw java.io.IOException("USB access wasn't allowed.")
                _state.value = UsbJob.Working("Connecting over USB…")
                withContext(Dispatchers.IO) {
                    UsbCamera.open(m, device) { AppLog.add(it) }.use { cam ->
                        val mode = cam.usbMode()
                        AppLog.add("usb: mode $mode")
                        if (mode != null && mode != BankCodec.USB_MODE_RAW_CONV) throw java.io.IOException(WRONG_MODE)
                        job(cam)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.error("usb: $what", t)
                UsbJob.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val SLOTS = 7
        const val NOT_PLUGGED = "No camera on USB. Connect it to this phone with a USB-C cable, with its USB mode set to USB RAW CONV./BACKUP RESTORE."
        const val WRONG_MODE = "The camera is in another USB mode. On the camera, set the USB connection mode to USB RAW CONV./BACKUP RESTORE, then plug the cable in again."

        /** A USB backup is the unpacked file; keep it compressed like a Bluetooth backup, so either can be restored the same way. */
        fun deflate(raw: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION)
            d.setInput(raw)
            d.finish()
            val out = ByteArrayOutputStream(raw.size / 4)
            val buf = ByteArray(8192)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }
    }
}
