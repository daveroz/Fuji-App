package com.fujiapp.remote.data

import android.content.Context
import com.fujiapp.remote.model.Recipe
import java.io.File

/** Stores recipes as JSON files in the app's private storage (filesDir/recipes). */
class RecipeStore(context: Context) {

    private val dir: File = File(context.filesDir, "recipes").apply { mkdirs() }

    fun list(): List<StoredRecipe> =
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f ->
                runCatching { StoredRecipe(f, Recipe.fromJsonString(f.readText())) }.getOrNull()
            }
            ?: emptyList()

    fun save(recipe: Recipe): File {
        val file = File(dir, safeName(recipe.name) + ".json")
        file.writeText(recipe.toJsonString())
        return file
    }

    fun delete(file: File): Boolean = file.delete()

    fun load(file: File): Recipe = Recipe.fromJsonString(file.readText())

    /** Write a shareable copy to the cache dir for an export/share intent. */
    fun exportToCache(context: Context, recipe: Recipe): File {
        val out = File(context.cacheDir, safeName(recipe.name) + ".fujirecipe.json")
        out.writeText(recipe.toJsonString())
        return out
    }

    private fun safeName(name: String): String =
        name.trim().ifEmpty { "recipe" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)

    data class StoredRecipe(val file: File, val recipe: Recipe)
}
