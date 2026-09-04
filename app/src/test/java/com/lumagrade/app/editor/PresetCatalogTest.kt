package com.lumagrade.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCatalogTest {
    @Test
    fun presetIdsAreUnique() {
        val ids = PresetCatalog.presets.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun catalogContainsNeutralAndCreativeLooks() {
        assertEquals(Adjustments(), PresetCatalog.byId("original").adjustments)
        assertTrue(PresetCatalog.presets.size >= 16)
    }

    @Test
    fun controlsStayWithinSupportedRanges() {
        PresetCatalog.presets.forEach { preset ->
            with(preset.adjustments) {
                assertTrue("${preset.id}: exposure", exposure in -1.5f..1.5f)
                listOf(contrast, highlights, shadows, whites, blacks, temperature, tint, vibrance, saturation)
                    .forEach { assertTrue("${preset.id}: signed control", it in -1f..1f) }
                listOf(fade, vignette, grain, sharpen, shadowTone, highlightTone)
                    .forEach { assertTrue("${preset.id}: effect", it in 0f..1f) }
            }
        }
    }
}
