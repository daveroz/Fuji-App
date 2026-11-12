package com.fujiapp.remote.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fujiapp.remote.App
import com.fujiapp.remote.CameraSession
import com.fujiapp.remote.R
import com.fujiapp.remote.data.RecipeStore
import com.fujiapp.remote.databinding.ActivityRecipesBinding
import com.fujiapp.remote.databinding.ItemRecipeBinding
import com.fujiapp.remote.model.Recipe
import com.fujiapp.remote.net.Ptp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class RecipeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecipesBinding
    private val session: CameraSession get() = App.of(application)
    private val store: RecipeStore by lazy { RecipeStore(this) }
    private val adapter = RecipeAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecipesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.recipes_title)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.backupButton.setOnClickListener { onBackup() }
        refresh()
    }

    private fun refresh() {
        val items = store.list()
        adapter.submit(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onBackup() {
        if (!session.isConnected) {
            Toast.makeText(this, R.string.not_connected_hint, Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this).apply { hint = getString(R.string.recipe_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_current)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().ifBlank { "Recipe ${store.list().size + 1}" }
                lifecycleScope.launch {
                    val recipe = session.buildRecipe(name)
                    if (recipe == null || recipe.properties.isEmpty()) {
                        Toast.makeText(this@RecipeActivity, "Couldn't read camera settings", Toast.LENGTH_LONG).show()
                    } else {
                        store.save(recipe)
                        Toast.makeText(
                            this@RecipeActivity,
                            "Saved ${recipe.properties.size} settings",
                            Toast.LENGTH_SHORT,
                        ).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onApply(recipe: Recipe) {
        if (!session.isConnected) {
            Toast.makeText(this, R.string.not_connected_hint, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val (applied, total) = session.applyRecipe(recipe)
            Toast.makeText(this@RecipeActivity, "Applied $applied / $total settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun onShare(recipe: Recipe) {
        val file = store.exportToCache(this, recipe)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun onDelete(item: RecipeStore.StoredRecipe) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(item.recipe.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(item.file)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- adapter ----

    private inner class RecipeAdapter : RecyclerView.Adapter<RecipeAdapter.VH>() {
        private val items = ArrayList<RecipeStore.StoredRecipe>()
        private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        fun submit(list: List<RecipeStore.StoredRecipe>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRecipeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemRecipeBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: RecipeStore.StoredRecipe) {
                val r = item.recipe
                b.name.text = r.name
                val filmSim = r.properties.firstOrNull { it.code == Ptp.FujiProp.FILM_SIMULATION }?.valueLabel
                val parts = buildList {
                    if (!filmSim.isNullOrBlank()) add(filmSim)
                    if (r.camera.isNotBlank()) add(r.camera)
                    add("${r.properties.size} settings")
                    add(dateFmt.format(Date(r.savedAt)))
                }
                b.subtitle.text = parts.joinToString(" · ")
                b.applyButton.setOnClickListener { onApply(r) }
                b.shareButton.setOnClickListener { onShare(r) }
                b.deleteButton.setOnClickListener { onDelete(item) }
            }
        }
    }
}
