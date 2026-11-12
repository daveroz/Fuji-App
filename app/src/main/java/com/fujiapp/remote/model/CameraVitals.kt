package com.fujiapp.remote.model

import org.json.JSONObject
import java.util.Locale

/**
 * The camera's "vital data": shutter and usage counters it sends as a JSON file over Bluetooth
 * (XApp's equipment status). Field names follow XApp's `VitalFileEntity`. Any field may be
 * missing on a body.
 */
data class CameraVitals(
    val totalShots: Long? = null,
    /** XApp compares this one against [shutterLimit] for its shutter-life gauge. */
    val mechanicalShutter: Long? = null,
    val frontCurtain: Long? = null,
    val electronicShutter: Long? = null,
    /** The shutter's rated life, e.g. 500 000. */
    val shutterLimit: Long? = null,
    val shutterReplacements: Long? = null,
    val powerOns: Long? = null,
    val flashFired: Long? = null,
    val operatingSeconds: Long? = null,
    val shootingSeconds: Long? = null,
    val viewfinderSeconds: Long? = null,
    /** Battery wear 0–100 for the body battery; XApp warns at 100. */
    val batteryWear: Int? = null,
    val lensChanges: Long? = null,
    val usbConnections: Long? = null,
    val sensorCleanings: Long? = null,
    val overheatWarnings: Long? = null,
    val lastSensorCleaning: String? = null,
    val bodySerial: String? = null,
) {
    /** The mechanical shutter count XApp shows, as a share of the rated life, or null. */
    val shutterLifeUsedPercent: Int?
        get() {
            val used = mechanicalShutter ?: return null
            val limit = shutterLimit?.takeIf { it > 0 } ?: return null
            return ((used * 100) / limit).toInt()
        }

    fun rows(locale: Locale = Locale.getDefault()): List<CameraInfo.Row> {
        fun n(v: Long?) = v?.let { String.format(locale, "%,d", it) }
        fun hours(s: Long?) = s?.let { String.format(locale, "%,d h %02d min", it / 3600, (it % 3600) / 60) }
        val limit = shutterLimit?.takeIf { it > 0 }?.let { l ->
            n(l) + (shutterLifeUsedPercent?.let { " ($it% used)" } ?: "")
        }
        return listOf(
            "Total shots" to n(totalShots),
            "Mechanical shutter" to n(mechanicalShutter),
            "Electronic shutter" to n(electronicShutter),
            "Front curtain" to n(frontCurtain),
            "Rated shutter life" to limit,
            "Shutter replacements" to n(shutterReplacements),
            "Power-ons" to n(powerOns),
            "Flash fired" to n(flashFired),
            "Operating time" to hours(operatingSeconds),
            "Shooting time" to hours(shootingSeconds),
            "Viewfinder time" to hours(viewfinderSeconds),
            "Battery wear" to batteryWear?.let { "$it%" },
            "Lens changes" to n(lensChanges),
            "USB connections" to n(usbConnections),
            "Sensor cleanings" to n(sensorCleanings),
            "Overheat warnings" to n(overheatWarnings),
            "Last sensor cleaning" to lastSensorCleaning,
        ).mapNotNull { (label, value) -> value?.ifBlank { null }?.let { CameraInfo.Row(label, it) } }
    }

    companion object {
        fun parse(json: String): CameraVitals {
            val o = JSONObject(json.trim().trimEnd('\u0000'))
            fun long(key: String): Long? = if (o.has(key) && !o.isNull(key)) o.optLong(key) else null
            fun text(key: String): String? = o.optString(key, "").ifBlank { null }
            val battery = o.optJSONObject("battery_age")
            return CameraVitals(
                totalShots = long("total_shot_count"),
                mechanicalShutter = long("rear_curtain_shutter_count"),
                frontCurtain = long("front_curtain_shutter_count"),
                electronicShutter = long("electronic_shutter_count"),
                shutterLimit = long("shutter_upper_limit_count"),
                shutterReplacements = long("shutter_exchange_count"),
                powerOns = long("start_count"),
                flashFired = long("flash_count"),
                operatingSeconds = long("total_operation_second"),
                shootingSeconds = long("shooting_mode_operation_second"),
                viewfinderSeconds = long("evf_total_display_second"),
                batteryWear = battery?.takeIf { it.has("body") }?.optInt("body"),
                lensChanges = long("lens_attachment_count"),
                usbConnections = long("usb_insertion_count"),
                sensorCleanings = long("dust_reduction_count"),
                overheatWarnings = long("temp_warning_count"),
                lastSensorCleaning = text("cmos_cleaning_datetime"),
                bodySerial = text("body_serial_number"),
            )
        }
    }
}
