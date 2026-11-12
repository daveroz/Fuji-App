package com.fujiapp.remote.model

import org.json.JSONObject

/**
 * A camera settings backup: the camera's own backup file (all settings, including the C1–C7
 * custom settings that hold your film-simulation recipes), exactly as it sent it, plus where it
 * came from. A backup only restores to the same camera model, so the model is kept with it.
 */
data class BackupMeta(
    val id: String,
    val name: String,
    val cameraModel: String,
    val cameraName: String,
    val cameraSerial: String,
    val firmware: String,
    /** The file name the camera reported, e.g. "backup.dat". */
    val fileName: String,
    val size: Int,
    val savedAt: Long,
    val sha256: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT_VERSION)
        put("id", id)
        put("name", name)
        put("cameraModel", cameraModel)
        put("cameraName", cameraName)
        put("cameraSerial", cameraSerial)
        put("firmware", firmware)
        put("fileName", fileName)
        put("size", size)
        put("savedAt", savedAt)
        put("sha256", sha256)
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun fromJson(o: JSONObject): BackupMeta = BackupMeta(
            id = o.getString("id"),
            name = o.optString("name", "Backup"),
            cameraModel = o.optString("cameraModel", ""),
            cameraName = o.optString("cameraName", ""),
            cameraSerial = o.optString("cameraSerial", ""),
            firmware = o.optString("firmware", ""),
            fileName = o.optString("fileName", "backup.dat"),
            size = o.optInt("size", 0),
            savedAt = o.optLong("savedAt", 0L),
            sha256 = o.optString("sha256", ""),
        )
    }
}
