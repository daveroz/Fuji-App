package com.fujiapp.remote.model

import com.fujiapp.remote.ble.PowerState

/** Everything the camera tells us over Bluetooth about itself. Any field may be missing on a body. */
data class CameraInfo(
    /** GAP device name: the model only, e.g. "X100VI". */
    val model: String? = null,
    /** The camera's NAME setting, e.g. "FUJIFILM-X100VI-1A2B". */
    val cameraName: String? = null,
    val manufacturer: String? = null,
    /** Fujifilm model code from Device Information, e.g. "FF230003". */
    val modelNumber: String? = null,
    val serialNumber: String? = null,
    val firmware: String? = null,
    val hardwareRevision: String? = null,
    val softwareRevision: String? = null,
    val bleProtocolVersion: Int? = null,
    val macAddress: String? = null,
    val lensName: String? = null,
    val lensFirmware: String? = null,
    val power: PowerState? = null,
    val connectWhilePowerOff: Boolean? = null,
    val pairedPhones: Int? = null,
) {
    /** A short name for headers: the NAME setting without the "FUJIFILM-" prefix, else the model. */
    val displayName: String
        get() = cameraName?.removePrefix("FUJIFILM-")?.ifBlank { null } ?: model ?: "Fujifilm camera"

    data class Row(val label: String, val value: String)
    data class Section(val title: String, val rows: List<Row>)

    /** Display sections, leaving out anything the camera did not report. */
    fun sections(): List<Section> {
        fun rows(vararg pairs: Pair<String, Any?>): List<Row> =
            pairs.mapNotNull { (label, value) -> value?.toString()?.ifBlank { null }?.let { Row(label, it) } }

        return listOf(
            Section(
                "Camera",
                rows(
                    "Model" to model,
                    "Name" to cameraName,
                    "Serial number" to serialNumber,
                    "Firmware" to firmware,
                    "Manufacturer" to manufacturer,
                    "Model code" to modelNumber,
                ),
            ),
            Section("Lens", rows("Lens" to lensName, "Lens firmware" to lensFirmware)),
            Section(
                "Status",
                rows(
                    "Power" to power?.label,
                    "Connect while power off" to connectWhilePowerOff?.let { if (it) "On" else "Off" },
                    "Paired phones" to pairedPhones,
                ),
            ),
            Section(
                "Technical",
                rows(
                    "Hardware revision" to hardwareRevision,
                    "Software revision" to softwareRevision,
                    "Bluetooth protocol" to bleProtocolVersion,
                    "MAC address" to macAddress,
                ),
            ),
        ).filter { it.rows.isNotEmpty() }
    }
}
