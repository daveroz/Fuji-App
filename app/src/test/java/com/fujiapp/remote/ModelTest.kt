package com.fujiapp.remote

import com.fujiapp.remote.ble.PowerState
import com.fujiapp.remote.model.BackupMeta
import com.fujiapp.remote.model.CameraInfo
import com.fujiapp.remote.model.CameraVitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ModelTest {

    @Test fun backup_metadata_round_trips() {
        val meta = BackupMeta(
            id = "20261112-101500", name = "Summer recipes", cameraModel = "X100VI",
            cameraName = "FUJIFILM-X100VI-1A2B", cameraSerial = "51000002", firmware = "01.32",
            fileName = "backup.dat", size = 33404, savedAt = 1_700_000_000_000L, sha256 = "ab12",
        )
        assertEquals(meta, BackupMeta.fromJson(meta.toJson()))
    }

    @Test fun vital_data_parses_xapps_fields() {
        // Shape of the camera's vital JSON (tiredboffin/fffw ffbt/camera/vitals.json), with values filled in.
        val json = """
            {"body_serial_number":"51000002","start_count":1234,"total_shot_count":15000,"flash_count":12,
             "total_operation_second":45296,"shooting_mode_operation_second":3600,"evf_total_display_second":60,
             "front_curtain_shutter_count":0,"rear_curtain_shutter_count":10000,"shutter_exchange_count":0,
             "electronic_shutter_count":5000,"shutter_upper_limit_count":500000,
             "cmos_cleaning_datetime":"2025-03-01T00:00:00+04:00","battery_age":{"body":37},"error_log":""}
        """.trimIndent() + "\u0000"
        val v = CameraVitals.parse(json)
        assertEquals(15000L, v.totalShots)
        assertEquals(10000L, v.mechanicalShutter)
        assertEquals(5000L, v.electronicShutter)
        assertEquals(500000L, v.shutterLimit)
        assertEquals(2, v.shutterLifeUsedPercent)
        assertEquals(37, v.batteryWear)
        assertEquals("51000002", v.bodySerial)

        val rows = v.rows(Locale.US).associate { it.label to it.value }
        assertEquals("15,000", rows["Total shots"])
        assertEquals("500,000 (2% used)", rows["Rated shutter life"])
        assertEquals("12 h 34 min", rows["Operating time"])
        assertEquals("37%", rows["Battery wear"])
        assertEquals(null, rows["Lens changes"]) // absent in the JSON, so not shown
    }

    @Test fun x100vi_vital_data_without_optional_fields() {
        // The keys an X100VI 1.31 sends (no front curtain, no battery_age); the values are made up.
        val json = """
            {"start_count":10,"total_shot_count":1200,"flash_count":0,"total_operation_second":7200,
             "shooting_mode_operation_second":3600,"ok_switch_count":5,"s1_switch_count":9,"zoom_drive_steps":0,
             "battery_end_detection":0,"usb_insertion_count":1,"dust_reduction_count":2,"lens_attachment_count":0,
             "cmos_temp_performance":0,"cmos_temp_function":0,"cmos_temp_unguaranteed":0,"variable_finder_switching":3,
             "command_dial_rotation":40,"command_dial_push":1,"evf_total_display_second":60,
             "rear_curtain_shutter_count":1500,"shutter_exchange_count":0,"gps_positioning_second":0,
             "electronic_shutter_count":0,"shutter_upper_limit_count":100000,
             "cmos_cleaning_datetime":"","sensor_dust_check_datetime":"","temp_warning_count":0,
             "temp_warning_power_off_count":0,"error_log":""}
        """.trimIndent()
        val v = CameraVitals.parse(json)
        val rows = v.rows(Locale.US).associate { it.label to it.value }
        assertEquals("1,200", rows["Total shots"])
        assertEquals("1,500", rows["Shutter actuations"])
        assertEquals("100,000 (1% used)", rows["Rated shutter life"])
        assertEquals(null, rows["Front curtain"])
        assertEquals(null, rows["Battery wear"])
        assertEquals(null, rows["Last sensor cleaning"]) // empty string in the JSON
    }

    @Test fun camera_info_hides_missing_fields_and_empty_sections() {
        val info = CameraInfo(model = "X100VI", cameraName = "FUJIFILM-X100VI-1A2B", firmware = "01.32", power = PowerState.ON)
        val sections = info.sections()
        assertEquals(listOf("Camera", "Status"), sections.map { it.title })
        assertEquals(listOf("Model", "Name", "Firmware"), sections[0].rows.map { it.label })
        assertEquals("On", sections[1].rows.single().value)
        assertEquals("X100VI-1A2B", info.displayName)
        assertTrue(CameraInfo().sections().isEmpty())
        assertEquals("Fujifilm camera", CameraInfo().displayName)
    }
}
