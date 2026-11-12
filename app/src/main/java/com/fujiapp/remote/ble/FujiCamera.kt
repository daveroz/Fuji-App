package com.fujiapp.remote.ble

import com.fujiapp.remote.model.CameraSnapshot
import com.fujiapp.remote.model.CameraVitals
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

class UnsupportedCameraException(message: String) : IOException(message)
class CameraBusyException(message: String) : IOException(message)
/** The camera ignored a request; a fresh connection usually fixes it. */
class NoAnswerException(message: String) : IOException(message)

/** A file the camera sent over Bluetooth. */
class DownloadedFile(val name: String, val data: ByteArray)

/** What the connected camera offers over Bluetooth. */
data class Capabilities(val backup: Boolean, val restore: Boolean, val vitals: Boolean)

/** The camera-level operations, on top of one [GattClient] connection. */
class FujiCamera(private val gatt: GattClient, private val log: (String) -> Unit) {


    /**
     * Register this phone with the camera (GeoShutter's tested X100VI sequence): read the status,
     * write this phone's identification number back, write the client name, then subscribe.
     *
     * The identification number: on the pairing connection it is the value the camera offers with
     * byte 3 set to 0x20 (XApp's marker). On later connections XApp writes the number it stored
     * then, whatever the camera reads back. In two field logs the camera offered a changing value
     * (`9a 8d 06`, `9c 8d 06`, …) and dropped the link when that was echoed, while writing the
     * stored `65 48 26 20` worked; so [storedId] is written when there is one, unless this
     * connection just paired. Returns the number written, for the caller to store.
     *
     * Must run on a bonded link, right after pairing for a new camera: the X100VI refuses a
     * registration that comes minutes after the bond was made.
     */
    suspend fun register(
        storedId: Long?,
        freshPairing: Boolean,
        locationRequests: Boolean,
        onWaitingForPairing: () -> Unit,
    ): Long {
        if (!gatt.has(FujiGatt.DEVICE_IDENTIFICATION_NUMBER)) {
            if (gatt.hasService(FujiGatt.SVC_LEGACY_PAIRING)) {
                throw UnsupportedCameraException(
                    "This camera uses the older Bluetooth pairing. Update its firmware to the latest version and try again."
                )
            }
            throw UnsupportedCameraException("This doesn't look like a supported Fujifilm camera.")
        }
        val status = readThroughPairing(FujiGatt.DEVICE_IDENTIFICATION_NUMBER, onWaitingForPairing)
        if (status.size != 4) throw GattException("Unexpected status from the camera: ${FujiProtocol.hex(status)}")
        val ack = if (storedId != null && !freshPairing) {
            log("writing the stored identification number")
            FujiProtocol.le32(storedId)
        } else {
            log(if (freshPairing) "new pairing: acknowledging the camera's number" else "no stored number yet: acknowledging the camera's")
            FujiProtocol.ackStatus(status)
        }
        gatt.write(FujiGatt.DEVICE_IDENTIFICATION_NUMBER, ack)
        gatt.write(FujiGatt.CONNECTED_DEVICE_NAME, FujiProtocol.clientName())

        val subscriptions = FujiGatt.REGISTRATION_SUBSCRIPTIONS +
            (if (locationRequests) listOf(FujiGatt.LOCATION_SYNC_STATE) else emptyList()) +
            FujiGatt.LIVE_SUBSCRIPTIONS
        for (c in subscriptions) {
            if (!gatt.has(c)) {
                log("no ${c.name} on this camera, skipped")
                continue
            }
            try {
                gatt.subscribe(c)
            } catch (e: GattException) {
                if (gatt.disconnected.value) throw e
                log("subscribe ${c.name} failed: ${e.message}")
            }
        }
        log("registered")
        return FujiProtocol.idValue(ack)
    }

    /** Start or stop receiving the camera's location requests (location sync turned on or off). */
    suspend fun setLocationRequests(on: Boolean) {
        if (!gatt.has(FujiGatt.LOCATION_SYNC_STATE)) return
        if (on) gatt.subscribe(FujiGatt.LOCATION_SYNC_STATE) else gatt.unsubscribe(FujiGatt.LOCATION_SYNC_STATE)
    }

    /**
     * Read through a pairing in progress: while the bond is still being confirmed the camera answers
     * with an authentication error, so keep the link and try again (as fuji-bridge does).
     */
    private suspend fun readThroughPairing(c: GattChar, onWaiting: () -> Unit): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return gatt.read(c)
            } catch (e: GattException) {
                if (!GattClient.isAuthError(e.status) || attempt >= 30) throw e
                if (attempt == 0) onWaiting()
                attempt++
                delay(2_000)
            }
        }
    }

    /** Read every value that describes the camera. Missing or unreadable ones are left out. */
    suspend fun readSnapshot(): CameraSnapshot {
        val values = HashMap<java.util.UUID, ByteArray>()
        for (c in FujiGatt.READABLE) {
            if (!gatt.has(c)) continue
            try {
                values[c.uuid] = gatt.read(c)
            } catch (e: GattException) {
                if (gatt.disconnected.value) throw e
            }
        }
        return CameraSnapshot(values)
    }

    /** Read one value again (after writing a setting). */
    suspend fun read(c: GattChar): ByteArray? = if (gatt.has(c)) gatt.read(c) else null

    // ---- camera settings, time, location and shutter ----

    /** The camera's SMARTPHONE LOCATION SYNC. setting (GeoShutter saw the X100VI switch it). */
    suspend fun setLocationSyncSetting(on: Boolean) =
        gatt.write(FujiGatt.LOCATION_SYNC_SETTING, FujiProtocol.le16(if (on) 1 else 0))

    /** Seconds between the camera's location requests, one of [FujiGatt.LOCATION_SYNC_INTERVALS]. */
    suspend fun setLocationSyncInterval(seconds: Int) {
        require(seconds in FujiGatt.LOCATION_SYNC_INTERVALS) { "unsupported interval $seconds" }
        gatt.write(FujiGatt.LOCATION_SYNC_CYCLE, FujiProtocol.le16(seconds))
    }

    /** The camera's CONNECT WHILE POWER OFF setting. */
    suspend fun setConnectWhilePowerOff(on: Boolean) =
        gatt.write(FujiGatt.REMOTE_BOOT_SETTING, FujiProtocol.le16(if (on) 1 else 0))

    /** Answer the camera's time request with the phone's clock and time zone. */
    suspend fun sendTime(now: java.time.Instant, zone: java.time.ZoneId) {
        if (gatt.has(FujiGatt.UTC_AND_TIMEZONE)) gatt.write(FujiGatt.UTC_AND_TIMEZONE, FujiProtocol.timePacket(now, zone))
    }

    /** Answer the camera's location request. */
    suspend fun sendLocation(packet: ByteArray) {
        if (gatt.has(FujiGatt.LOCATION_AND_SPEED)) gatt.write(FujiGatt.LOCATION_AND_SPEED, packet)
    }

    val hasShutter: Boolean get() = gatt.has(FujiGatt.SHOOTING_REQUEST)

    /** The Bluetooth shutter button: [SHUTTER_HALF] (focus), [SHUTTER_FULL] or [SHUTTER_RELEASE]. */
    suspend fun shutter(state: Int) = gatt.write(FujiGatt.SHOOTING_REQUEST, FujiProtocol.le16(state))

    val capabilities: Capabilities
        get() {
            val file = FILE_CORE.all { gatt.has(it) }
            return Capabilities(
                backup = file && gatt.has(FujiGatt.BACKUP_REQUEST) && gatt.has(FujiGatt.BACKUP_STATE),
                restore = file && gatt.has(FujiGatt.RESTORE_REQUEST) && gatt.has(FujiGatt.RESTORE_STATE) &&
                    gatt.has(FujiGatt.FILE_INFORMATION),
                vitals = file && gatt.has(FujiGatt.LOG_TRANSFER_REQUEST) && gatt.has(FujiGatt.LOG_TRANSFER_STATE),
            )
        }

    // ---- downloads ----

    /** The camera's settings backup (all settings, including the C1–C7 custom settings). */
    suspend fun downloadBackup(progress: (Int) -> Unit): DownloadedFile {
        if (!capabilities.backup) throw UnsupportedCameraException("This camera doesn't offer a settings backup over Bluetooth.")
        return download(FujiGatt.BACKUP_REQUEST, FujiGatt.BACKUP_STATE, FileTransfer.REQUEST_SETTINGS, true, "backup", progress)
    }

    /** The camera's vital data: shutter count, power-ons, operating time and more. */
    suspend fun readVitals(): CameraVitals {
        if (!capabilities.vitals) throw UnsupportedCameraException("This camera doesn't share its shutter count over Bluetooth.")
        val file = download(
            FujiGatt.LOG_TRANSFER_REQUEST, FujiGatt.LOG_TRANSFER_STATE, FileTransfer.REQUEST_VITAL_DATA, false, "shutter count",
        ) {}
        val json = String(file.data, Charsets.UTF_8)
        return try {
            CameraVitals.parse(json)
        } catch (e: Exception) {
            log("vital data not JSON: ${json.take(200)}")
            throw FileTransferException("The camera's usage data couldn't be read.")
        }
    }

    /**
     * Run one file transfer with its notifications on, and turn them off afterwards whatever
     * happens, as XApp does (`enableNotifyToBackup/Restore/LogTransfer(true … false)`). Left on,
     * the camera keeps the transfer session open: the next request gets "busy", and forcing a new
     * transfer on top of it made the camera drop the link.
     */
    private suspend fun <T> transferSession(requestState: GattChar, block: suspend () -> T): T {
        gatt.subscribe(FujiGatt.FILE_TRANSACTION_STATE)
        gatt.subscribe(requestState)
        try {
            return block()
        } finally {
            if (!gatt.disconnected.value) {
                withContext(NonCancellable) {
                    runCatching { gatt.unsubscribe(FujiGatt.FILE_TRANSACTION_STATE) }
                    runCatching { gatt.unsubscribe(requestState) }
                }
            }
        }
    }

    /**
     * Ask for a transfer and wait until the camera says it's ready. If it answers "busy" or not at
     * all, close whatever transfer it still has open (FILE_TRANSFER_RESULT = cancel, which the
     * camera ends with transaction state 5) and ask once more.
     */
    private suspend fun requestTransfer(request: GattChar, requestState: GattChar, value: Int, what: String) {
        for (attempt in 1..2) {
            gatt.drain(requestState)
            gatt.drain(FujiGatt.FILE_TRANSACTION_STATE)
            gatt.write(request, FujiProtocol.le16(value))
            val answer = gatt.awaitValue(requestState, 10_000)
            if (answer != null && FileTransfer.stateCode(answer) == FileTransfer.READY) return
            log("$what request: ${if (answer == null) "no answer" else "busy"} (attempt $attempt)")
            runCatching { gatt.write(FujiGatt.FILE_TRANSFER_RESULT, FujiProtocol.le16(FileTransfer.RESULT_CANCEL)) }
            gatt.awaitValue(FujiGatt.FILE_TRANSACTION_STATE, 3_000)
            if (attempt == 2) {
                throw if (answer == null) {
                    NoAnswerException("The camera didn't answer the $what request.")
                } else {
                    CameraBusyException("The camera is busy. Leave its menus or playback and try again.")
                }
            }
            delay(700)
        }
    }

    /** One file download, as XApp's `fileTransfer`; see [FileTransfer]. */
    private suspend fun download(
        request: GattChar,
        requestState: GattChar,
        requestValue: Int,
        isBackup: Boolean,
        what: String,
        progress: (Int) -> Unit,
    ): DownloadedFile = transferSession(requestState) {
        requestTransfer(request, requestState, requestValue, what)
        var result = FileTransfer.RESULT_FAIL
        try {
            gatt.write(FujiGatt.FILE_PARTIAL_SIZE, FujiProtocol.le32(FileTransfer.PARTIAL_SIZE.toLong()))
            gatt.write(FujiGatt.FILE_TRANSFER_INDEX, FujiProtocol.le32(0))

            val assembler = FileAssembler()
            var file: DownloadedFile? = null
            while (file == null) {
                val state = gatt.awaitValue(FujiGatt.FILE_TRANSACTION_STATE, 20_000)
                    ?: throw NoAnswerException("The camera stopped sending the $what.")
                when (val code = FileTransfer.stateCode(state)) {
                    FileTransfer.STATE_START, FileTransfer.STATE_TRANSFERRING -> {
                        assembler.add(FileTransfer.parseChunk(gatt.read(FujiGatt.FILE_PARTIAL_DATA)))
                        progress(assembler.received)
                    }
                    FileTransfer.STATE_FILE_FINISHED -> {
                        assembler.verify()
                        val info = if (gatt.has(FujiGatt.FILE_INFORMATION)) {
                            FileTransfer.parseFileInfo(gatt.read(FujiGatt.FILE_INFORMATION))
                        } else {
                            null
                        }
                        val data = assembler.finish(info)
                        result = -1
                        if (isBackup) gatt.write(FujiGatt.FILE_TRANSFER_RESULT, FujiProtocol.le16(FileTransfer.RESULT_FINISH))
                        gatt.write(FujiGatt.FILE_TRANSFER_RESULT, FujiProtocol.le16(FileTransfer.RESULT_SUCCESS))
                        // The camera then closes the file (state 4) before the notifications go off.
                        gatt.awaitValue(FujiGatt.FILE_TRANSACTION_STATE, 3_000)
                        log("$what received: ${info?.name ?: "?"}, ${data.size} bytes")
                        file = DownloadedFile(info?.name?.ifBlank { null } ?: FileTransfer.BACKUP_FILE_NAME, data)
                    }
                    FileTransfer.STATE_TRANSFER_FINISHED -> throw FileTransferException("The camera ended the transfer.")
                    FileTransfer.STATE_ERROR -> throw FileTransferException("The camera reported a transfer error.")
                    else -> throw FileTransferException("The camera ended the transfer (state $code).")
                }
            }
            checkNotNull(file)
        } catch (e: Throwable) {
            // Tell the camera to drop the transfer so it leaves its "sending" state.
            if (result >= 0 && !gatt.disconnected.value) {
                withContext(NonCancellable) {
                    runCatching { gatt.write(FujiGatt.FILE_TRANSFER_RESULT, FujiProtocol.le16(result)) }
                }
            }
            throw e
        }
    }

    // ---- restore ----

    /**
     * Send a settings backup back to the camera (XApp's restore; see [FileTransfer]). The camera
     * replaces all its settings, including C1–C7, with the ones in [data]. [progress] gets
     * (bytes sent, total bytes).
     */
    suspend fun restore(data: ByteArray, progress: (Int, Int) -> Unit) {
        if (!capabilities.restore) throw UnsupportedCameraException("This camera doesn't accept a restore over Bluetooth.")
        val chunks = FileTransfer.restoreChunks(data)
        transferSession(FujiGatt.RESTORE_STATE) {
            requestTransfer(FujiGatt.RESTORE_REQUEST, FujiGatt.RESTORE_STATE, FileTransfer.REQUEST_SETTINGS, "restore")
            var result = FileTransfer.RESULT_CANCEL
            try {
                gatt.write(FujiGatt.FILE_PARTIAL_SIZE, FujiProtocol.le32(FileTransfer.PARTIAL_SIZE.toLong()))
                gatt.write(FujiGatt.FILE_TRANSFER_INDEX, FujiProtocol.le32(0))
                gatt.drain(FujiGatt.FILE_TRANSACTION_STATE)
                gatt.write(FujiGatt.FILE_INFORMATION, FileTransfer.encodeFileInfo(FileTransfer.BACKUP_FILE_NAME, data.size.toLong()))
                val start = gatt.awaitValue(FujiGatt.FILE_TRANSACTION_STATE, 10_000)
                    ?: throw NoAnswerException("The camera didn't start the restore.")
                if (FileTransfer.stateCode(start) != FileTransfer.STATE_START) {
                    throw FileTransferException("The camera refused the restore (state ${FileTransfer.stateCode(start)}).")
                }

                var finished = false
                for ((i, chunk) in chunks.withIndex()) {
                    gatt.write(FujiGatt.FILE_PARTIAL_DATA, chunk)
                    progress(minOf(data.size, (i + 1) * FileTransfer.PARTIAL_SIZE), data.size)
                    val state = gatt.awaitValue(FujiGatt.FILE_TRANSACTION_STATE, 10_000)
                        ?: throw NoAnswerException("The camera stopped answering during the restore.")
                    when (val code = FileTransfer.stateCode(state)) {
                        FileTransfer.STATE_TRANSFERRING -> continue
                        FileTransfer.STATE_FILE_FINISHED -> {
                            result = -1
                            // The camera has the whole file. It may restart to apply it, so a failed
                            // final write doesn't make the restore fail.
                            runCatching {
                                gatt.write(FujiGatt.FILE_TRANSFER_RESULT, FujiProtocol.le16(FileTransfer.RESULT_FINISH))
                            }
                            log("restore accepted after ${i + 1} of ${chunks.size} chunks")
                            finished = true
                        }
                        FileTransfer.STATE_ERROR -> {
                            result = FileTransfer.RESULT_FAIL
                            throw FileTransferException("The camera rejected the backup. Is it from the same camera model?")
                        }
                        else -> throw FileTransferException("The camera ended the restore (state $code).")
                    }
                    if (finished) break
                }
                if (!finished) {
                    // Every chunk was acknowledged with "transferring": XApp treats this as done too.
                    result = -1
                    log("restore: all ${chunks.size} chunks sent")
                }
            } catch (e: Throwable) {
                if (result >= 0 && !gatt.disconnected.value) {
                    withContext(NonCancellable) {
                        runCatching { gatt.write(FujiGatt.FILE_TRANSFER_RESULT, FujiProtocol.le16(result)) }
                    }
                }
                throw e
            }
        }
    }

    companion object {
        const val SHUTTER_RELEASE = 0
        const val SHUTTER_HALF = 1
        const val SHUTTER_FULL = 2

        private val FILE_CORE = listOf(
            FujiGatt.FILE_TRANSACTION_STATE, FujiGatt.FILE_PARTIAL_SIZE, FujiGatt.FILE_TRANSFER_INDEX,
            FujiGatt.FILE_PARTIAL_DATA, FujiGatt.FILE_TRANSFER_RESULT,
        )
    }
}
