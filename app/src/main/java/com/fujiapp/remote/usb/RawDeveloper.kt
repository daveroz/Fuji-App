package com.fujiapp.remote.usb

import android.content.ContentValues
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.fujiapp.remote.recipe.BankCodec
import com.fujiapp.remote.recipe.ConversionProfile
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
import java.io.File
import java.io.IOException

/** Where a camera development stands. */
sealed interface DevelopState {
    data object Idle : DevelopState
    data class Working(val message: String) : DevelopState
    data class Done(val jpeg: ByteArray, val recipe: Recipe, val rafName: String, val seconds: Double) : DevelopState
    data class Failed(val message: String) : DevelopState
}

/**
 * Develop on camera: the camera's own processor renders a RAF with a recipe, as X RAW Studio does.
 * Over USB (camera in USB RAW CONV./BACKUP RESTORE): upload the RAF once, then for each version
 * patch the conversion profile, start a reduced-size conversion and fetch the JPEG.
 */
class RawDeveloper(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager: UsbManager? get() = app.getSystemService(UsbManager::class.java)
    private var camera: UsbCamera? = null
    private var uploaded: Uri? = null
    private var baseProfile: ByteArray? = null

    private val _state = MutableStateFlow<DevelopState>(DevelopState.Idle)
    val state: StateFlow<DevelopState> = _state.asStateFlow()

    /** The RAF chosen for developing, and its name. */
    var raf: Uri? = null
        private set
    var rafName: String = ""
        private set

    val busy: Boolean get() = _state.value is DevelopState.Working

    fun useRaf(uri: Uri) {
        raf = uri
        rafName = displayName(uri) ?: "RAW"
        AppLog.ui("develop: RAF $rafName")
    }

    fun develop(recipe: Recipe) {
        val uri = raf ?: return
        if (busy) return
        AppLog.ui("develop: ${recipe.cameraName} on $rafName")
        _state.value = DevelopState.Working("Connecting over USB…")
        scope.launch {
            val started = System.currentTimeMillis()
            _state.value = try {
                val m = manager ?: throw IOException("This phone can't connect to USB devices.")
                val cam = camera ?: run {
                    val device = UsbCamera.find(m) ?: throw IOException(RecipeTransfer.NOT_PLUGGED)
                    if (!UsbCamera.requestPermission(app, m, device)) throw IOException("USB access wasn't allowed.")
                    withContext(Dispatchers.IO) {
                        UsbCamera.open(m, device) { AppLog.add(it) }.also { c ->
                            val mode = c.usbMode()
                            if (mode != null && mode != BankCodec.USB_MODE_RAW_CONV) {
                                c.close()
                                throw IOException(RecipeTransfer.WRONG_MODE)
                            }
                        }
                    }.also { camera = it; uploaded = null }
                }
                val jpeg = withContext(Dispatchers.IO) { run(cam, uri, recipe) }
                DevelopState.Done(jpeg, recipe, rafName, (System.currentTimeMillis() - started) / 1000.0)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.error("develop", t)
                close()
                DevelopState.Failed(explain(t))
            }
        }
    }

    private fun run(cam: UsbCamera, uri: Uri, recipe: Recipe): ByteArray {
        if (uploaded != uri || baseProfile == null) {
            val size = size(uri) ?: throw IOException("Couldn't read the size of $rafName.")
            _state.value = DevelopState.Working("Uploading $rafName…")
            app.contentResolver.openInputStream(uri)?.use { input ->
                cam.uploadRaf(size, input) { sent -> _state.value = DevelopState.Working("Uploading $rafName… ${sent * 100 / size}%") }
            } ?: throw IOException("Couldn't open $rafName.")
            val profile = cam.conversionProfile()
            AppLog.add("develop: profile ${profile.size} bytes, ${ConversionProfile.cells(profile)} cells")
            baseProfile = profile
            uploaded = uri
        }
        _state.value = DevelopState.Working("The camera is developing ${recipe.cameraName}…")
        val patched = ConversionProfile.apply(baseProfile!!, recipe)
        val set = cam.setConversionProfile(patched)
        if (set != Ptp.OK) throw PtpException(set, "Setting the recipe")
        runCatching { cam.conversionProfile() }.getOrNull()?.let { back ->
            val wrong = ConversionProfile.mismatches(patched, back, recipe)
            if (wrong.isNotEmpty()) AppLog.add("develop: cells read back differently: $wrong")
        }
        val before = cam.objectHandles().toSet()
        val start = cam.startConversion(full = false)
        if (start != Ptp.OK) throw PtpException(start, "Starting the conversion")
        val deadline = System.currentTimeMillis() + CONVERSION_TIMEOUT_MS
        var result: Int? = null
        while (result == null) {
            if (System.currentTimeMillis() > deadline) {
                throw IOException("The camera didn't finish developing. Switch it off and on (take the battery out if it won't switch off) before trying again.")
            }
            Thread.sleep(POLL_MS)
            result = cam.objectHandles().firstOrNull { it !in before }
        }
        _state.value = DevelopState.Working("Downloading the picture…")
        val jpeg = cam.getObject(result)
        cam.deleteObject(result)
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) throw IOException("The camera sent something other than a JPEG.")
        AppLog.add("develop: ${recipe.cameraName} ${jpeg.size} bytes")
        return jpeg
    }

    /** Save the last developed picture in Pictures/Fuji Remote. */
    fun saveLast(): String? {
        val done = _state.value as? DevelopState.Done ?: return null
        val name = "${done.rafName.substringBeforeLast('.')}-${done.recipe.cameraName.replace(' ', '-')}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Fuji Remote")
            }
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            app.contentResolver.openOutputStream(uri)?.use { it.write(done.jpeg) } ?: return null
        } else {
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Fuji Remote").apply { mkdirs() }
            File(dir, name).writeBytes(done.jpeg)
        }
        AppLog.add("develop: saved $name")
        return name
    }

    /**
     * Let go of the USB connection (the next development uploads the RAF again). Done at once, not
     * in the background: a PTP session belongs to the camera, so a late CloseSession would close the
     * session another USB job has just opened. It's a few milliseconds over USB.
     */
    fun close() {
        val c = camera
        camera = null
        uploaded = null
        baseProfile = null
        if (c != null) runCatching { c.close() }
    }

    fun clear() {
        if (!busy) _state.value = DevelopState.Idle
    }

    private fun explain(t: Throwable): String = when {
        t is PtpException && t.code == Ptp.GENERAL_ERROR ->
            "The camera couldn't develop that file. It has to be a RAW (RAF) this camera shot."
        t is PtpException && t.code == Ptp.DEVICE_BUSY ->
            "The camera is busy and may be stuck. Switch it off and on (take the battery out if it won't), then try again."
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun size(uri: Uri): Long? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull() ?: runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()?.takeIf { it > 0 }

    private companion object {
        const val CONVERSION_TIMEOUT_MS = 40_000L
        const val POLL_MS = 500L
    }
}
