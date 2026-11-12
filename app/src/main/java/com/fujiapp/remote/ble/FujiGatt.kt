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
 *  - Restore, camera vital data (shutter count) and the transfer state/result values: static
 *    analysis of FUJIFILM XApp 2.7.6 for interoperability (`BTConstans`, `BTCameraModel`). Only
 *    identifiers, byte values and the order of operations were taken.
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
    /** uint16. */
    val WAKEUP_MODE = GattChar(SVC_CAMERA_INFORMATION, uuid("9c72c205-5740-4f17-9949-0d3fadf2f67a"), "Wake-up mode")
    /** uint32. */
    val STARTUP_VALUE = GattChar(SVC_CAMERA_INFORMATION, uuid("4bf16f6d-7264-4e76-b0d2-52cde16d2c7d"), "Startup value")
    /** One byte. */
    val MOVIE_BUTTON = GattChar(SVC_CAMERA_INFORMATION, uuid("cae8c738-4bb6-4605-98c1-58331c87f3b6"), "Movie button")
    /** XApp: CAMERA_Y_NUMBER, a 30-byte string. */
    val CAMERA_Y_NUMBER = GattChar(SVC_CAMERA_INFORMATION, uuid("27870478-94a9-4345-849b-efa3bf37887f"), "Y number")

    // ---- Lens information ----

    val SVC_LENS_INFORMATION: UUID = uuid("15ca59fe-620c-464d-a987-223fab660cde")
    val LENS_PRODUCT_NAME = GattChar(SVC_LENS_INFORMATION, uuid("68887a7a-10a7-4a4a-aadd-6f9db1abaa0e"), "Lens name")
    val LENS_FIRMWARE_VERSION = GattChar(SVC_LENS_INFORMATION, uuid("621a98d8-6314-4c3e-a683-eea1d6166606"), "Lens firmware")
    val LENS_SERIAL_NUMBER = GattChar(SVC_LENS_INFORMATION, uuid("df287e0c-c23f-4f80-afdd-c2b570288cdb"), "Lens serial")

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
    /** uint16 0/1: the camera's SMARTPHONE LOCATION SYNC. setting. Notifies. */
    val LOCATION_SYNC_SETTING = GattChar(SVC_CAMERA_SETTING, uuid("aab609c4-94dd-4d89-bc60-665d5090b828"), "Location sync setting")
    /** uint16 seconds between the camera's location requests. Notifies. */
    val LOCATION_SYNC_CYCLE = GattChar(SVC_CAMERA_SETTING, uuid("c95d91ae-b247-4d6d-8661-7dd5d6a0f85b"), "Location sync interval")
    /** uint16 0/1: reserve pictures for transfer after shooting. Notifies. */
    val RESERVATION_AFTER_SHOOTING = GattChar(SVC_CAMERA_SETTING, uuid("bd45f887-a6be-4cb7-8565-390df38bf5bf"), "Transfer after shooting")
    /** One byte 0/1. Notifies. */
    val IMAGE_TRANSFER_SETTING = GattChar(SVC_CAMERA_SETTING, uuid("caedb497-83bf-482c-91ef-91cf6f1216ff"), "Image transfer setting")
    /** Four bytes. Notifies. */
    val IMAGE_TRANSFER_SETTING_EX = GattChar(SVC_CAMERA_SETTING, uuid("98934b2c-756c-4632-aa2f-dcba1bfec824"), "Image transfer setting ex")
    /** One byte 0/1: send resized pictures. Notifies. */
    val IMAGE_RESIZE_SETTING = GattChar(SVC_CAMERA_SETTING, uuid("82a9f452-c5ce-4ef5-8203-3fc9a47f8171"), "Image resize setting")

    // ---- Camera state (XApp: SERVICE_FF_CAMERA_STATE) ----

    val SVC_CAMERA_STATE: UUID = uuid("4c0020fe-f3b6-40de-acc9-77d129067b14")
    val AP_STATE = GattChar(SVC_CAMERA_STATE, uuid("a68e3f66-0fcc-4395-8d4c-aa980b5877fa"), "AP state")
    val TRANSFER_STATE = GattChar(SVC_CAMERA_STATE, uuid("bd17ba04-b76b-4892-a545-b73ba1f74dae"), "Transfer state")
    val DATE_SYNC_STATE = GattChar(SVC_CAMERA_STATE, uuid("f9150137-5d40-4801-a8dc-f7fc5b01da50"), "Date sync state")
    val LOCATION_SYNC_STATE = GattChar(SVC_CAMERA_STATE, uuid("ad06c7b7-f41a-46f4-a29a-712055319122"), "Location sync state")
    /** uint16 indication: 1 = backup ready to transfer, 0 = camera busy. */
    val BACKUP_STATE = GattChar(SVC_CAMERA_STATE, uuid("11438c83-cfb0-4511-841b-759e0d2321c8"), "Backup state")
    /** uint16 indication: 1 = camera ready to receive a restore, 0 = not now. */
    val RESTORE_STATE = GattChar(SVC_CAMERA_STATE, uuid("4b3a413c-230f-42bc-b3ed-b1db2eadee82"), "Restore state")
    /** uint16 indication: 1 = log / vital data ready to transfer. */
    val LOG_TRANSFER_STATE = GattChar(SVC_CAMERA_STATE, uuid("2f6cb772-1d69-448e-8819-fe6b5c20b094"), "Log transfer state")
    /** uint16 notify: 1 = the camera's vital data changed. */
    val CAMERA_VITAL_STATE = GattChar(SVC_CAMERA_STATE, uuid("e6692c5c-b7cd-44f4-95fc-eda07ce32560"), "Camera vital state")
    val FWUPDATE_STATE = GattChar(SVC_CAMERA_STATE, uuid("049ec406-ef75-4205-a390-08fe209c51f0"), "Firmware update state")
    /** uint16. */
    val CARD_STATE = GattChar(SVC_CAMERA_STATE, uuid("34d8c8de-e2a9-43ff-822c-7d945dd8d8e1"), "Card state")
    /** uint16, see [FujiProtocol.stateErrorDetails]: why the camera can't do something right now. */
    val STATE_ERROR_DETAILS = GattChar(SVC_CAMERA_STATE, uuid("1587b102-0b6d-4b63-9226-66fcc6d17387"), "State details")

    // ---- Camera control (furble: "shutter" service) ----

    val SVC_CAMERA_CONTROL: UUID = uuid("6514eb81-4e8f-458d-aa2a-e691336cdfac")
    /** uint16: 1 = camera settings (2 would be IPTC). */
    val BACKUP_REQUEST = GattChar(SVC_CAMERA_CONTROL, uuid("f14e7a88-6f57-4872-a69b-b47ef3295c9b"), "Backup request")
    /** uint16: 1 = camera settings (2 would be IPTC). */
    val RESTORE_REQUEST = GattChar(SVC_CAMERA_CONTROL, uuid("f1897eb9-a9aa-40f0-8ce6-2fa7915bc459"), "Restore request")
    /** uint16: 1 = activity logs, 2 = camera vital data (shutter count etc.). */
    val LOG_TRANSFER_REQUEST = GattChar(SVC_CAMERA_CONTROL, uuid("2674f0b0-f909-4e73-8656-338e9c74457f"), "Log transfer request")
    /** uint16: 1 = half press (focus), 2 = full press, 0 = release (firmware parser, MaxRink/furble). */
    val SHOOTING_REQUEST = GattChar(SVC_CAMERA_CONTROL, uuid("7fcf49c6-4ff0-4777-a03d-1a79166af7a8"), "Shutter")

    // ---- Current time and location ----

    val SVC_CURRENT_TIME: UUID = uuid("e872b11f-d526-4ae1-9bb4-89a99d48fa59")
    /** 12 bytes, see [FujiProtocol.timePacket]. Applied only when the camera asked (DATE_SYNC_STATE). */
    val UTC_AND_TIMEZONE = GattChar(SVC_CURRENT_TIME, uuid("c52edbce-1fe2-4ecc-9483-907e6592be9e"), "Date and time")

    val SVC_CURRENT_LOCATION: UUID = uuid("3b46ec2b-48ba-41fd-b1b8-ed860b60d22b")
    /** 23 bytes, see [FujiProtocol.geotagPacket]. */
    val LOCATION_AND_SPEED = GattChar(SVC_CURRENT_LOCATION, uuid("0f36ec14-29e5-411a-a1b6-64ee8383f090"), "Location")

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
    /** Notify, 4 bytes: uint16 state (see [FileTransfer]), uint16 sequence number. */
    val FILE_TRANSACTION_STATE = GattChar(SVC_FILE, uuid("2e27ed9f-5506-41cd-ba48-dac06669ad95"), "File transaction state")
    /** uint32: number of files waiting (activity logs). */
    val FILE_NUMBER = GattChar(SVC_FILE, uuid("e3fbbfcf-f326-4b0f-82cf-b00ae1b107a2"), "File number")

    /** Services a secure camera advertises: in PAIRING REGISTRATION, and when bonded and looking for its phone. */
    val ADVERTISED_PAIRING: UUID = SVC_CAMERA_INFORMATION
    val ADVERTISED_BONDED: List<UUID> = listOf(SVC_STARTUP_INFORMATION, SVC_CONNECTED_DEVICE)

    /**
     * Everything readable that describes the camera, read for the status screen. Leaves out the
     * Wi-Fi passphrase on purpose, and the pairing status (read during registration).
     */
    val READABLE: List<GattChar> = listOf(
        DEVICE_NAME, MANUFACTURER, MODEL_NUMBER, SERIAL_NUMBER, HARDWARE_REVISION, FIRMWARE_REVISION,
        SOFTWARE_REVISION, BLE_PROTOCOL_VERSION, CAMERA_SERIAL_NUMBER, CAMERA_MAC_ADDRESS, WAKEUP_MODE, STARTUP_VALUE,
        MOVIE_BUTTON, CAMERA_Y_NUMBER, LENS_PRODUCT_NAME, LENS_FIRMWARE_VERSION, LENS_SERIAL_NUMBER, CAMERA_NAME,
        LOGGING_SETTING, PAIRING_SMART_DEVICE_NUM, LOCATION_SYNC_SETTING, LOCATION_SYNC_CYCLE,
        RESERVATION_AFTER_SHOOTING, IMAGE_TRANSFER_SETTING, IMAGE_TRANSFER_SETTING_EX, IMAGE_RESIZE_SETTING,
        AP_STATE, TRANSFER_STATE, CAMERA_VITAL_STATE, FWUPDATE_STATE, CARD_STATE, STATE_ERROR_DETAILS,
        POWER_KEY_STATE, REMOTE_BOOT_SETTING,
    )

    /** Every characteristic above, for log names. */
    val ALL: List<GattChar> = READABLE + listOf(
        DEVICE_IDENTIFICATION_NUMBER, CONNECTED_DEVICE_NAME, DATE_SYNC_STATE, LOCATION_SYNC_STATE, BACKUP_STATE,
        RESTORE_STATE, LOG_TRANSFER_STATE, BACKUP_REQUEST, RESTORE_REQUEST, LOG_TRANSFER_REQUEST, SHOOTING_REQUEST,
        UTC_AND_TIMEZONE, LOCATION_AND_SPEED, FILE_TRANSFER_INDEX, FILE_INFORMATION, FILE_PARTIAL_SIZE,
        FILE_PARTIAL_DATA, FILE_TRANSFER_RESULT, FILE_TRANSACTION_STATE, FILE_NUMBER,
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
        SVC_CURRENT_TIME to "Current time",
        SVC_CURRENT_LOCATION to "Current location",
    )

    /**
     * Subscriptions made during registration, in the order XApp and GeoShutter use: the two
     * indications first, then notifications. A missing one is logged and skipped.
     *
     * All of them are needed, LOCATION_SYNC_STATE included. In a field log from an X100VI 1.31
     * without it, the camera reported its power switch as 0, never asked for the time, and left
     * every shutter-count request unanswered; the moment it was subscribed, the power switch
     * turned to "on" and the camera asked for the time and the location.
     */
    val REGISTRATION_SUBSCRIPTIONS: List<GattChar> = listOf(
        AP_STATE, TRANSFER_STATE, DATE_SYNC_STATE, LOCATION_SYNC_STATE, CAMERA_NAME, LOGGING_SETTING,
    )

    /** Optional subscriptions that keep the status screen live. Failures are skipped. */
    val LIVE_SUBSCRIPTIONS: List<GattChar> = listOf(
        POWER_KEY_STATE, REMOTE_BOOT_SETTING, LOCATION_SYNC_SETTING, LOCATION_SYNC_CYCLE, CAMERA_VITAL_STATE,
    )

    /** Location sync choices the camera accepts, in seconds (XApp's list). */
    val LOCATION_SYNC_INTERVALS: List<Int> = listOf(10, 15, 20, 30, 60, 120, 240, 480)
}
