package com.lumagrade.app.editor

data class Adjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val sharpen: Float = 0f,
    val shadowHue: Float = 220f,
    val shadowTone: Float = 0f,
    val highlightHue: Float = 42f,
    val highlightTone: Float = 0f,
)

data class PhotoPreset(
    val id: String,
    val name: String,
    val collection: String,
    val swatchStart: Long,
    val swatchEnd: Long,
    val adjustments: Adjustments,
)

enum class EditorPanel(val label: String) {
    Presets("Presets"),
    Light("Light"),
    Color("Color"),
    Effects("Effects"),
}
