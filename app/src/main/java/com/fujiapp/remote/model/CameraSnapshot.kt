package com.fujiapp.remote.model

import com.fujiapp.remote.ble.FujiGatt
import com.fujiapp.remote.ble.FujiProtocol
import com.fujiapp.remote.ble.GattChar
import java.util.UUID

/**
 * Everything the camera has told us: the raw value of each characteristic, by UUID, kept up to
 * date as notifications arrive. [info] and [sections] turn it into something readable.
 */
class CameraSnapshot(val values: Map<UUID, ByteArray> = emptyMap()) {

    operator fun get(c: GattChar): ByteArray? = values[c.uuid]

    fun with(c: GattChar, value: ByteArray): CameraSnapshot = CameraSnapshot(values + (c.uuid to value))

    private fun text(c: GattChar): String? = printable(this[c])
    private fun u16(c: GattChar): Int? = FujiProtocol.u16(this[c])

    /** The camera's identity, as used for headers and backups. */
    val info: CameraInfo
        get() = CameraInfo(
            model = text(FujiGatt.DEVICE_NAME),
            cameraName = text(FujiGatt.CAMERA_NAME),
            manufacturer = text(FujiGatt.MANUFACTURER),
            modelNumber = text(FujiGatt.MODEL_NUMBER),
            serialNumber = text(FujiGatt.SERIAL_NUMBER) ?: text(FujiGatt.CAMERA_SERIAL_NUMBER),
            firmware = text(FujiGatt.FIRMWARE_REVISION),
            hardwareRevision = text(FujiGatt.HARDWARE_REVISION),
            softwareRevision = text(FujiGatt.SOFTWARE_REVISION),
            bleProtocolVersion = u16(FujiGatt.BLE_PROTOCOL_VERSION),
            macAddress = text(FujiGatt.CAMERA_MAC_ADDRESS),
            lensName = text(FujiGatt.LENS_PRODUCT_NAME),
            lensFirmware = text(FujiGatt.LENS_FIRMWARE_VERSION),
            power = FujiProtocol.powerState(this[FujiGatt.POWER_KEY_STATE]),
            connectWhilePowerOff = u16(FujiGatt.REMOTE_BOOT_SETTING)?.let { it != 0 },
            pairedPhones = FujiProtocol.u8(this[FujiGatt.PAIRING_SMART_DEVICE_NUM]),
        )

    val locationSyncOn: Boolean? get() = u16(FujiGatt.LOCATION_SYNC_SETTING)?.let { it != 0 }
    val locationSyncInterval: Int? get() = u16(FujiGatt.LOCATION_SYNC_CYCLE)
    val vitalDataChanged: Boolean get() = u16(FujiGatt.CAMERA_VITAL_STATE) == 1

    /** Every value, decoded and grouped for the status screen. Missing values are left out. */
    fun sections(): List<CameraInfo.Section> {
        val i = info
        fun rows(vararg pairs: Pair<String, String?>) =
            pairs.mapNotNull { (label, value) -> value?.ifBlank { null }?.let { CameraInfo.Row(label, it) } }

        val logging = FujiProtocol.loggingItems(this[FujiGatt.LOGGING_SETTING])
        return listOf(
            CameraInfo.Section(
                "State",
                rows(
                    "Power" to i.power?.label,
                    "Wi-Fi" to FujiProtocol.apState(this[FujiGatt.AP_STATE]),
                    "Transfer" to FujiProtocol.transferState(this[FujiGatt.TRANSFER_STATE]),
                    "Busy because" to FujiProtocol.stateErrorDetails(this[FujiGatt.STATE_ERROR_DETAILS]),
                    "Usage data" to this[FujiGatt.CAMERA_VITAL_STATE]?.let { if (vitalDataChanged) "Changed since last read" else "Up to date" },
                    "Card" to u16(FujiGatt.CARD_STATE)?.let { "Code $it" },
                    "Firmware update" to u16(FujiGatt.FWUPDATE_STATE)?.let { if (it == 0) "Idle" else "Code $it" },
                ),
            ),
            CameraInfo.Section(
                "Camera",
                rows(
                    "Model" to i.model,
                    "Name" to i.cameraName,
                    "Serial number" to i.serialNumber,
                    "Firmware" to i.firmware,
                    "Model code" to i.modelNumber,
                    "Manufacturer" to i.manufacturer,
                ),
            ),
            CameraInfo.Section(
                "Connection",
                rows(
                    "Paired phones" to i.pairedPhones?.toString(),
                    "Connect while off" to FujiProtocol.onOff(this[FujiGatt.REMOTE_BOOT_SETTING]),
                    "Location sync" to FujiProtocol.onOff(this[FujiGatt.LOCATION_SYNC_SETTING]),
                    "Location interval" to locationSyncInterval?.let { "$it s" },
                    "Transfer after shooting" to FujiProtocol.onOff(this[FujiGatt.RESERVATION_AFTER_SHOOTING]),
                    "Image transfer" to FujiProtocol.onOff(this[FujiGatt.IMAGE_TRANSFER_SETTING]),
                    "Resize for phone" to FujiProtocol.onOff(this[FujiGatt.IMAGE_RESIZE_SETTING]),
                    "Activity log" to logging?.let { "${it.size} of ${FujiProtocol.LOGGING_ITEMS.size} items" },
                ),
            ),
            CameraInfo.Section(
                "Lens",
                rows(
                    "Lens" to i.lensName,
                    "Lens firmware" to i.lensFirmware?.takeIf { it != "00.00" },
                    "Lens serial" to text(FujiGatt.LENS_SERIAL_NUMBER),
                ),
            ),
            CameraInfo.Section(
                "Technical",
                rows(
                    "Hardware" to i.hardwareRevision,
                    "Software" to i.softwareRevision,
                    "Bluetooth protocol" to i.bleProtocolVersion?.toString(),
                    "MAC address" to i.macAddress,
                    "Y number" to text(FujiGatt.CAMERA_Y_NUMBER),
                    "Wake-up mode" to u16(FujiGatt.WAKEUP_MODE)?.toString(),
                    "Startup value" to FujiProtocol.u32(this[FujiGatt.STARTUP_VALUE])?.let { "0x%08X".format(it) },
                    "Movie button" to FujiProtocol.u8(this[FujiGatt.MOVIE_BUTTON])?.toString(),
                    "Transfer setting (ex)" to this[FujiGatt.IMAGE_TRANSFER_SETTING_EX]?.let { FujiProtocol.hex(it) },
                ),
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    companion object {
        /** A string value, or null when it's empty or not text (some bodies return binary there). */
        fun printable(value: ByteArray?): String? {
            val s = FujiProtocol.string(value) ?: return null
            return s.takeIf { t -> t.all { it.code in 0x20..0x7E } }
        }
    }
}
