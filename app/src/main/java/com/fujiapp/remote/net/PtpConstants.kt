package com.fujiapp.remote.net

/**
 * Protocol constants for Fujifilm's WiFi remote control — a vendor variant of PTP/IP
 * (ISO 15740, Picture Transfer Protocol over TCP/IP).
 *
 * This is interoperability work based on PUBLIC reverse-engineering of the camera's network
 * protocol (the same approach libgphoto2 and the Fujifilm "Camera Remote"/"XApp" clients use).
 * No camera firmware is decompiled, modified, or redistributed.
 *
 * Primary references (values below are cross-checked against these):
 *  - petabyt/libfuji + petabyt/fudge  https://github.com/petabyt/libfuji   (reference Android client)
 *  - malc0mn/ptp-ip (Go)              https://github.com/malc0mn/ptp-ip
 *  - hkr/fuji-cam-wifi-tool (C++)     https://github.com/hkr/fuji-cam-wifi-tool
 *  - gphoto/libgphoto2 (ptp2)         https://github.com/gphoto/libgphoto2
 *
 * Network layout once the phone has joined the camera's own WiFi access point:
 *  - Camera IP:        192.168.0.1
 *  - Command/data:     TCP 55740
 *  - Event channel:    TCP 55741
 *  - Live-view stream: TCP 55742   (length-prefixed JPEG frames; image bytes begin at offset 18)
 *
 * Fuji vendor property codes (0xD0xx) differ between camera generations and some are not publicly
 * documented at all (e.g. Color Chrome, Clarity on recent bodies). This app therefore does NOT
 * depend on a fixed list: it enumerates whatever properties the connected body reports
 * (GetDeviceInfo) and reads/writes them generically. [KnownProps] only supplies friendly labels.
 */
object Ptp {

    const val CAMERA_IP = "192.168.0.1"
    const val PORT_CMD = 55740
    const val PORT_EVENT = 55741
    const val PORT_LIVEVIEW = 55742

    /** Fuji PTP/IP init handshake magic (0x8F53E4F2 has the high bit set, so not a const literal). */
    val FUJI_PROTOCOL_VERSION: Int = 0x8F53E4F2.toInt()

    /** Value written to ClientState (0xDF01) to request remote-control mode. */
    const val FUJI_REMOTE_MODE = 5

    /** Value written to RemoteVersion (0xDF24); the value Fujifilm's own Camera Connect uses. */
    const val FUJI_CAM_CONNECT_REMOTE_VER = 0x0002000C

    // ---- PTP/IP packet types (used only by the init handshake on the command channel) ----
    object PacketType {
        const val INIT_COMMAND_REQUEST = 0x0001
        const val INIT_COMMAND_ACK = 0x0002
        const val INIT_EVENT_REQUEST = 0x0003
        const val INIT_EVENT_ACK = 0x0004
        const val INIT_FAIL = 0x0005
    }

    /** The DataPhase word (u16) that leads every Fuji operation request/response packet. */
    object DataPhase {
        const val NO_DATA_OR_DATA_IN = 0x0001 // request for an op with no data-out (incl. data-in gets)
        const val DATA_OUT = 0x0002           // host -> camera data (e.g. the value in SetDevicePropValue)
    }

    // ---- PTP operation codes ----
    object Op {
        const val GET_DEVICE_INFO = 0x1001
        const val OPEN_SESSION = 0x1002
        const val CLOSE_SESSION = 0x1003
        const val GET_STORAGE_IDS = 0x1004
        const val GET_OBJECT_HANDLES = 0x1007
        const val GET_OBJECT_INFO = 0x1008
        const val GET_OBJECT = 0x1009
        const val GET_THUMB = 0x100A
        const val INITIATE_CAPTURE = 0x100E
        const val GET_DEVICE_PROP_DESC = 0x1014
        const val GET_DEVICE_PROP_VALUE = 0x1015
        const val SET_DEVICE_PROP_VALUE = 0x1016
        const val TERMINATE_OPEN_CAPTURE = 0x1018
        const val GET_PARTIAL_OBJECT = 0x101B
        const val INITIATE_OPEN_CAPTURE = 0x101C

        // Fuji vendor operations (0x9xxx)
        const val FUJI_GET_DEVICE_INFO = 0x902B       // returns a list of DevicePropDesc structs
        const val FUJI_GET_CAPTURE_PREVIEW = 0x9022
        const val FUJI_INITIATE_MOVIE_CAPTURE = 0x9020
        const val FUJI_TERMINATE_MOVIE_CAPTURE = 0x9021
        const val FUJI_STEP_SHUTTER_SPEED = 0x902C    // param: 1 = up, 0 = down
        const val FUJI_STEP_FNUMBER = 0x902D
        const val FUJI_STEP_EXPOSURE_BIAS = 0x902E
        const val FUJI_SET_FOCUS_POINT = 0x9026
        const val FUJI_RESET_FOCUS_POINT = 0x9027
    }

    // ---- PTP response codes ----
    object Resp {
        const val OK = 0x2001
        const val SESSION_ALREADY_OPEN = 0x201E
        fun isResponseCode(code: Int): Boolean = code in 0x2001..0x21FF
        fun name(code: Int): String = when (code) {
            OK -> "OK"
            0x2002 -> "GeneralError"
            0x2003 -> "SessionNotOpen"
            0x2005 -> "OperationNotSupported"
            0x2006 -> "ParameterNotSupported"
            0x200A -> "AccessDenied"
            0x200B -> "InvalidDevicePropValue"
            0x2019 -> "DeviceBusy"
            SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
            else -> "0x%04X".format(code)
        }
    }

    // ---- Fuji event codes (on InitiateCapture) ----
    object Event {
        const val FUJI_PREVIEW_AVAILABLE = 0xC001
        const val FUJI_OBJECT_ADDED = 0xC004
    }

    // ---- PTP datatype codes (DevicePropDesc) ----
    object Type {
        const val INT8 = 0x0001
        const val UINT8 = 0x0002
        const val INT16 = 0x0003
        const val UINT16 = 0x0004
        const val INT32 = 0x0005
        const val UINT32 = 0x0006
        const val INT64 = 0x0007
        const val UINT64 = 0x0008
        const val STR = 0xFFFF
        /** Size in bytes of a fixed-width numeric type, or null if variable. */
        fun sizeOf(type: Int): Int? = when (type) {
            INT8, UINT8 -> 1
            INT16, UINT16 -> 2
            INT32, UINT32 -> 4
            INT64, UINT64 -> 8
            else -> null
        }
        fun isSigned(type: Int): Boolean = type == INT8 || type == INT16 || type == INT32 || type == INT64
    }

    /** Handshake / session-management device properties. */
    object Handshake {
        const val CAMERA_STATE = 0xDF00
        const val CLIENT_STATE = 0xDF01
        const val IMAGE_GET_VERSION = 0xDF21
        const val GET_OBJECT_VERSION = 0xDF22
        const val REMOTE_VERSION = 0xDF24
        const val REMOTE_IMAGE_VIEW_VERSION = 0xDF25
        const val EVENTS_LIST = 0xD212

        // CameraState (0xDF00) values
        const val STATE_WAIT_FOR_ACCESS = 0
        const val STATE_MULTIPLE_TRANSFER = 1
        const val STATE_FULL_ACCESS = 2
        const val STATE_PC_AUTO_SAVE = 3
        const val STATE_REMOTE_ACCESS = 6
    }

    /** Standard still-imaging device property codes (ISO 15740). */
    object Prop {
        const val BATTERY_LEVEL = 0x5001
        const val WHITE_BALANCE = 0x5005
        const val F_NUMBER = 0x5007
        const val FOCUS_MODE = 0x500A
        const val EXPOSURE_TIME = 0x500D
        const val EXPOSURE_PROGRAM_MODE = 0x500E
        const val EXPOSURE_INDEX_ISO = 0x500F
        const val EXPOSURE_BIAS_COMPENSATION = 0x5010
        const val STILL_CAPTURE_MODE = 0x5013
        const val CONTRAST = 0x5014
        const val SHARPNESS = 0x5015
    }

    /** Fuji vendor device property codes (labels only; the live device info is authoritative). */
    object FujiProp {
        const val FILM_SIMULATION = 0xD001
        const val FILM_SIMULATION_TUNE = 0xD002
        const val DYNAMIC_RANGE = 0xD007
        const val COLOR_MODE = 0xD008
        const val COLOR_SPACE = 0xD00A
        const val WB_TUNE1 = 0xD00B
        const val WB_TUNE2 = 0xD00C
        const val COLOR_TEMPERATURE = 0xD017
        const val IMAGE_QUALITY = 0xD018
        const val NOISE_REDUCTION = 0xD01C
        const val GRAIN_EFFECT = 0xD023
        const val EXPOSURE_INDEX_LV = 0xD02A
        const val HIGHLIGHT_TONE = 0xD320
        const val SHADOW_TONE = 0xD321
        const val LONG_EXPOSURE_NR = 0xD322
        const val APERTURE = 0xD218
        // Sources disagree between 0xD219 and 0xD240 for shutter speed; verify on-device.
        const val SHUTTER_SPEED = 0xD240
        const val BATTERY = 0xD242
        const val FOCUS_METERING_MODE = 0xD17C
        const val IMAGE_SIZE = 0xD174
    }

    /** Film simulation enum values (older set; newer looks like Classic Neg are body-specific). */
    val FILM_SIMULATIONS: Map<Int, String> = mapOf(
        0x0001 to "PROVIA / Standard",
        0x0002 to "Velvia / Vivid",
        0x0003 to "ASTIA / Soft",
        0x0004 to "Monochrome",
        0x0005 to "Sepia",
        0x0006 to "PRO Neg. Hi",
        0x0007 to "PRO Neg. Std",
        0x0008 to "Monochrome + Ye",
        0x0009 to "Monochrome + R",
        0x000A to "Monochrome + G",
        0x000B to "Classic Chrome",
        0x000C to "ACROS",
        0x000D to "ACROS + Ye",
        0x000E to "ACROS + R",
        0x000F to "ACROS + G",
        0x0010 to "ETERNA / Cinema",
    )

    object KnownProps {
        private val labels: Map<Int, String> = buildMap {
            put(Prop.BATTERY_LEVEL, "Battery level")
            put(Prop.WHITE_BALANCE, "White balance")
            put(Prop.F_NUMBER, "Aperture (F-number)")
            put(Prop.FOCUS_MODE, "Focus mode")
            put(Prop.EXPOSURE_TIME, "Shutter speed")
            put(Prop.EXPOSURE_PROGRAM_MODE, "Exposure mode")
            put(Prop.EXPOSURE_INDEX_ISO, "ISO")
            put(Prop.EXPOSURE_BIAS_COMPENSATION, "Exposure compensation")
            put(Prop.STILL_CAPTURE_MODE, "Drive / capture mode")
            put(Prop.CONTRAST, "Contrast")
            put(Prop.SHARPNESS, "Sharpness")
            put(FujiProp.FILM_SIMULATION, "Film simulation")
            put(FujiProp.FILM_SIMULATION_TUNE, "Film simulation tune")
            put(FujiProp.DYNAMIC_RANGE, "Dynamic range")
            put(FujiProp.COLOR_MODE, "Color")
            put(FujiProp.COLOR_SPACE, "Color space")
            put(FujiProp.WB_TUNE1, "White balance shift 1")
            put(FujiProp.WB_TUNE2, "White balance shift 2")
            put(FujiProp.COLOR_TEMPERATURE, "WB colour temperature")
            put(FujiProp.IMAGE_QUALITY, "Image quality")
            put(FujiProp.NOISE_REDUCTION, "Noise reduction")
            put(FujiProp.GRAIN_EFFECT, "Grain effect")
            put(FujiProp.HIGHLIGHT_TONE, "Highlight tone")
            put(FujiProp.SHADOW_TONE, "Shadow tone")
            put(FujiProp.LONG_EXPOSURE_NR, "Long-exposure NR")
            put(FujiProp.APERTURE, "Aperture")
            put(FujiProp.SHUTTER_SPEED, "Shutter speed")
            put(FujiProp.BATTERY, "Battery")
            put(FujiProp.FOCUS_METERING_MODE, "Focus metering mode")
            put(FujiProp.IMAGE_SIZE, "Image size")
        }

        fun label(code: Int): String = labels[code] ?: "Property 0x%04X".format(code)

        /** Human-readable value for known enumerations, else a plain number. */
        fun valueLabel(code: Int, value: Long): String = when (code) {
            FujiProp.FILM_SIMULATION -> FILM_SIMULATIONS[value.toInt()] ?: "0x%04X".format(value)
            else -> value.toString()
        }

        /** Property codes that make up a "recipe" (a custom look), in display order. */
        val recipeOrder: List<Int> = listOf(
            FujiProp.FILM_SIMULATION, FujiProp.FILM_SIMULATION_TUNE,
            FujiProp.DYNAMIC_RANGE, FujiProp.HIGHLIGHT_TONE, FujiProp.SHADOW_TONE,
            FujiProp.COLOR_MODE, Prop.SHARPNESS, FujiProp.NOISE_REDUCTION,
            FujiProp.GRAIN_EFFECT, Prop.WHITE_BALANCE, FujiProp.COLOR_TEMPERATURE,
            FujiProp.WB_TUNE1, FujiProp.WB_TUNE2,
        )

        fun isRecipeProperty(code: Int): Boolean = code in recipeOrder

        /** Volatile / non-recipe codes to skip when backing up a look. */
        val volatile: Set<Int> = setOf(
            Prop.BATTERY_LEVEL, FujiProp.BATTERY, Handshake.CAMERA_STATE, Handshake.CLIENT_STATE,
            Handshake.EVENTS_LIST, Handshake.REMOTE_VERSION, Handshake.GET_OBJECT_VERSION,
            Handshake.IMAGE_GET_VERSION, Handshake.REMOTE_IMAGE_VIEW_VERSION,
        )
    }
}
