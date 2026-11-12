package com.fujiapp.remote.model

import org.json.JSONObject
import java.util.Locale

/**
 * The camera's "vital data": the shutter and usage counters it sends as a JSON file over
 * Bluetooth (XApp's equipment status). Field names follow XApp's `VitalFileEntity`; any field may
 * be missing on a body (an X100VI 1.31 sends no front-curtain count and no battery age).
 */
data class CameraVitals(
    val totalShots: Long? = null,
    /** XApp compares this one against [shutterLimit] for its shutter-life gauge. */
    val mechanicalShutter: Long? = null,
    val frontCurtain: Long? = null,
    val electronicShutter: Long? = null,
    /** The shutter's rated life, e.g. 100 000. */
    val shutterLimit: Long? = null,
    val shutterReplacements: Long? = null,
    val powerOns: Long? = null,
    val flashFired: Long? = null,
    val operatingSeconds: Long? = null,
    val shootingSeconds: Long? = null,
    val viewfinderSeconds: Long? = null,
    val viewfinderSwitches: Long? = null,
    val gpsSeconds: Long? = null,
    val halfPresses: Long? = null,
    val okPresses: Long? = null,
    val dialTurns: Long? = null,
    val dialPresses: Long? = null,
    val zoomSteps: Long? = null,
    /** Battery wear 0–100 for the body battery; XApp warns at 100. */
    val batteryWear: Int? = null,
    val batteryEndDetections: Long? = null,
    val lensChanges: Long? = null,
    val usbConnections: Long? = null,
    val sensorCleanings: Long? = null,
    val sensorHeatPerformance: Long? = null,
    val sensorHeatFunction: Long? = null,
    val sensorHeatBeyondRated: Long? = null,
    val overheatWarnings: Long? = null,
    val overheatShutdowns: Long? = null,
    val lastSensorCleaning: String? = null,
    val lastDustCheck: String? = null,
    val errorLog: String? = null,
    val bodySerial: String? = null,
) {
    /** The shutter actuations XApp shows, as a share of the rated life, or null. */
    val shutterLifeUsedPercent: Int?
        get() {
            val used = mechanicalShutter ?: return null
            val limit = shutterLimit?.takeIf { it > 0 } ?: return null
            return ((used * 100) / limit).toInt()
        }

    /** Every counter, grouped for display. Missing values are left out. */
    fun sections(locale: Locale = Locale.getDefault()): List<CameraInfo.Section> {
        fun n(v: Long?) = v?.let { String.format(locale, "%,d", it) }
        fun hours(s: Long?) = s?.let { String.format(locale, "%,d h %02d min", it / 3600, (it % 3600) / 60) }
        fun date(s: String?) = s?.ifBlank { null }?.substringBefore('T')
        fun rows(vararg pairs: Pair<String, String?>) =
            pairs.mapNotNull { (label, value) -> value?.ifBlank { null }?.let { CameraInfo.Row(label, it) } }
        val limit = shutterLimit?.takeIf { it > 0 }?.let { l -> n(l) + (shutterLifeUsedPercent?.let { " ($it% used)" } ?: "") }

        return listOf(
            CameraInfo.Section(
                "Shutter",
                rows(
                    "Total shots" to n(totalShots),
                    // The X100VI's leaf shutter also moves when no picture is taken, so this exceeds the shots.
                    "Shutter actuations" to n(mechanicalShutter),
                    "Electronic shutter" to n(electronicShutter),
                    "Front curtain" to n(frontCurtain),
                    "Rated shutter life" to limit,
                    "Shutter replacements" to n(shutterReplacements),
                ),
            ),
            CameraInfo.Section(
                "Usage",
                rows(
                    "Power-ons" to n(powerOns),
                    "Operating time" to hours(operatingSeconds),
                    "Shooting time" to hours(shootingSeconds),
                    "Viewfinder time" to hours(viewfinderSeconds),
                    "Viewfinder switches" to n(viewfinderSwitches),
                    "Flash fired" to n(flashFired),
                    "GPS time" to hours(gpsSeconds),
                ),
            ),
            CameraInfo.Section(
                "Controls",
                rows(
                    "Shutter half-presses" to n(halfPresses),
                    "OK button presses" to n(okPresses),
                    "Command dial turns" to n(dialTurns),
                    "Command dial presses" to n(dialPresses),
                    "Zoom steps" to n(zoomSteps),
                ),
            ),
            CameraInfo.Section(
                "Sensor and heat",
                rows(
                    "Sensor cleanings" to n(sensorCleanings),
                    "Last sensor cleaning" to date(lastSensorCleaning),
                    "Last dust check" to date(lastDustCheck),
                    "Heat warnings" to n(overheatWarnings),
                    "Heat shutdowns" to n(overheatShutdowns),
                    "Sensor hot · performance" to n(sensorHeatPerformance),
                    "Sensor hot · function" to n(sensorHeatFunction),
                    "Sensor hot · beyond rating" to n(sensorHeatBeyondRated),
                ),
            ),
            CameraInfo.Section(
                "Battery and connections",
                rows(
                    "Battery wear" to batteryWear?.let { "$it%" },
                    "Battery run flat" to n(batteryEndDetections),
                    "USB connections" to n(usbConnections),
                    "Lens changes" to n(lensChanges),
                ),
            ),
            CameraInfo.Section("Error log", rows("Errors" to errorLog?.trim())),
        ).filter { it.rows.isNotEmpty() }
    }

    /** All rows of [sections], for a flat list. */
    fun rows(locale: Locale = Locale.getDefault()): List<CameraInfo.Row> = sections(locale).flatMap { it.rows }

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
                viewfinderSwitches = long("variable_finder_switching"),
                gpsSeconds = long("gps_positioning_second"),
                halfPresses = long("s1_switch_count"),
                okPresses = long("ok_switch_count"),
                dialTurns = long("command_dial_rotation"),
                dialPresses = long("command_dial_push"),
                zoomSteps = long("zoom_drive_steps"),
                batteryWear = battery?.takeIf { it.has("body") }?.optInt("body"),
                batteryEndDetections = long("battery_end_detection"),
                lensChanges = long("lens_attachment_count"),
                usbConnections = long("usb_insertion_count"),
                sensorCleanings = long("dust_reduction_count"),
                sensorHeatPerformance = long("cmos_temp_performance"),
                sensorHeatFunction = long("cmos_temp_function"),
                sensorHeatBeyondRated = long("cmos_temp_unguaranteed"),
                overheatWarnings = long("temp_warning_count"),
                overheatShutdowns = long("temp_warning_power_off_count"),
                lastSensorCleaning = text("cmos_cleaning_datetime"),
                lastDustCheck = text("sensor_dust_check_datetime"),
                errorLog = text("error_log"),
                bodySerial = text("body_serial_number"),
            )
        }
    }
}
