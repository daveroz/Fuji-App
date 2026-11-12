package com.fujiapp.remote

import com.fujiapp.remote.ble.PowerState
import com.fujiapp.remote.model.BackupMeta
import com.fujiapp.remote.model.CameraInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test fun backup_metadata_round_trips() {
        val meta = BackupMeta(
            id = "20261112-101500", name = "Summer recipes", cameraModel = "X100VI",
            cameraName = "FUJIFILM-X100VI-1A2B", cameraSerial = "51000002", firmware = "01.32",
            fileName = "backup.dat", size = 33404, savedAt = 1_700_000_000_000L, sha256 = "ab12",
        )
        assertEquals(meta, BackupMeta.fromJson(meta.toJson()))
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
