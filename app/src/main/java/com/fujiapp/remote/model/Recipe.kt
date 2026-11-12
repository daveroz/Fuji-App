package com.fujiapp.remote.model

import com.fujiapp.remote.net.Ptp
import com.fujiapp.remote.net.PropDesc
import org.json.JSONArray
import org.json.JSONObject

/** One property captured in a recipe. */
data class RecipeProperty(
    val code: Int,
    val value: Long,
    val dataType: Int,
    val label: String,
    val valueLabel: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("hex", "0x%04X".format(code))
        put("label", label)
        put("value", value)
        put("valueLabel", valueLabel)
        put("type", dataType)
    }

    companion object {
        fun fromJson(o: JSONObject): RecipeProperty = RecipeProperty(
            code = o.getInt("code"),
            value = o.getLong("value"),
            dataType = o.optInt("type", Ptp.Type.UINT32),
            label = o.optString("label", Ptp.KnownProps.label(o.getInt("code"))),
            valueLabel = o.optString("valueLabel", o.getLong("value").toString()),
        )

        fun fromDesc(d: PropDesc): RecipeProperty = RecipeProperty(
            code = d.code,
            value = d.currentValue,
            dataType = d.dataType,
            label = d.label,
            valueLabel = d.valueLabel,
        )
    }
}

/**
 * A "recipe" — a named snapshot of the camera's look/custom-simulation parameters (film
 * simulation + grain, Color Chrome, white balance, tone curves, etc.). This is the legal,
 * firmware-free way to back up and transfer custom looks over the standard protocol.
 */
data class Recipe(
    val name: String,
    val camera: String,
    val savedAt: Long,
    val properties: List<RecipeProperty>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("camera", camera)
        put("savedAt", savedAt)
        put("appFormat", FORMAT_VERSION)
        put("properties", JSONArray().apply { properties.forEach { put(it.toJson()) } })
    }

    fun toJsonString(): String = toJson().toString(2)

    companion object {
        const val FORMAT_VERSION = 1

        fun fromJsonString(s: String): Recipe = fromJson(JSONObject(s))

        fun fromJson(o: JSONObject): Recipe {
            val props = ArrayList<RecipeProperty>()
            val arr = o.optJSONArray("properties") ?: JSONArray()
            for (i in 0 until arr.length()) props.add(RecipeProperty.fromJson(arr.getJSONObject(i)))
            return Recipe(
                name = o.optString("name", "Recipe"),
                camera = o.optString("camera", ""),
                savedAt = o.optLong("savedAt", System.currentTimeMillis()),
                properties = props,
            )
        }
    }
}
