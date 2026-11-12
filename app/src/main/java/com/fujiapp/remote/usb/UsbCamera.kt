package com.fujiapp.remote.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.fujiapp.remote.recipe.BankCodec
import com.fujiapp.remote.recipe.DynamicRange
import com.fujiapp.remote.recipe.Recipe
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume

/**
 * The camera over a USB cable, for what Bluetooth can't do: reading and writing the custom setting
 * slots one setting at a time. The camera must be in USB RAW CONV./BACKUP RESTORE mode; while it
 * is, its own menus are unavailable. All calls block: run them off the main thread.
 */
class UsbCamera private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val log: (String) -> Unit,
) : Closeable {

    private var transaction = 0
    private var sessionOpen = false

    /** A transaction's response, and the data the camera sent with it. */
    class Reply(val code: Int, val params: List<Int>, val data: ByteArray)

    /** One full PTP transaction: command, optional data out, optional data in, response. */
    @Synchronized
    fun transact(code: Int, params: List<Int> = emptyList(), dataOut: ByteArray? = null, timeoutMs: Int = TIMEOUT_MS): Reply {
        val tid = if (code == Ptp.OPEN_SESSION) 0 else ++transaction
        send(Ptp.command(code, tid, params), timeoutMs)
        if (dataOut != null) send(Ptp.data(code, tid, dataOut), timeoutMs)
        var data = ByteArray(0)
        while (true) {
            val c = receive(timeoutMs)
            when (c.type) {
                Ptp.TYPE_DATA -> data = c.payload
                Ptp.TYPE_RESPONSE -> return Reply(c.code, c.params, data)
                else -> log("usb: ignored container type ${c.type}")
            }
        }
    }

    private fun send(bytes: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(CHUNK, bytes.size - offset)
            val sent = connection.bulkTransfer(bulkOut, bytes, offset, n, timeoutMs)
            if (sent < 0) throw IOException("USB write failed")
            offset += sent
        }
        // A transfer that ends exactly on a packet boundary needs an empty packet to end it.
        if (bytes.isNotEmpty() && bytes.size % bulkOut.maxPacketSize == 0) connection.bulkTransfer(bulkOut, ByteArray(0), 0, timeoutMs)
    }

    private fun receive(timeoutMs: Int): Ptp.Container {
        val buffer = ByteArray(CHUNK)
        var n = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
        if (n == 0) n = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs) // a stray empty packet
        if (n < Ptp.HEADER) throw IOException(if (n < 0) "The camera didn't answer over USB" else "Short USB packet ($n bytes)")
        val total = Ptp.length(buffer)
        val out = ByteArrayOutputStream(maxOf(total, n))
        out.write(buffer, 0, n)
        while (out.size() < total) {
            val m = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
            if (m < 0) throw IOException("USB read stopped after ${out.size()} of $total bytes")
            out.write(buffer, 0, m)
        }
        return Ptp.parse(out.toByteArray())
    }

    fun openSession() {
        var r = transact(Ptp.OPEN_SESSION, listOf(SESSION_ID))
        if (r.code == Ptp.SESSION_ALREADY_OPEN) {
            log("usb: session already open, reopening")
            runCatching { transact(Ptp.CLOSE_SESSION) }
            transaction = 0
            r = transact(Ptp.OPEN_SESSION, listOf(SESSION_ID))
        }
        if (r.code != Ptp.OK && r.code != Ptp.SESSION_ALREADY_OPEN) throw PtpException(r.code, "Open session")
        sessionOpen = true
        log("usb: session open")
    }

    fun getU16(property: Int): Int {
        val r = transact(Ptp.GET_DEVICE_PROP_VALUE, listOf(property))
        if (r.code != Ptp.OK) throw PtpException(r.code, "Read 0x%04X".format(property))
        return Ptp.readU16(r.data)
    }

    fun getString(property: Int): String {
        val r = transact(Ptp.GET_DEVICE_PROP_VALUE, listOf(property))
        if (r.code != Ptp.OK) throw PtpException(r.code, "Read 0x%04X".format(property))
        return Ptp.readString(r.data)
    }

    /** Write a 16-bit value; returns the camera's response code (OK, or why it refused). */
    fun setU16(property: Int, value: Int): Int =
        transact(Ptp.SET_DEVICE_PROP_VALUE, listOf(property), Ptp.u16(value)).code

    fun setString(property: Int, value: String): Int =
        transact(Ptp.SET_DEVICE_PROP_VALUE, listOf(property), Ptp.string(value)).code

    /** The camera's USB mode (6 = USB RAW CONV./BACKUP RESTORE), or null if it won't say. */
    fun usbMode(): Int? = runCatching { getU16(BankCodec.USB_MODE) }.getOrNull()

    private fun select(slot: Int) {
        val code = setU16(BankCodec.SELECTOR, slot)
        if (code != Ptp.OK) throw PtpException(code, "Select C$slot")
        Thread.sleep(SELECT_SETTLE_MS)
    }

    /** Read one slot: its name and every setting it answers. */
    fun readSlot(slot: Int): BankCodec.Slot {
        select(slot)
        val name = runCatching { getString(BankCodec.NAME) }.getOrDefault("")
        val values = LinkedHashMap<Int, Int>()
        for (p in BankCodec.SLOT_PROPERTIES) {
            val r = transact(Ptp.GET_DEVICE_PROP_VALUE, listOf(p))
            if (r.code == Ptp.OK && r.data.size >= 2) values[p] = Ptp.readU16(r.data)
        }
        return BankCodec.Slot(slot, name, values)
    }

    /** What happened when a recipe was written to a slot. */
    data class WriteReport(
        val slot: Int,
        val before: BankCodec.Slot,
        val after: BankCodec.Slot,
        val written: List<BankCodec.Write>,
        val refused: List<Pair<BankCodec.Write, Int>>,
        val mismatches: List<String>,
        val nameWritten: Boolean,
    ) {
        val ok: Boolean get() = refused.isEmpty() && mismatches.isEmpty()
    }

    /**
     * Write [recipe] into [slot]: read the slot first (the snapshot to go back to), write only what
     * changes in the order the camera needs, carry on past refusals, write the name last, then read
     * everything back. The camera's own slot selection is put back afterwards.
     */
    fun writeRecipe(slot: Int, recipe: Recipe): WriteReport {
        val original = runCatching { getU16(BankCodec.SELECTOR) }.getOrNull()
        try {
            val before = readSlot(slot)
            if (before.values[BankCodec.FILM_SIM] == null) throw IOException("C$slot didn't answer its settings; nothing was written.")
            log("usb: C$slot before: ${before.name} ${before.values.entries.joinToString { "%04X=%d".format(it.key, it.value) }}")
            val plan = BankCodec.plan(before.values, recipe)
            return apply(slot, before, plan, recipe.cameraName) { after -> BankCodec.mismatches(after.values, recipe) }
        } finally {
            if (original != null && original != slot) runCatching { setU16(BankCodec.SELECTOR, original) }
        }
    }

    /** Put a slot back the way [snapshot] found it (settings and name). */
    fun restoreSlot(snapshot: BankCodec.Slot): WriteReport {
        val original = runCatching { getU16(BankCodec.SELECTOR) }.getOrNull()
        try {
            val now = readSlot(snapshot.number)
            val plan = BankCodec.undo(snapshot.values, now.values)
            return apply(snapshot.number, now, plan, snapshot.name) { after ->
                BankCodec.SLOT_PROPERTIES.filter { p ->
                    val want = snapshot.values[p] ?: return@filter false
                    val got = after.values[p]
                    if (p == BankCodec.GRAIN) com.fujiapp.remote.recipe.Grain.of(want) != got?.let { com.fujiapp.remote.recipe.Grain.of(it) } else got != want
                }.map { BankCodec.NAMES[it] ?: "0x%04X".format(it) }
            }
        } finally {
            if (original != null && original != snapshot.number) runCatching { setU16(BankCodec.SELECTOR, original) }
        }
    }

    private fun apply(
        slot: Int,
        before: BankCodec.Slot,
        plan: List<BankCodec.Write>,
        name: String,
        verify: (BankCodec.Slot) -> List<String>,
    ): WriteReport {
        val written = mutableListOf<BankCodec.Write>()
        val refused = mutableListOf<Pair<BankCodec.Write, Int>>()
        for (w in plan) {
            val code = setU16(w.property, w.value)
            if (code == Ptp.OK) written += w else refused += w to code
            log("usb: C$slot ${w.label} = ${w.value} -> ${Ptp.responseName(code)}")
        }
        var nameWritten = false
        if (name != before.name) {
            val code = setString(BankCodec.NAME, name)
            nameWritten = code == Ptp.OK
            log("usb: C$slot name \"$name\" -> ${Ptp.responseName(code)}")
            if (!nameWritten) refused += BankCodec.Write(BankCodec.NAME, 0) to code
        }
        val after = readSlot(slot)
        val mismatches = verify(after)
        log("usb: C$slot after: ${after.name}; refused ${refused.size}; mismatches $mismatches")
        return WriteReport(slot, before, after, written, refused, mismatches, nameWritten)
    }

    /**
     * Download the camera's settings backup (the same file a Bluetooth backup gets, unpacked).
     * Reported to work in this mode on several bodies; not yet confirmed on the X100VI.
     */
    fun downloadBackup(): ByteArray {
        val info = transact(Ptp.GET_OBJECT_INFO, listOf(0))
        if (info.code != Ptp.OK) throw PtpException(info.code, "Backup info")
        val r = transact(Ptp.GET_OBJECT, listOf(0), timeoutMs = BACKUP_TIMEOUT_MS)
        if (r.code != Ptp.OK) throw PtpException(r.code, "Backup")
        log("usb: backup ${r.data.size} bytes")
        return r.data
    }

    // ---- RAW conversion: the camera develops a RAF with a recipe (X RAW Studio's method) ----

    /** Send a RAF into the camera's conversion buffer, streamed from [input] (it can be 80 MB). */
    fun uploadRaf(size: Long, input: InputStream, progress: (Long) -> Unit) {
        require(size in 1..Int.MAX_VALUE.toLong() - Ptp.HEADER) { "That file is too large" }
        val info = transact(SEND_OBJECT_INFO, listOf(0, 0, 0), rafInfo(size))
        if (info.code != Ptp.OK) throw PtpException(info.code, "Preparing the upload")
        val sent = transactStream(SEND_OBJECT, size, input, progress)
        if (sent.code != Ptp.OK) throw PtpException(sent.code, "Uploading the RAW")
        log("usb: RAF uploaded ($size bytes)")
    }

    /** ObjectInfo for the upload: format 0xF802 (RAF), the size, the name FUP_FILE.dat, everything else zero. */
    private fun rafInfo(size: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putInt(0).putShort(0xF802.toShort()).putShort(0).putInt(size.toInt())
        b.putShort(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
        b.putInt(0).putShort(0).putInt(0).putInt(0)
        val head = b.array().copyOf(b.position())
        return head + Ptp.string("FUP_FILE.dat") + byteArrayOf(0, 0, 0)
    }

    /**
     * A transaction whose data phase streams [size] bytes from [input]. Every USB transfer but the
     * last must be a whole number of packets (a short one ends the data phase), so the 12-byte
     * header goes out with the first data, in full 16 KB buffers.
     */
    @Synchronized
    private fun transactStream(code: Int, size: Long, input: InputStream, progress: (Long) -> Unit): Reply {
        val tid = ++transaction
        send(Ptp.command(code, tid), TIMEOUT_MS)
        val buffer = ByteArray(CHUNK)
        val header = java.nio.ByteBuffer.wrap(buffer, 0, Ptp.HEADER).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putInt((Ptp.HEADER + size).toInt()).putShort(Ptp.TYPE_DATA.toShort()).putShort(code.toShort()).putInt(tid)
        var fill = Ptp.HEADER
        var sent = 0L
        var total = 0L
        while (true) {
            while (fill < buffer.size && sent < size) {
                val n = input.read(buffer, fill, minOf(buffer.size - fill.toLong(), size - sent).toInt())
                if (n < 0) throw IOException("The file ended early")
                fill += n
                sent += n
            }
            if (fill == 0) break
            var off = 0
            while (off < fill) {
                val w = connection.bulkTransfer(bulkOut, buffer, off, fill - off, UPLOAD_TIMEOUT_MS)
                if (w < 0) throw IOException("USB upload stopped at $sent of $size bytes")
                off += w
            }
            total += fill
            progress(sent)
            fill = 0
            if (sent >= size) break
        }
        if (total % bulkOut.maxPacketSize == 0L) connection.bulkTransfer(bulkOut, ByteArray(0), 0, TIMEOUT_MS)
        while (true) {
            val c = receive(TIMEOUT_MS)
            if (c.type == Ptp.TYPE_RESPONSE) return Reply(c.code, c.params, c.payload)
        }
    }

    /** The conversion profile for the RAF in the buffer (only readable after an upload). */
    fun conversionProfile(): ByteArray {
        val r = transact(Ptp.GET_DEVICE_PROP_VALUE, listOf(CONVERSION_PROFILE))
        if (r.code != Ptp.OK) throw PtpException(r.code, "Reading the conversion profile")
        return r.data
    }

    fun setConversionProfile(profile: ByteArray): Int = transact(Ptp.SET_DEVICE_PROP_VALUE, listOf(CONVERSION_PROFILE), profile).code

    /** Start developing: [full] resolution, or a reduced-size JPEG (half the linear size). */
    fun startConversion(full: Boolean): Int = setU16(START_CONVERSION, if (full) 1 else 0)

    fun objectHandles(): List<Int> {
        val r = transact(GET_OBJECT_HANDLES, listOf(-1, 0, 0))
        if (r.code != Ptp.OK || r.data.size < 4) return emptyList()
        val b = java.nio.ByteBuffer.wrap(r.data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n = b.int
        return (0 until n).mapNotNull { if (b.remaining() >= 4) b.int else null }
    }

    fun getObject(handle: Int): ByteArray {
        val r = transact(Ptp.GET_OBJECT, listOf(handle), timeoutMs = BACKUP_TIMEOUT_MS)
        if (r.code != Ptp.OK) throw PtpException(r.code, "Downloading the developed picture")
        return r.data
    }

    fun deleteObject(handle: Int): Int = transact(DELETE_OBJECT, listOf(handle, 0)).code

    override fun close() {
        if (sessionOpen) runCatching { transact(Ptp.CLOSE_SESSION) }
        sessionOpen = false
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
        log("usb: closed")
    }

    companion object {
        const val FUJIFILM_VENDOR = 0x04CB
        private const val SESSION_ID = 1
        private const val CHUNK = 16 * 1024
        private const val TIMEOUT_MS = 5_000
        private const val BACKUP_TIMEOUT_MS = 20_000
        private const val SELECT_SETTLE_MS = 60L
        private const val ACTION_PERMISSION = "com.fujiapp.remote.USB_PERMISSION"
        private const val UPLOAD_TIMEOUT_MS = 15_000
        private const val SEND_OBJECT_INFO = 0x900C
        private const val SEND_OBJECT = 0x900D
        private const val GET_OBJECT_HANDLES = 0x1007
        private const val DELETE_OBJECT = 0x100B
        private const val CONVERSION_PROFILE = 0xD185
        private const val START_CONVERSION = 0xD183

        /** A Fujifilm camera plugged into the phone, if there is one. */
        fun find(manager: UsbManager): UsbDevice? = manager.deviceList.values.firstOrNull { it.vendorId == FUJIFILM_VENDOR }

        /** Ask the user to let the app use [device]; true once allowed. */
        suspend fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice): Boolean {
            if (manager.hasPermission(device)) return true
            val app = context.applicationContext
            return suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (intent.action != ACTION_PERMISSION) return
                        runCatching { app.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(manager.hasPermission(device))
                    }
                }
                ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
                // The system fills in the result, so the intent must be mutable; explicit to this app.
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val intent = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
                manager.requestPermission(device, intent)
            }
        }

        /** Open [device] (permission already granted) and start a PTP session. */
        fun open(manager: UsbManager, device: UsbDevice, log: (String) -> Unit): UsbCamera {
            var chosen: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) input = ep else output = ep
                }
                if (input != null && output != null) {
                    chosen = Triple(intf, input, output)
                    if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) break
                }
            }
            val (intf, input, output) = chosen ?: throw IOException("The camera's USB connection has no data channel. Set its USB mode to USB RAW CONV./BACKUP RESTORE.")
            val connection = manager.openDevice(device) ?: throw IOException("Couldn't open the camera's USB connection.")
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                throw IOException("Another app is using the camera's USB connection.")
            }
            log("usb: opened ${device.productName ?: "camera"} (0x%04X:0x%04X)".format(device.vendorId, device.productId))
            val camera = UsbCamera(connection, intf, input, output, log)
            try {
                camera.openSession()
            } catch (t: Throwable) {
                camera.close()
                throw t
            }
            return camera
        }

        /** Why the camera refused [write], in words. */
        fun refusalReason(write: BankCodec.Write, code: Int): String = when {
            write.property == BankCodec.DYNAMIC_RANGE && code == Ptp.INVALID_VALUE -> {
                val dr = DynamicRange.of(write.value)
                if (dr != null && dr.minIso > 0) "${dr.label} needs ISO ${dr.minIso} or higher in this slot (or ISO Auto)" else "Dynamic range refused"
            }
            write.property == BankCodec.DR_PRIORITY && code == Ptp.INVALID_VALUE -> "D-range priority can't be set while the slot is at base ISO"
            write.property == BankCodec.NAME -> "The name was refused (${Ptp.responseName(code)})"
            code == Ptp.PROP_NOT_SUPPORTED -> "${write.label}: not on this camera"
            else -> "${write.label}: ${Ptp.responseName(code)}"
        }
    }
}
