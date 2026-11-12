package com.fujiapp.remote.ui

import android.text.format.Formatter
import android.view.View
import com.fujiapp.remote.BackupState
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.RestoreState
import com.fujiapp.remote.model.BackupMeta
import java.text.DateFormat
import java.util.Date

/** Back up the camera's settings (with the C1–C7 recipes), restore them, and manage backups. */
class BackupsScreen(private val a: MainActivity) {
    private val c = a
    private val backupButton = Kit.button(c, "Back up camera settings") { a.askBackupName() }
    private val progress = Kit.progress(c)
    private val status = Kit.text(c, null, 14f, Deco.MUTED)
    private val list = Kit.column(c)
    private val empty = Kit.text(c, "No backups yet. Connect to the camera and back up its settings.", 14f, Deco.MUTED)
    private var shownDone: BackupMeta? = null
    private var restoreAllowed = false
    private var items: List<BackupMeta> = emptyList()

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.label(c, "Recipes", Deco.DIM), Kit.lp(top = Deco.dpi(c, 12)))
        addView(Kit.text(c, "Backups", 30f), Kit.lp(top = Deco.dpi(c, 12)))
        addView(
            Kit.text(
                c,
                "A backup is the camera's own settings file: every setting, including your C1–C7 custom " +
                    "settings with your film-simulation recipes. Restoring puts all of them back, onto the same model.",
                14f, Deco.MUTED,
            ),
            Kit.lp(top = Deco.dpi(c, 14)),
        )
        addView(backupButton, Kit.lp(top = Deco.dpi(c, 24)))
        addView(progress, Kit.lp(top = Deco.dpi(c, 12)))
        addView(status, Kit.lp(top = Deco.dpi(c, 10)))
        addView(Kit.sectionTitle(c, "Saved on this phone"))
        addView(empty)
        addView(list)
    })

    fun refreshList() {
        items = a.session.store.list()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        drawList()
    }

    fun render(conn: ConnState, backup: BackupState, restore: RestoreState) {
        val connected = conn as? ConnState.Connected
        val running = backup is BackupState.Running || restore is RestoreState.Running
        Kit.setEnabled(backupButton, connected?.capabilities?.backup == true && !running && !a.session.isTransferring)

        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (restore is RestoreState.Running && restore.total > 0) {
            progress.isIndeterminate = false
            progress.setProgressCompat(restore.sent * 100 / restore.total, true)
        } else if (backup is BackupState.Running) {
            progress.isIndeterminate = true
        }

        val text = when {
            restore is RestoreState.Running ->
                "Restoring \"${restore.name}\"… ${if (restore.total > 0) restore.sent * 100 / restore.total else 0}%"
            restore is RestoreState.Done -> "Restored \"${restore.name}\". The camera may restart to apply the settings."
            restore is RestoreState.Failed -> "Restore failed: ${restore.message}"
            backup is BackupState.Running -> "Receiving backup… ${Formatter.formatShortFileSize(c, backup.bytes.toLong())}"
            backup is BackupState.Done -> "Saved \"${backup.meta.name}\" (${Formatter.formatShortFileSize(c, backup.meta.size.toLong())})"
            backup is BackupState.Failed -> "Backup failed: ${backup.message}"
            connected == null -> "Connect to the camera to back up or restore."
            !connected.capabilities.backup -> "This camera doesn't offer a settings backup over Bluetooth."
            else -> null
        }
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        status.setTextColor(if (backup is BackupState.Failed || restore is RestoreState.Failed) Deco.RED else Deco.MUTED)

        val allowed = connected != null && !running && !a.session.isTransferring
        if (allowed != restoreAllowed) {
            restoreAllowed = allowed
            drawList()
        }
        if (backup is BackupState.Done && shownDone != backup.meta) {
            shownDone = backup.meta
            refreshList()
        }
    }

    private fun drawList() {
        list.removeAllViews()
        val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (meta in items) {
            list.addView(Kit.panel(c, padV = 16).apply {
                addView(Kit.text(c, meta.name, 17f, Deco.TEXT, bold = true))
                addView(
                    Kit.label(
                        c,
                        listOf(
                            meta.cameraModel,
                            meta.firmware.takeIf { it.isNotBlank() }?.let { "FW $it" },
                            Formatter.formatShortFileSize(c, meta.size.toLong()),
                        ).filter { !it.isNullOrBlank() }.joinToString(" · "),
                        Deco.DIM, 10f,
                    ),
                    Kit.lp(top = Deco.dpi(c, 8)),
                )
                addView(Kit.text(c, dates.format(Date(meta.savedAt)), 13f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 6)))
                val recipes = a.session.store.settings(meta)?.customSlots.orEmpty().filter { it.name.isNotBlank() }
                if (recipes.isNotEmpty()) {
                    addView(
                        Kit.text(c, recipes.joinToString("  ·  ") { "${it.label} ${it.name}" }, 13f, Deco.TEXT_SOFT),
                        Kit.lp(top = Deco.dpi(c, 10)),
                    )
                }
                addView(Kit.row(c).apply {
                    val restore = Kit.button(c, "Restore", Kit.Style.SECONDARY) { a.confirmRestore(meta) }
                    Kit.setEnabled(restore, restoreAllowed)
                    addView(restore, Kit.lp(0, weight = 1f).apply { marginEnd = Deco.dpi(c, 8) })
                    addView(Kit.button(c, "Share", Kit.Style.QUIET) { a.shareBackup(meta) })
                    addView(Kit.button(c, "Delete", Kit.Style.QUIET) { a.confirmDelete(meta) })
                }, Kit.lp(top = Deco.dpi(c, 14)))
            }, Kit.lp(bottom = Deco.dpi(c, 12)))
        }
    }
}
