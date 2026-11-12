package com.fujiapp.remote.data

import android.content.Context
import com.fujiapp.remote.ble.DownloadedFile
import com.fujiapp.remote.model.BackupMeta
import com.fujiapp.remote.model.CameraInfo
import com.fujiapp.remote.model.SettingsFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera settings backups in the app's private storage: `filesDir/backups/<id>.dat` holds the
 * camera's file byte for byte, `<id>.json` the [BackupMeta].
 */
class BackupStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "backups").apply { mkdirs() }

    fun list(): List<BackupMeta> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { BackupMeta.fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?.filter { dataFile(it).exists() }
            ?.sortedByDescending { it.savedAt }
            ?: emptyList()

    fun save(name: String, camera: CameraInfo, file: DownloadedFile): BackupMeta {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) +
            "-" + Integer.toHexString((now % 0x10000).toInt())
        val meta = BackupMeta(
            id = id,
            name = name.trim().ifEmpty { "Backup" },
            cameraModel = camera.model ?: camera.modelNumber ?: "",
            cameraName = camera.cameraName ?: "",
            cameraSerial = camera.serialNumber ?: "",
            firmware = camera.firmware ?: "",
            fileName = file.name,
            size = file.data.size,
            savedAt = now,
            sha256 = sha256(file.data),
        )
        dataFile(meta).writeBytes(file.data)
        File(dir, "$id.json").writeText(meta.toJson().toString(2))
        revision++
        return meta
    }

    fun dataFile(meta: BackupMeta): File = File(dir, "${meta.id}.dat")

    private val parsed = HashMap<String, SettingsFile?>()

    /** Goes up whenever a backup is saved or deleted, so screens know to look again. */
    @Volatile
    var revision = 0
        private set

    /** What's inside a backup (the C1–C7 names), read once and remembered; null if unreadable. */
    @Synchronized
    fun settings(meta: BackupMeta): SettingsFile? = parsed.getOrPut(meta.id) {
        runCatching { SettingsFile.parse(dataFile(meta).readBytes()) }.getOrNull()
    }

    fun delete(meta: BackupMeta) {
        synchronized(this) { parsed.remove(meta.id) }
        revision++
        dataFile(meta).delete()
        File(dir, "${meta.id}.json").delete()
    }

    /** A copy in the cache dir with a readable name, for the share sheet. */
    fun exportToCache(meta: BackupMeta): File {
        val outDir = File(appContext.cacheDir, "export").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val label = listOf(meta.name, meta.cameraModel).filter { it.isNotBlank() }.joinToString(" - ")
        val out = File(outDir, safeName(label) + ".dat")
        dataFile(meta).copyTo(out, overwrite = true)
        return out
    }

    private fun safeName(name: String): String =
        name.trim().ifEmpty { "backup" }.replace(Regex("[^A-Za-z0-9._ -]+"), "_").take(80)

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
