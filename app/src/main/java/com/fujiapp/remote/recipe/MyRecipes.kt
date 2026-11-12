package com.fujiapp.remote.recipe

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Recipes made in the Lab, kept on the phone. */
class MyRecipes(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("my_recipes", Context.MODE_PRIVATE)

    fun list(): List<LibraryRecipe> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull() }
    }

    fun save(recipe: Recipe, note: String): LibraryRecipe {
        val item = LibraryRecipe(
            id = "mine:" + System.currentTimeMillis(),
            title = recipe.name.ifBlank { "My recipe" },
            group = LibraryRecipe.Group.MINE,
            source = "Made in the Lab",
            look = note,
            how = "",
            tips = "",
            palette = emptyList(),
            recipe = recipe,
        )
        write(list() + item)
        return item
    }

    fun delete(id: String) = write(list().filter { it.id != id })

    private fun write(items: List<LibraryRecipe>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    companion object {
        private const val KEY = "recipes"

        fun toJson(item: LibraryRecipe): JSONObject = JSONObject().apply {
            put("id", item.id)
            put("note", item.look)
            put("recipe", recipeJson(item.recipe))
        }

        fun recipeJson(r: Recipe): JSONObject = JSONObject().apply {
            put("name", r.name); put("filmSim", r.filmSim.name); put("grain", r.grain.name)
            put("colorChrome", r.colorChrome.name); put("colorChromeBlue", r.colorChromeBlue.name)
            put("whiteBalance", r.whiteBalance.name); put("kelvin", r.kelvin); put("wbRed", r.wbRed); put("wbBlue", r.wbBlue)
            put("dynamicRange", r.dynamicRange.name); put("drPriority", r.drPriority.name)
            put("highlight", r.highlight); put("shadow", r.shadow); put("color", r.color); put("sharpness", r.sharpness)
            put("noiseReduction", r.noiseReduction); put("clarity", r.clarity)
            put("monoWarmCool", r.monoWarmCool); put("monoMagentaGreen", r.monoMagentaGreen)
        }

        fun recipeFromJson(o: JSONObject): Recipe = Recipe(
            name = o.getString("name"),
            filmSim = FilmSim.valueOf(o.getString("filmSim")),
            grain = Grain.valueOf(o.optString("grain", "OFF")),
            colorChrome = Effect.valueOf(o.optString("colorChrome", "OFF")),
            colorChromeBlue = Effect.valueOf(o.optString("colorChromeBlue", "OFF")),
            whiteBalance = WhiteBalance.valueOf(o.optString("whiteBalance", "AUTO")),
            kelvin = o.optInt("kelvin", 5500),
            wbRed = o.optInt("wbRed"), wbBlue = o.optInt("wbBlue"),
            dynamicRange = DynamicRange.valueOf(o.optString("dynamicRange", "DR100")),
            drPriority = DrPriority.valueOf(o.optString("drPriority", "OFF")),
            highlight = o.optDouble("highlight", 0.0), shadow = o.optDouble("shadow", 0.0),
            color = o.optInt("color"), sharpness = o.optInt("sharpness"), noiseReduction = o.optInt("noiseReduction"),
            clarity = o.optInt("clarity"), monoWarmCool = o.optInt("monoWarmCool"), monoMagentaGreen = o.optInt("monoMagentaGreen"),
        )

        private fun fromJson(o: JSONObject): LibraryRecipe {
            val recipe = recipeFromJson(o.getJSONObject("recipe"))
            return LibraryRecipe(
                id = o.getString("id"), title = recipe.name, group = LibraryRecipe.Group.MINE,
                source = "Made in the Lab", look = o.optString("note"), how = "", tips = "", palette = emptyList(), recipe = recipe,
            )
        }
    }
}
