package com.fujiapp.remote.ble

import java.util.UUID

/** One GATT characteristic: the service it lives in, its UUID and a readable name for logs. */
data class GattChar(val service: UUID, val uuid: UUID, val name: String)

/**
 * The Fujifilm Bluetooth LE layout the app uses. The camera is the GATT server and the phone the
 * client; multi-byte values are little-endian and every write is a write with response.
 *
 * Names follow Fujifilm's own apps where known (XApp / Camera Remote call the characteristics
 * `CHARACTERISTIC_FF_*` / `CH_*`). Sources, all public or interoperability analysis:
 *  - GATT table and handles for the X100VI 1.31 ("ad14d4" profile): tiredboffin/fffw
 *    `ffbt/cfg/gatt-ad14d4.yaml`, matched against firmware by MaxRink/furble
 *    `docs/camera-research/fujifilm-secure-gatt.md`.
 *  - Secure registration (status ack, client name, subscriptions), tested on an X100VI 01.32:
 *    sarmadjari/GeoShutter `docs/fujifilm-protocol.md`.
 *  - Settings-backup file transfer, from XApp 2.2.1: Th0rgal/fuji-bridge `FujiBackup.swift`.
 *  - Camera information names and value formats: FUJIFILM Camera Remote 4.10.2 (`BTCamera`).
 *
 * No camera firmware is touched: this is the same Bluetooth interface Fujifilm's XApp uses.
 */
object FujiGatt {

    /** Bluetooth SIG company identifier in Fujifilm's advertisement manufacturer data. */
    const val COMPANY_ID = 0x04D8

    private fun uuid(s: String): UUID = UUID.fromString(s)
    private fun sig(short: String): UUID = uuid("0000$short-0000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration descriptor (turns notifications/indications on). */
    val CCCD: UUID = sig("2902")

    // ---- Standard services ----

    val SVC_GENERIC_ACCESS: UUID = sig("1800")
    val DEVICE_NAME = GattChar(SVC_GENERIC_ACCESS, sig("2a00"), "Device name")

    val SVC_DEVICE_INFORMATION: UUID = sig("180a")
    val MANUFACTURER = GattChar(SVC_DEVICE_INFORMATION, sig("2a29"), "Manufacturer")
    val MODEL_NUMBER = GattChar(SVC_DEVICE_INFORMATION, sig("2a24"), "Model number")
    val SERIAL_NUMBER = GattChar(SVC_DEVICE_INFORMATION, sig("2a25"), "Serial number")
    val HARDWARE_REVISION = GattChar(SVC_DEVICE_INFORMATION, sig("2a27"), "Hardware revision")
    val FIRMWARE_REVISION = GattChar(SVC_DEVICE_INFORMATION, sig("2a26"), "Firmware revision")
    val SOFTWARE_REVISION = GattChar(SVC_DEVICE_INFORMATION, sig("2a28"), "Software revision")

    // ---- Camera information (XApp: CAMERA_INFORMATION_RED) ----

    val SVC_CAMERA_INFORMATION: UUID = uuid("a9d2b304-e8d6-4902-8336-352b772d7597")
    /** uint16. */
    val BLE_PROTOCOL_VERSION = GattChar(SVC_CAMERA_INFORMATION, uuid("389363e4-712e-4cf2-a72e-bfcf7fb6adc1"), "BLE protocol version")
    /** NUL-terminated ASCII. */
    val CAMERA_SERIAL_NUMBER = GattChar(SVC_CAMERA_INFORMATION, uuid("e8e40d50-a625-4f1d-96ed-8cec034f5690"), "Camera serial number")
    /** NUL-terminated ASCII, "AA:BB:CC:DD:EE:FF". */
    val CAMERA_MAC_ADDRESS = GattChar(SVC_CAMERA_INFORMATION, uuid("49a12959-dfaa-4eb2-89ce-62548ad948f3"), "Camera MAC address")

    // ---- Lens information ----

    val SVC_LENS_INFORMATION: UUID = uuid("15ca59fe-620c-464d-a987-223fab660cde")
    val LENS_PRODUCT_NAME = GattChar(SVC_LENS_INFORMATION, uuid("68887a7a-10a7-4a4a-aadd-6f9db1abaa0e"), "Lens name")
    val LENS_FIRMWARE_VERSION = GattChar(SVC_LENS_INFORMATION, uuid("621a98d8-6314-4c3e-a683-eea1d6166606"), "Lens firmware")

    // ---- Connected device information: secure pairing (XApp: CONNECTED_DEVICE_INFORMATION_RED) ----

    val SVC_CONNECTED_DEVICE: UUID = uuid("123d8f06-62a1-4935-9322-833c531ee225")
    /** 4 bytes, read then written back with byte 3 = 0x20 (see [FujiProtocol.ackStatus]). */
    val DEVICE_IDENTIFICATION_NUMBER = GattChar(SVC_CONNECTED_DEVICE, uuid("f557d96b-8284-4667-8793-b971c1deca2a"), "Status")
    /** The phone's name, UTF-8 plus NUL. */
    val CONNECTED_DEVICE_NAME = GattChar(SVC_CONNECTED_DEVICE, uuid("85b9163e-62d1-49ff-a6f5-054b4630d4a1"), "Client name")

    /** Token pairing service of older ("Basic") firmware; absent from the X100VI from 1.30 on. */
    val SVC_LEGACY_PAIRING: UUID = uuid("91f1de68-dff6-466e-8b65-ff13b0f16fb8")

    // ---- Camera settings (XApp: SERVICE_FF_CAMERA_SETTING) ----

    val SVC_CAMERA_SETTING: UUID = uuid("4e941240-d01d-46b9-a5ea-67636806830b")
    /** The camera's NAME setting, e.g. "FUJIFILM-X100VI-1A2B". Notifies. */
    val CAMERA_NAME = GattChar(SVC_CAMERA_SETTING, uuid("bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4"), "Camera name")
    val LOGGING_SETTING = GattChar(SVC_CAMERA_SETTING, uuid("75823784-fbb7-4b71-abae-cd9a34072e3c"), "Logging setting")
    /** Number of phones paired with the camera (first byte). */
    val PAIRING_SMART_DEVICE_NUM = GattChar(SVC_CAMERA_SETTING, uuid("8814441b-1d7b-4046-891d-d8f80864cc8e"), "Paired phones")

    // ---- Camera state (XApp: SERVICE_FF_CAMERA_STATE) ----

    val SVC_CAMERA_STATE: UUID = uuid("4c0020fe-f3b6-40de-acc9-77d129067b14")
    val AP_STATE = GattChar(SVC_CAMERA_STATE, uuid("a68e3f66-0fcc-4395-8d4c-aa980b5877fa"), "AP state")
    val TRANSFER_STATE = GattChar(SVC_CAMERA_STATE, uuid("bd17ba04-b76b-4892-a545-b73ba1f74dae"), "Transfer state")
    val DATE_SYNC_STATE = GattChar(SVC_CAMERA_STATE, uuid("f9150137-5d40-4801-a8dc-f7fc5b01da50"), "Date sync state")
    val LOCATION_SYNC_STATE = GattChar(SVC_CAMERA_STATE, uuid("ad06c7b7-f41a-46f4-a29a-712055319122"), "Location sync state")
    /** uint16 indication: 1 = backup ready to transfer, 0 = camera busy. */
    val BACKUP_STATE = GattChar(SVC_CAMERA_STATE, uuid("11438c83-cfb0-4511-841b-759e0d2321c8"), "Backup state")

    // ---- Camera control (furble: "shutter" service) ----

    val SVC_CAMERA_CONTROL: UUID = uuid("6514eb81-4e8f-458d-aa2a-e691336cdfac")
    /** uint16: 1 = camera settings (2 would be IPTC). */
    val BACKUP_REQUEST = GattChar(SVC_CAMERA_CONTROL, uuid("f14e7a88-6f57-4872-a69b-b47ef3295c9b"), "Backup request")

    // ---- Camera startup information (XApp: CAMERA_STARTUP_INFORMATION_RED) ----

    val SVC_STARTUP_INFORMATION: UUID = uuid("804daa8e-ffeb-4ab3-8e75-6edd7303208d")
    /** uint16, see [FujiProtocol.powerState]. */
    val POWER_KEY_STATE = GattChar(SVC_STARTUP_INFORMATION, uuid("f90f7d3a-3b64-45c6-ab21-933900184837"), "Power switch")
    /** uint16 0/1: the camera's CONNECT WHILE POWER OFF setting. */
    val REMOTE_BOOT_SETTING = GattChar(SVC_STARTUP_INFORMATION, uuid("7170fd5a-56d9-4c19-b043-7a7047d8e1a0"), "Connect while power off")

    // ---- File transfer: equipment status and backup ----

    val SVC_FILE: UUID = uuid("af854c2e-b214-458e-97e2-912c4ecf2cb8")
    /** uint32, written 0 to start sending the first file. */
    val FILE_TRANSFER_INDEX = GattChar(SVC_FILE, uuid("051dd980-df9d-4472-a2e1-35811dd24ee1"), "File transfer index")
    /** 44 bytes: name (40, NUL padded) + uint32 size. */
    val FILE_INFORMATION = GattChar(SVC_FILE, uuid("c922ac69-9480-4348-8f4b-9ee29bc30d1d"), "File information")
    /** uint32: bytes per chunk the camera should send. */
    val FILE_PARTIAL_SIZE = GattChar(SVC_FILE, uuid("7f3400fe-17e7-4b80-8a0e-81b0343c1b49"), "File partial size")
    /** One chunk, see [FileTransfer.parseChunk]. */
    val FILE_PARTIAL_DATA = GattChar(SVC_FILE, uuid("ac0c799a-fa6c-4df5-bbc5-bb95cce7e6ea"), "File partial data")
    /** uint16: 3 = finished, 0 = abort. */
    val FILE_TRANSFER_RESULT = GattChar(SVC_FILE, uuid("68052e8a-fb91-404f-8847-0eb4be24308c"), "File transfer result")
    /** Notify, uint16 first: 1/2 = a chunk is ready, 3 = file complete. */
    val FILE_TRANSACTION_STATE = GattChar(SVC_FILE, uuid("2e27ed9f-5506-41cd-ba48-dac06669ad95"), "File transaction state")

    /** Services a secure camera advertises: in PAIRING REGISTRATION, and when bonded and looking for its phone. */
    val ADVERTISED_PAIRING: UUID = SVC_CAMERA_INFORMATION
    val ADVERTISED_BONDED: List<UUID> = listOf(SVC_STARTUP_INFORMATION, SVC_CONNECTED_DEVICE)

    /** Every characteristic above, for log names. */
    val ALL: List<GattChar> = listOf(
        DEVICE_NAME, MANUFACTURER, MODEL_NUMBER, SERIAL_NUMBER, HARDWARE_REVISION, FIRMWARE_REVISION,
        SOFTWARE_REVISION, BLE_PROTOCOL_VERSION, CAMERA_SERIAL_NUMBER, CAMERA_MAC_ADDRESS, LENS_PRODUCT_NAME,
        LENS_FIRMWARE_VERSION, DEVICE_IDENTIFICATION_NUMBER, CONNECTED_DEVICE_NAME, CAMERA_NAME, LOGGING_SETTING,
        PAIRING_SMART_DEVICE_NUM, AP_STATE, TRANSFER_STATE, DATE_SYNC_STATE, LOCATION_SYNC_STATE, BACKUP_STATE,
        BACKUP_REQUEST, POWER_KEY_STATE, REMOTE_BOOT_SETTING, FILE_TRANSFER_INDEX, FILE_INFORMATION,
        FILE_PARTIAL_SIZE, FILE_PARTIAL_DATA, FILE_TRANSFER_RESULT, FILE_TRANSACTION_STATE,
    )

    val SERVICE_NAMES: Map<UUID, String> = mapOf(
        SVC_GENERIC_ACCESS to "Generic access",
        SVC_DEVICE_INFORMATION to "Device information",
        SVC_CAMERA_INFORMATION to "Camera information",
        SVC_LENS_INFORMATION to "Lens information",
        SVC_CONNECTED_DEVICE to "Connected device (secure pairing)",
        SVC_LEGACY_PAIRING to "Legacy pairing",
        SVC_CAMERA_SETTING to "Camera settings",
        SVC_CAMERA_STATE to "Camera state",
        SVC_CAMERA_CONTROL to "Camera control",
        SVC_STARTUP_INFORMATION to "Startup information",
        SVC_FILE to "File transfer",
    )

    /**
     * Subscriptions made during registration, in the order GeoShutter found the X100VI accepts:
     * the two indications first, then notifications. A missing one is logged and skipped.
     */
    val REGISTRATION_SUBSCRIPTIONS: List<GattChar> = listOf(
        AP_STATE, TRANSFER_STATE, DATE_SYNC_STATE, LOCATION_SYNC_STATE, CAMERA_NAME, LOGGING_SETTING,
    )
}
