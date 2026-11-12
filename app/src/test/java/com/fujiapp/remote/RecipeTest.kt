package com.fujiapp.remote

import com.fujiapp.remote.model.Recipe
import com.fujiapp.remote.model.RecipeProperty
import com.fujiapp.remote.net.Ptp
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeTest {

    private fun sample() = Recipe(
        name = "Classic Chrome Street",
        camera = "X100VI",
        savedAt = 1_700_000_000_000L,
        properties = listOf(
            RecipeProperty(Ptp.FujiProp.FILM_SIMULATION, 0x0B, Ptp.Type.UINT16, "Film simulation", "Classic Chrome"),
            RecipeProperty(Ptp.FujiProp.GRAIN_EFFECT, 2, Ptp.Type.UINT16, "Grain effect", "2"),
            RecipeProperty(Ptp.FujiProp.HIGHLIGHT_TONE, 0xFFFFFFFEL, Ptp.Type.INT32, "Highlight tone", "-2"),
        ),
    )

    @Test fun json_round_trips() {
        val original = sample()
        val restored = Recipe.fromJsonString(original.toJsonString())
        assertEquals(original.name, restored.name)
        assertEquals(original.camera, restored.camera)
        assertEquals(original.savedAt, restored.savedAt)
        assertEquals(original.properties.size, restored.properties.size)
        assertEquals(0x0BL, restored.properties[0].value)
        assertEquals(Ptp.Type.UINT16, restored.properties[0].dataType)
        // 32-bit value with high bit set survives the trip.
        assertEquals(0xFFFFFFFEL, restored.properties[2].value)
        assertEquals(Ptp.FujiProp.HIGHLIGHT_TONE, restored.properties[2].code)
    }

    @Test fun film_sim_label_lookup() {
        assertEquals("Classic Chrome", Ptp.KnownProps.valueLabel(Ptp.FujiProp.FILM_SIMULATION, 0x0B))
        assertEquals("Film simulation", Ptp.KnownProps.label(Ptp.FujiProp.FILM_SIMULATION))
        // Unknown code falls back to hex label, never crashes.
        assertEquals("Property 0xD999", Ptp.KnownProps.label(0xD999))
    }
}
