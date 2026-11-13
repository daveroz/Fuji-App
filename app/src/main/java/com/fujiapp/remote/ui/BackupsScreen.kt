package com.fujiapp.remote.ui

import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.fujiapp.remote.BackupState
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.RestoreState
import com.fujiapp.remote.model.BackupMeta
import java.text.DateFormat
import java.util.Date

/** Back up the camera's settings (with the C1–C7 recipes), restore them, and manage backups. */
class BackupsScreen(private val a: MainActivity) {
    private companion object {
        const val CARD_PAD = 18
    }

    private val c = a
    private val backupButton = Kit.button(c, "Back up now", icon = LineIcon.Kind.ARCHIVE) { a.askBackupName() }
    private val count = Kit.text(c, "Backups", 28f, Deco.TEXT, bold = true)
    private val progress = Kit.progress(c)
    private val status = Kit.text(c, null, 14f, Deco.MUTED)
    private val list = Kit.column(c)
    private val empty = Kit.text(c, "No backups yet. Connect to the camera and back up its settings, or import a backup file.", 14f, Deco.MUTED)
    private var shownDone: BackupMeta? = null
    private var restoreAllowed = false
    private var items: List<BackupMeta> = emptyList()

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.row(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Kit.column(c).apply {
                addView(Kit.label(c, "Backups", Deco.AMBER_LIGHT, 10f))
                addView(count, Kit.lp(top = Deco.dpi(c, 8)))
            }, Kit.lp(0, weight = 1f))
            addView(Kit.button(c, "Import", Kit.Style.SECONDARY, LineIcon.Kind.DOWNLOAD) { a.pickBackupToImport() }.apply {
                minHeight = Deco.dpi(c, 42)
                setPadding(Deco.dpi(c, 16), 0, Deco.dpi(c, 18), 0)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            }, Kit.lp(Kit.WRAP))
        }, Kit.lp(top = Deco.dpi(c, 12)))
        addView(
            Kit.text(
                c,
                "A backup is the camera's own settings file: every setting, including your C1–C7 custom " +
                    "settings with your film-simulation recipes. Restoring puts all of them back, onto the same model.",
                13f, Deco.MUTED,
            ),
            Kit.lp(top = Deco.dpi(c, 12)),
        )
        addView(backupButton, Kit.lp(top = Deco.dpi(c, 18)))
        addView(progress, Kit.lp(top = Deco.dpi(c, 12)))
        addView(status, Kit.lp(top = Deco.dpi(c, 10)))
        addView(empty, Kit.lp(top = Deco.dpi(c, 16)))
        addView(list, Kit.lp(top = Deco.dpi(c, 14)))
    })

    fun refreshList() {
        items = a.session.store.list()
        count.text = if (items.isEmpty()) "None saved" else "${items.size} saved"
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
        val times = DateFormat.getTimeInstance(DateFormat.SHORT)
        val months = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
        val dayOfMonth = java.text.SimpleDateFormat("dd", java.util.Locale.getDefault())
        // Each backup as a ticket: the date set large on the left, what and when beside it, a
        // tear line, the recipes inside as chips, then restoring it.
        for ((n, meta) in items.withIndex()) {
            val saved = Date(meta.savedAt)
            list.addView(Kit.column(c).apply {
                contentDescription = DateFormat.getDateInstance(DateFormat.MEDIUM).format(saved)
                background = Deco.glass(c, 16f, if (n == 0) GlassDrawable.Tone.WARM else GlassDrawable.Tone.PANE)
                setPadding(Deco.dpi(c, CARD_PAD), Deco.dpi(c, 16), Deco.dpi(c, CARD_PAD), Deco.dpi(c, 16))
                addView(Kit.row(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Kit.column(c).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(Kit.text(c, dayOfMonth.format(saved), 26f, Deco.TEXT, bold = true))
                        addView(Kit.label(c, months.format(saved), Deco.DIM, 8f), Kit.lp(Kit.WRAP, top = Deco.dpi(c, 4)))
                    }, LinearLayout.LayoutParams(Deco.dpi(c, 50), Kit.WRAP).apply { marginEnd = Deco.dpi(c, 12) })
                    addView(Kit.column(c).apply {
                        addView(Kit.text(c, meta.name, 15f, Deco.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(Kit.label(c, listOf(times.format(saved), Formatter.formatShortFileSize(c, meta.size.toLong()), meta.cameraModel.ifBlank { null }, meta.firmware.ifBlank { null }?.let { "fw $it" })
                            .filterNotNull().joinToString(" · "), Deco.DIM, 8f).apply { maxLines = 1 }, Kit.lp(top = Deco.dpi(c, 6)))
                    }, Kit.lp(0, weight = 1f))
                    addView(Kit.iconButton(c, LineIcon.Kind.SHARE, "Share", 40) { a.shareBackup(meta) }, LinearLayout.LayoutParams(Deco.dpi(c, 40), Deco.dpi(c, 40)).apply { marginStart = Deco.dpi(c, 8) })
                    addView(Kit.iconButton(c, LineIcon.Kind.TRASH, "Delete", 40) { a.confirmDelete(meta) }, LinearLayout.LayoutParams(Deco.dpi(c, 40), Deco.dpi(c, 40)).apply { marginStart = Deco.dpi(c, 8) })
                })
                addView(TicketDivider(c), LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 18)).apply {
                    topMargin = Deco.dpi(c, 14)
                    marginStart = -Deco.dpi(c, CARD_PAD)
                    marginEnd = -Deco.dpi(c, CARD_PAD)
                })
                val recipes = a.session.store.settings(meta)?.customSlots.orEmpty().filter { it.name.isNotBlank() }
                addView(FlowLayout(c, Deco.dpi(c, 6), Deco.dpi(c, 6)).apply {
                    if (recipes.isEmpty()) addView(Kit.label(c, "No custom recipes", Deco.DIM, 8f))
                    recipes.forEach { r ->
                        addView(Kit.label(c, "${r.label} ${r.name}", Deco.TEXT_SOFT, 8f).apply {
                            gravity = Gravity.CENTER
                            minHeight = Deco.dpi(c, 26)
                            setPadding(Deco.dpi(c, 10), 0, Deco.dpi(c, 10), 0)
                            background = Deco.glass(c, -1f, GlassDrawable.Tone.WELL)
                        })
                    }
                }, Kit.lp(top = Deco.dpi(c, 12)))
                val restore = Kit.button(c, "Restore to the camera", Kit.Style.SECONDARY, LineIcon.Kind.UPLOAD) { a.confirmRestore(meta) }
                Kit.setEnabled(restore, restoreAllowed)
                addView(restore, Kit.lp(top = Deco.dpi(c, 14)))
            }, Kit.lp(bottom = Deco.dpi(c, 12)))
        }
    }
}
