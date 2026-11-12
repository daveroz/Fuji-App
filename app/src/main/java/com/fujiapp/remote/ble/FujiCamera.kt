package com.fujiapp.remote.ble

import com.fujiapp.remote.model.CameraInfo
import kotlinx.coroutines.delay
import java.io.IOException

class UnsupportedCameraException(message: String) : IOException(message)
class CameraBusyException(message: String) : IOException(message)

/** A file the camera sent over Bluetooth. */
class DownloadedFile(val name: String, val data: ByteArray)

/** The camera-level operations, on top of one [GattClient] connection. */
class FujiCamera(private val gatt: GattClient, private val log: (String) -> Unit) {

    /**
     * Register this phone with the camera, as XApp does on every connection (GeoShutter's tested
     * X100VI sequence): read the status, acknowledge it, write the client name, then subscribe.
     *
     * Must run on a bonded link, right after pairing for a new camera: the X100VI refuses a
     * registration that comes minutes after the bond was made.
     */
    suspend fun register(onWaitingForPairing: () -> Unit) {
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
        gatt.write(FujiGatt.DEVICE_IDENTIFICATION_NUMBER, FujiProtocol.ackStatus(status))
        gatt.write(FujiGatt.CONNECTED_DEVICE_NAME, FujiProtocol.clientName())
        for (c in FujiGatt.REGISTRATION_SUBSCRIPTIONS) {
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

    /** Read everything the camera reports about itself. Missing characteristics are left null. */
    suspend fun readInfo(): CameraInfo {
        suspend fun bytes(c: GattChar): ByteArray? =
            if (!gatt.has(c)) null else try {
                gatt.read(c)
            } catch (e: GattException) {
                if (gatt.disconnected.value) throw e
                null
            }
        suspend fun text(c: GattChar): String? = FujiProtocol.string(bytes(c))

        return CameraInfo(
            model = text(FujiGatt.DEVICE_NAME),
            cameraName = text(FujiGatt.CAMERA_NAME),
            manufacturer = text(FujiGatt.MANUFACTURER),
            modelNumber = text(FujiGatt.MODEL_NUMBER),
            serialNumber = text(FujiGatt.SERIAL_NUMBER) ?: text(FujiGatt.CAMERA_SERIAL_NUMBER),
            firmware = text(FujiGatt.FIRMWARE_REVISION),
            hardwareRevision = text(FujiGatt.HARDWARE_REVISION),
            softwareRevision = text(FujiGatt.SOFTWARE_REVISION),
            bleProtocolVersion = FujiProtocol.u16(bytes(FujiGatt.BLE_PROTOCOL_VERSION)),
            macAddress = text(FujiGatt.CAMERA_MAC_ADDRESS),
            lensName = text(FujiGatt.LENS_PRODUCT_NAME),
            lensFirmware = text(FujiGatt.LENS_FIRMWARE_VERSION),
            power = FujiProtocol.powerState(bytes(FujiGatt.POWER_KEY_STATE)),
            connectWhilePowerOff = FujiProtocol.u16(bytes(FujiGatt.REMOTE_BOOT_SETTING))?.let { it != 0 },
            pairedPhones = FujiProtocol.u8(bytes(FujiGatt.PAIRING_SMART_DEVICE_NUM)),
        )
    }

    val supportsBackup: Boolean
        get() = BACKUP_CHARACTERISTICS.all { gatt.has(it) }

    /**
     * Download the camera's settings backup (all settings, including the C1–C7 custom settings),
     * following XApp's sequence; see [FileTransfer]. [progress] gets the bytes received so far.
     */
    suspend fun downloadBackup(progress: (Int) -> Unit): DownloadedFile {
        if (!supportsBackup) throw UnsupportedCameraException("This camera doesn't offer a settings backup over Bluetooth.")
        gatt.subscribe(FujiGatt.FILE_TRANSACTION_STATE)
        gatt.subscribe(FujiGatt.BACKUP_STATE)
        gatt.drain(FujiGatt.FILE_TRANSACTION_STATE)
        gatt.drain(FujiGatt.BACKUP_STATE)
        try {
            gatt.write(FujiGatt.BACKUP_REQUEST, FujiProtocol.le16(FileTransfer.BACKUP_KIND_SETTINGS))
            val ready = gatt.awaitValue(FujiGatt.BACKUP_STATE, 10_000)
                ?: throw FileTransferException("The camera didn't answer the backup request.")
            if (FileTransfer.stateCode(ready) != 1) {
                throw CameraBusyException("The camera is busy. Leave its menus or playback and try again.")
            }
            gatt.write(FujiGatt.FILE_PARTIAL_SIZE, FujiProtocol.le32(FileTransfer.PARTIAL_SIZE))
            gatt.write(FujiGatt.FILE_TRANSFER_INDEX, FujiProtocol.le32(0))

            val assembler = FileAssembler()
            while (true) {
                val state = gatt.awaitValue(FujiGatt.FILE_TRANSACTION_STATE, 20_000)
                    ?: throw FileTransferException("The camera stopped sending the backup.")
                when (val code = FileTransfer.stateCode(state)) {
                    FileTransfer.STATE_FIRST_CHUNK, FileTransfer.STATE_NEXT_CHUNK -> {
                        assembler.add(FileTransfer.parseChunk(gatt.read(FujiGatt.FILE_PARTIAL_DATA)))
                        progress(assembler.received)
                    }
                    FileTransfer.STATE_COMPLETE -> {
                        val info = if (gatt.has(FujiGatt.FILE_INFORMATION)) {
                            FileTransfer.parseFileInfo(gatt.read(FujiGatt.FILE_INFORMATION))
                        } else {
                            null
                        }
                        val data = assembler.finish(info)
                        gatt.write(FujiGatt.FILE_TRANSFER_RESULT, FujiProtocol.le16(FileTransfer.RESULT_FINISHED))
                        log("backup received: ${info?.name ?: "?"}, ${data.size} bytes")
                        return DownloadedFile(info?.name?.ifBlank { null } ?: "backup.dat", data)
                    }
                    else -> throw FileTransferException("The camera ended the transfer (state $code).")
                }
            }
        } catch (e: Throwable) {
            // Tell the camera to drop the transfer so it leaves its "sending" state.
            if (!gatt.disconnected.value) {
                runCatching { gatt.write(FujiGatt.FILE_TRANSFER_RESULT, FujiProtocol.le16(FileTransfer.RESULT_ABORT)) }
            }
            throw e
        }
    }

    private companion object {
        val BACKUP_CHARACTERISTICS = listOf(
            FujiGatt.BACKUP_REQUEST, FujiGatt.BACKUP_STATE, FujiGatt.FILE_TRANSACTION_STATE,
            FujiGatt.FILE_PARTIAL_SIZE, FujiGatt.FILE_TRANSFER_INDEX, FujiGatt.FILE_PARTIAL_DATA,
            FujiGatt.FILE_TRANSFER_RESULT,
        )
    }
}
