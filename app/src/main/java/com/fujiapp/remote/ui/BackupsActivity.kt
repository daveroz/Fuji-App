package com.fujiapp.remote.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fujiapp.remote.App
import com.fujiapp.remote.BackupState
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.R
import com.fujiapp.remote.RestoreState
import com.fujiapp.remote.databinding.ActivityBackupsBinding
import com.fujiapp.remote.databinding.ItemBackupBinding
import com.fujiapp.remote.model.BackupMeta
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Back up the camera's settings (with the C1–C7 recipes), restore them, and manage backups. */
class BackupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupsBinding
    private val session: CameraSession get() = App.of(application)
    private val adapter = BackupAdapter()
    private var shownDone: BackupMeta? = null
    private var canRestore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.backupButton.setOnClickListener { askName() }
        session.clearTransferResults()
        refresh()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.state, session.backup, session.restore) { c, b, r -> Triple(c, b, r) }
                    .collect { (conn, backup, restore) -> render(conn, backup, restore) }
            }
        }
    }

    private fun render(conn: ConnState, backup: BackupState, restore: RestoreState) {
        val connected = conn as? ConnState.Connected
        val backupRunning = backup is BackupState.Running
        val restoreRunning = restore is RestoreState.Running
        val busy = backupRunning || restoreRunning || session.isTransferring

        binding.backupButton.isEnabled = connected?.capabilities?.backup == true && !busy
        binding.backupProgress.visibility = if (backupRunning || restoreRunning) View.VISIBLE else View.GONE
        if (restore is RestoreState.Running && restore.total > 0) {
            binding.backupProgress.isIndeterminate = false
            binding.backupProgress.setProgressCompat(restore.sent * 100 / restore.total, true)
        } else if (backupRunning) {
            binding.backupProgress.isIndeterminate = true
        }

        val status = when {
            restore is RestoreState.Running ->
                getString(R.string.restore_running, restore.name, if (restore.total > 0) restore.sent * 100 / restore.total else 0)
            restore is RestoreState.Done -> getString(R.string.restore_done, restore.name)
            restore is RestoreState.Failed -> getString(R.string.restore_failed, restore.message)
            backup is BackupState.Running ->
                getString(R.string.backup_running, Formatter.formatShortFileSize(this, backup.bytes.toLong()))
            backup is BackupState.Done ->
                getString(R.string.backup_done, backup.meta.name, Formatter.formatShortFileSize(this, backup.meta.size.toLong()))
            backup is BackupState.Failed -> getString(R.string.backup_failed, backup.message)
            connected == null -> getString(R.string.not_connected_hint)
            !connected.capabilities.backup -> getString(R.string.backup_unsupported)
            else -> null
        }
        binding.backupStatus.text = status
        binding.backupStatus.visibility = if (status == null) View.GONE else View.VISIBLE

        val restoreAllowed = connected != null && !busy
        if (restoreAllowed != canRestore) {
            canRestore = restoreAllowed
            adapter.refreshButtons()
        }
        if (backup is BackupState.Done && shownDone != backup.meta) {
            shownDone = backup.meta
            refresh()
        }
    }

    private fun refresh() {
        val items = session.store.list()
        adapter.submit(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun askName() {
        val info = (session.state.value as? ConnState.Connected)?.info
        val default = listOfNotNull(
            info?.model,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()),
        ).joinToString(" ")
        val input = EditText(this).apply {
            hint = getString(R.string.backup_name_hint)
            setText(default)
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_now)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                if (!session.startBackup(input.text.toString().ifBlank { default })) {
                    Toast.makeText(this, R.string.busy_transfer, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRestore(meta: BackupMeta) {
        if (session.isTransferring) {
            Toast.makeText(this, R.string.busy_transfer, Toast.LENGTH_SHORT).show()
            return
        }
        session.restoreBlocker(meta)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.restore_confirm_title, meta.name))
            .setMessage(R.string.restore_confirm_body)
            .setPositiveButton(R.string.restore) { _, _ ->
                if (!session.startRestore(meta) && session.isTransferring) {
                    Toast.makeText(this, R.string.busy_transfer, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun share(meta: BackupMeta) {
        val file = session.store.exportToCache(meta)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, meta.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun delete(meta: BackupMeta) {
        AlertDialog.Builder(this)
            .setTitle(meta.name)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                session.store.delete(meta)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class BackupAdapter : RecyclerView.Adapter<BackupAdapter.VH>() {
        private val items = ArrayList<BackupMeta>()
        private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        fun submit(list: List<BackupMeta>) {
            items.clear()
            items.addAll(list)
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        fun refreshButtons() = notifyItemRangeChanged(0, items.size)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemBackupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemBackupBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(meta: BackupMeta) {
                b.name.text = meta.name
                b.subtitle.text = listOf(
                    meta.cameraModel,
                    meta.firmware.takeIf { it.isNotBlank() }?.let { "FW $it" },
                    Formatter.formatShortFileSize(this@BackupsActivity, meta.size.toLong()),
                    dateFormat.format(Date(meta.savedAt)),
                ).filter { !it.isNullOrBlank() }.joinToString(" · ")
                b.restoreButton.isEnabled = canRestore
                b.restoreButton.setOnClickListener { confirmRestore(meta) }
                b.shareButton.setOnClickListener { share(meta) }
                b.deleteButton.setOnClickListener { delete(meta) }
            }
        }
    }
}
