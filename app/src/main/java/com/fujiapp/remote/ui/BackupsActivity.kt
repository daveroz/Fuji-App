package com.fujiapp.remote.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
import com.fujiapp.remote.databinding.ActivityBackupsBinding
import com.fujiapp.remote.databinding.ItemBackupBinding
import com.fujiapp.remote.model.BackupMeta
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Back up the camera's settings (with the C1–C7 recipes) and manage saved backups. */
class BackupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupsBinding
    private val session: CameraSession get() = App.of(application)
    private val adapter = BackupAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.backupButton.setOnClickListener { askName() }
        session.clearBackupState()
        refresh()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.state, session.backup) { conn, backup -> conn to backup }.collect { (conn, backup) ->
                    render(conn, backup)
                }
            }
        }
    }

    private var shownDone: BackupMeta? = null

    private fun render(conn: ConnState, backup: BackupState) {
        val connected = conn as? ConnState.Connected
        val running = backup is BackupState.Running
        binding.backupButton.isEnabled = connected?.supportsBackup == true && !running
        binding.backupProgress.visibility = if (running) View.VISIBLE else View.GONE

        val status = when {
            backup is BackupState.Running ->
                getString(R.string.backup_running, Formatter.formatShortFileSize(this, backup.bytes.toLong()))
            backup is BackupState.Done ->
                getString(R.string.backup_done, backup.meta.name, Formatter.formatShortFileSize(this, backup.meta.size.toLong()))
            backup is BackupState.Failed -> getString(R.string.backup_failed, backup.message)
            connected == null -> getString(R.string.not_connected_hint)
            !connected.supportsBackup -> getString(R.string.backup_unsupported)
            else -> null
        }
        binding.backupStatus.text = status
        binding.backupStatus.visibility = if (status == null) View.GONE else View.VISIBLE

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
            .setPositiveButton(R.string.save) { _, _ -> session.startBackup(input.text.toString().ifBlank { default }) }
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
                b.shareButton.setOnClickListener { share(meta) }
                b.deleteButton.setOnClickListener { delete(meta) }
            }
        }
    }
}
