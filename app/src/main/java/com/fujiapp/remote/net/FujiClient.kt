package com.fujiapp.remote.net

import java.io.IOException
import javax.net.SocketFactory

/**
 * High-level client for a Fujifilm camera over WiFi (PTP/IP variant).
 *
 * Lifecycle: [connect] runs the full Fuji handshake (and will block while the camera shows its
 * "accept connection?" prompt — the caller surfaces that to the user). After that, use the
 * property and capture methods. [startLiveView] opens the live-view stream; frames are read by a
 * separate [LiveView] reader on port 55742.
 *
 * All methods are blocking socket I/O and must be called off the main thread (see CameraSession).
 */
class FujiClient(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    /** A stable per-install GUID so the camera remembers this client after the first "OK". */
    private val clientGuid: ByteArray,
    private val deviceName: String = "Fuji Remote",
) {
    private val transport = FujiTransport(socketFactory = socketFactory)

    var cameraName: String = ""
        private set
    var remoteVersion: Long = -1
        private set

    val isConnected: Boolean get() = transport.isConnected

    /** Progress callbacks during the (possibly slow) connect handshake. */
    fun interface ConnectProgress { fun onStep(message: String) }

    /**
     * Connect and complete the Fuji handshake. Throws on failure.
     * Blocks up to [acceptTimeoutMs] while waiting for the user to press OK on the camera.
     */
    fun connect(progress: ConnectProgress = ConnectProgress {}, acceptTimeoutMs: Long = 30_000) {
        progress.onStep("Opening connection to ${Ptp.CAMERA_IP}…")
        transport.open()

        progress.onStep("Registering with camera…")
        cameraName = transport.connectInit(deviceName, clientGuid)

        // Fuji needs a short pause before OpenSession.
        Thread.sleep(60)

        progress.onStep("Opening session…")
        val open = transport.operation(Ptp.Op.OPEN_SESSION, intArrayOf(1))
        if (!open.ok) throw FujiProtocolException("OpenSession failed: ${Ptp.Resp.name(open.responseCode)}")

        // Poll the event list until the camera leaves WAIT_FOR_ACCESS (user pressed OK).
        progress.onStep("Press OK on the camera to allow the connection…")
        val deadline = System.currentTimeMillis() + acceptTimeoutMs
        var state = cameraState()
        while ((state == Ptp.Handshake.STATE_WAIT_FOR_ACCESS || state < 0) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(100)
            state = cameraState()
        }
        if (state == Ptp.Handshake.STATE_WAIT_FOR_ACCESS || state < 0) {
            throw FujiProtocolException("Timed out waiting for the camera's OK")
        }

        // Read version properties (best-effort).
        remoteVersion = runCatching { readU32Prop(Ptp.Handshake.REMOTE_VERSION) }.getOrDefault(-1)
        runCatching { readU32Prop(Ptp.Handshake.GET_OBJECT_VERSION) }
        runCatching { readU32Prop(Ptp.Handshake.IMAGE_GET_VERSION) }
        runCatching { readU32Prop(Ptp.Handshake.REMOTE_IMAGE_VIEW_VERSION) }

        // Request remote-control mode. On newer bodies this raises the on-screen accept dialog.
        progress.onStep("Switching camera to remote mode…")
        setDeviceProp(Ptp.Handshake.CLIENT_STATE, Ptp.FUJI_REMOTE_MODE.toLong(), 2)
        getEvents()

        if (remoteVersion != -1L) {
            setDeviceProp(Ptp.Handshake.REMOTE_VERSION, Ptp.FUJI_CAM_CONNECT_REMOTE_VER.toLong(), 4)
        }
        progress.onStep("Connected to ${cameraName.ifEmpty { "camera" }}.")
    }

    fun disconnect() {
        runCatching { transport.operation(Ptp.Op.CLOSE_SESSION) }
        transport.close()
    }

    // ---- Properties ----

    /** Read a property's raw value as a u32 (for version/handshake props). */
    private fun readU32Prop(code: Int): Long {
        val res = transport.operation(Ptp.Op.GET_DEVICE_PROP_VALUE, intArrayOf(code))
        if (!res.ok) throw FujiProtocolException("GetDevicePropValue($code) -> ${Ptp.Resp.name(res.responseCode)}")
        val r = res.reader()
        return if (r.hasRemaining(4)) r.u32() else 0L
    }

    /**
     * Set a device property. [sizeBytes] is the datatype width (2 for u16 props, 4 for u32).
     * Returns true on OK.
     */
    fun setDeviceProp(code: Int, value: Long, sizeBytes: Int): Boolean {
        val data = encodeLe(value, sizeBytes)
        val res = transport.operation(Ptp.Op.SET_DEVICE_PROP_VALUE, intArrayOf(code), dataOut = data)
        return res.ok
    }

    /** Read a full property description (type, writable, current value, allowed values). */
    fun getPropDesc(code: Int): PropDesc? {
        val res = transport.operation(Ptp.Op.GET_DEVICE_PROP_DESC, intArrayOf(code))
        if (!res.ok || res.data.isEmpty()) return null
        return runCatching { parsePropDesc(res.reader()) }.getOrNull()
    }

    /** Set a property using a known [PropDesc] so the correct width is used. */
    fun setProp(desc: PropDesc, value: Long): Boolean {
        val size = Ptp.Type.sizeOf(desc.dataType) ?: 4
        return setDeviceProp(desc.code, value, size)
    }

    // ---- Device info / property enumeration ----

    /**
     * Enumerate the properties the camera supports, with their descriptions. Tries the Fuji-native
     * GetDeviceInfo (0x902B, returns all descs in one shot) first, then falls back to the standard
     * GetDeviceInfo (0x1001) + per-property GetDevicePropDesc.
     */
    fun enumerateProperties(): List<PropDesc> {
        runCatching { fujiDeviceInfo() }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        return runCatching { standardDeviceInfoProps() }.getOrDefault(emptyList())
    }

    private fun fujiDeviceInfo(): List<PropDesc> {
        val res = transport.operation(Ptp.Op.FUJI_GET_DEVICE_INFO)
        if (!res.ok || res.data.isEmpty()) return emptyList()
        val r = res.reader()
        val out = ArrayList<PropDesc>()
        // Layout: u32 count, then `count` length-prefixed DevicePropDesc records.
        val count = if (r.hasRemaining(4)) r.u32().toInt() else return emptyList()
        var i = 0
        while (i < count && r.hasRemaining(4)) {
            val descLen = r.u32().toInt()
            val end = r.pos + descLen
            runCatching { parsePropDesc(r) }.getOrNull()?.let { out.add(it) }
            if (r.pos < end) r.seek(end) // resync to the next record on any slack
            i++
        }
        return out
    }

    private fun standardDeviceInfoProps(): List<PropDesc> {
        val res = transport.operation(Ptp.Op.GET_DEVICE_INFO)
        if (!res.ok || res.data.isEmpty()) return emptyList()
        val codes = parseStandardDeviceInfoPropCodes(res.reader())
        return codes.mapNotNull { getPropDesc(it) }
    }

    // ---- Events / state ----

    /** Returns the latest event list as code -> value (includes CameraState 0xDF00). */
    fun getEvents(): Map<Int, Long> {
        val res = transport.operation(Ptp.Op.GET_DEVICE_PROP_VALUE, intArrayOf(Ptp.Handshake.EVENTS_LIST))
        if (!res.ok || res.data.isEmpty()) return emptyMap()
        val r = res.reader()
        // PtpFujiEvents: u16 count, then {u16 code; u32 value} entries.
        val out = HashMap<Int, Long>()
        if (!r.hasRemaining(2)) return out
        val n = r.u16()
        var i = 0
        while (i < n && r.hasRemaining(6)) {
            val code = r.u16()
            val value = r.u32()
            out[code] = value
            i++
        }
        return out
    }

    private fun cameraState(): Int =
        getEvents()[Ptp.Handshake.CAMERA_STATE]?.toInt() ?: -1

    // ---- Capture / live view ----

    /** Trigger a still capture (shutter). */
    fun capture(): Boolean {
        // InitiateCapture(storageId=0, formatCode=0)
        val res = transport.operation(Ptp.Op.INITIATE_CAPTURE, intArrayOf(0, 0))
        return res.ok
    }

    /** Start the live-view stream path (InitiateOpenCapture). Sockets are opened by [LiveView]. */
    fun startLiveView(): Boolean {
        val res = transport.operation(Ptp.Op.INITIATE_OPEN_CAPTURE, intArrayOf(0, 0))
        getEvents()
        return res.ok
    }

    fun stopLiveView() {
        runCatching { transport.operation(Ptp.Op.TERMINATE_OPEN_CAPTURE) }
    }

    // Relative exposure controls (Fuji step opcodes). dir: +1 up, -1/0 down.
    fun stepShutter(up: Boolean) = step(Ptp.Op.FUJI_STEP_SHUTTER_SPEED, up)
    fun stepAperture(up: Boolean) = step(Ptp.Op.FUJI_STEP_FNUMBER, up)
    fun stepExposureComp(up: Boolean) = step(Ptp.Op.FUJI_STEP_EXPOSURE_BIAS, up)

    private fun step(opcode: Int, up: Boolean): Boolean =
        transport.operation(opcode, intArrayOf(if (up) 1 else 0)).ok

    // ---- parsing helpers ----

    private fun parsePropDesc(r: PtpReader): PropDesc {
        val code = r.u16()
        val type = r.u16()
        val getset = r.u8()
        val default = r.numeric(type) ?: 0L
        val current = r.numeric(type) ?: 0L
        var allowed = emptyList<Long>()
        var range: Triple<Long, Long, Long>? = null
        if (r.hasRemaining(1)) {
            when (r.u8()) {
                1 -> { // range form: min, max, step
                    val min = r.numeric(type) ?: 0L
                    val max = r.numeric(type) ?: 0L
                    val step = r.numeric(type) ?: 1L
                    range = Triple(min, max, step)
                }
                2 -> { // enum form: u16 count, then values
                    if (r.hasRemaining(2)) {
                        val n = r.u16()
                        val list = ArrayList<Long>(n)
                        var i = 0
                        while (i < n) { list.add(r.numeric(type) ?: break); i++ }
                        allowed = list
                    }
                }
            }
        }
        return PropDesc(code, type, getset == 1, current, default, allowed, range)
    }

    private fun parseStandardDeviceInfoPropCodes(r: PtpReader): List<Int> {
        // DeviceInfo: StdVersion(u16), VendorExtID(u32), VendorExtVersion(u16), VendorExtDesc(str),
        // FunctionalMode(u16), OperationsSupported(AUINT16), EventsSupported(AUINT16),
        // DevicePropertiesSupported(AUINT16), ...
        r.u16(); r.u32(); r.u16()
        skipPtpString(r)
        r.u16()
        skipAUint16(r) // operations
        skipAUint16(r) // events
        return readAUint16(r) // device properties
    }

    private fun skipPtpString(r: PtpReader) {
        if (!r.hasRemaining(1)) return
        val chars = r.u8() // number of characters incl. null terminator
        r.skip(chars * 2)
    }

    private fun skipAUint16(r: PtpReader) {
        if (!r.hasRemaining(4)) return
        val n = r.u32().toInt()
        r.skip(n * 2)
    }

    private fun readAUint16(r: PtpReader): List<Int> {
        if (!r.hasRemaining(4)) return emptyList()
        val n = r.u32().toInt()
        val out = ArrayList<Int>(n)
        var i = 0
        while (i < n && r.hasRemaining(2)) { out.add(r.u16()); i++ }
        return out
    }

    private fun encodeLe(value: Long, sizeBytes: Int): ByteArray =
        ByteArray(sizeBytes) { ((value ushr (8 * it)) and 0xFF).toByte() }

    companion object {
        /** Generate a random 16-byte client GUID, persisted per install. */
        fun randomGuid(): ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    }
}
