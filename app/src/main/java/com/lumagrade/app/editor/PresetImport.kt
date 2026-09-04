package com.lumagrade.app.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import kotlin.math.max

/** Imports the most useful global settings from Adobe Camera Raw/Lightroom XMP presets. */
object PresetImporter {
    private val colorNames = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")

    fun parse(context: Context, uri: Uri): List<PhotoPreset> {
        val fileName = displayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_ARCHIVE_BYTES) }
            ?: throw IllegalArgumentException("Could not open $fileName")
        if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            return parseZip(bytes, fileName)
        }
        return parseText(bytes, fileName)
    }

    private fun parseText(bytes: ByteArray, fileName: String): List<PhotoPreset> {
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (text.isBlank()) throw IllegalArgumentException("$fileName is empty")
        return when {
            text.startsWith("<") -> listOf(parseXmp(text, fileName, bytes.contentHashCode()))
            text.startsWith("{") || text.startsWith("[") -> parseJson(text, fileName, bytes.contentHashCode())
            else -> throw IllegalArgumentException("$fileName is not an XMP or LumaGrade JSON preset")
        }
    }

    private fun parseZip(bytes: ByteArray, fileName: String): List<PhotoPreset> {
        val presets = mutableListOf<PhotoPreset>()
        var compatibleEntries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name.substringAfterLast('/')
                val compatible = !entry.isDirectory && (
                    entryName.endsWith(".xmp", ignoreCase = true) ||
                        entryName.endsWith(".json", ignoreCase = true)
                    )
                if (compatible) {
                    compatibleEntries++
                    if (compatibleEntries > MAX_ZIP_PRESETS) break
                    runCatching { parseText(readLimited(zip, MAX_PRESET_BYTES), entryName) }
                        .onSuccess { presets.addAll(it) }
                }
                zip.closeEntry()
            }
        }
        if (presets.isEmpty()) {
            throw IllegalArgumentException("$fileName contains no compatible XMP or JSON presets")
        }
        return presets
    }

    private fun parseXmp(text: String, fileName: String, contentHash: Int): PhotoPreset {
        val properties = linkedMapOf<String, String>()
        val curve = mutableListOf<CurvePoint>()
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(text))
        var curveDepth = -1
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    for (index in 0 until parser.attributeCount) {
                        properties[parser.getAttributeName(index).substringAfter(':')] = parser.getAttributeValue(index)
                    }
                    if (tag == "ToneCurvePV2012") {
                        curveDepth = parser.depth
                    } else if (tag == "li" && curveDepth > 0) {
                        parseCurvePoint(parser.nextText())?.let(curve::add)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (curveDepth == parser.depth && parser.name.substringAfter(':') == "ToneCurvePV2012") {
                        curveDepth = -1
                    }
                }
            }
            event = parser.next()
        }

        var recognized = curve.size
        fun raw(vararg names: String): Float? = names.firstNotNullOfOrNull { properties[it]?.toFloatOrNull() }
        fun signed(vararg names: String): Float {
            val value = raw(*names) ?: return 0f
            recognized++
            return (value / 100f).coerceIn(-1f, 1f)
        }
        fun positive(vararg names: String): Float {
            val value = raw(*names) ?: return 0f
            recognized++
            return (value / 100f).coerceIn(0f, 1f)
        }

        val exposure = raw("Exposure2012", "Exposure")?.also { recognized++ }?.coerceIn(-1.5f, 1.5f) ?: 0f
        val temperature = raw("Temperature")?.also { recognized++ }?.let {
            ((it - 5500f) / 3500f).coerceIn(-1f, 1f)
        } ?: signed("IncrementalTemperature")
        val shadowHue = raw("ColorGradeShadowHue", "SplitToningShadowHue")?.also { recognized++ } ?: 220f
        val shadowTone = raw("ColorGradeShadowSat", "SplitToningShadowSaturation")?.also { recognized++ }
            ?.div(100f)?.coerceIn(0f, 1f) ?: 0f
        val highlightHue = raw("ColorGradeHighlightHue", "SplitToningHighlightHue")?.also { recognized++ } ?: 42f
        val highlightTone = raw("ColorGradeHighlightSat", "SplitToningHighlightSaturation")?.also { recognized++ }
            ?.div(100f)?.coerceIn(0f, 1f) ?: 0f
        val postCropVignette = raw("PostCropVignetteAmount", "VignetteAmount")?.also { recognized++ } ?: 0f
        val clarity = max(positive("Clarity2012", "Clarity"), positive("Texture"))
        val hueMix = colorNames.map { signed("HueAdjustment$it") }
        val saturationMix = colorNames.map { signed("SaturationAdjustment$it") }
        val luminanceMix = colorNames.map { signed("LuminanceAdjustment$it") }
        val contrast = signed("Contrast2012", "Contrast")
        val highlights = signed("Highlights2012", "Highlights")
        val shadows = signed("Shadows2012", "Shadows")
        val whites = signed("Whites2012", "Whites")
        val blacks = signed("Blacks2012", "Blacks")
        val tint = signed("Tint")
        val vibrance = signed("Vibrance")
        val saturation = signed("Saturation")
        val grain = positive("GrainAmount")
        val sharpness = max(clarity, (raw("Sharpness")?.also { recognized++ }?.div(150f) ?: 0f)).coerceIn(0f, 1f)

        if (recognized == 0) {
            throw IllegalArgumentException("$fileName does not contain supported photo adjustments")
        }

        val cleanName = properties["PresetName"]
            ?: properties["Name"]
            ?: fileName.substringBeforeLast('.')
        val adjustments = Adjustments(
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            whites = whites,
            blacks = blacks,
            temperature = temperature,
            tint = tint,
            vibrance = vibrance,
            saturation = saturation,
            vignette = (-postCropVignette / 100f).coerceIn(0f, 1f),
            grain = grain,
            sharpen = sharpness,
            shadowHue = shadowHue,
            shadowTone = shadowTone,
            highlightHue = highlightHue,
            highlightTone = highlightTone,
            toneCurve = curve.distinctBy { it.input }.sortedBy { it.input },
            hueMix = hueMix,
            saturationMix = saturationMix,
            luminanceMix = luminanceMix,
        )
        return importedPreset(cleanName, contentHash, 0, adjustments)
    }

    private fun parseJson(text: String, fileName: String, contentHash: Int): List<PhotoPreset> {
        val root = JSONTokener(text).nextValue()
        val entries = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("presets") ?: JSONArray().put(root)
            else -> throw IllegalArgumentException("$fileName has an invalid preset structure")
        }
        if (entries.length() == 0) throw IllegalArgumentException("$fileName contains no presets")
        return List(entries.length()) { index ->
            val item = entries.optJSONObject(index)
                ?: throw IllegalArgumentException("Preset ${index + 1} in $fileName is invalid")
            val controls = item.optJSONObject("adjustments") ?: item
            val name = item.optString("name").ifBlank { "$fileName ${index + 1}" }
            importedPreset(
                name = name,
                contentHash = contentHash,
                index = index,
                adjustments = adjustmentsFromJson(controls),
                swatchStart = item.optLong("swatchStart", 0xFF7CC6B8),
                swatchEnd = item.optLong("swatchEnd", 0xFF4E5579),
            )
        }
    }

    private fun importedPreset(
        name: String,
        contentHash: Int,
        index: Int,
        adjustments: Adjustments,
        swatchStart: Long = 0xFF7CC6B8,
        swatchEnd: Long = 0xFF4E5579,
    ): PhotoPreset {
        val safeName = name.trim().take(40).ifBlank { "Imported preset" }
        val slug = safeName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
        return PhotoPreset(
            id = "imported_${slug}_${contentHash.toUInt().toString(16)}_$index",
            name = safeName,
            collection = "Imported",
            swatchStart = swatchStart,
            swatchEnd = swatchEnd,
            adjustments = adjustments,
        )
    }

    private fun adjustmentsFromJson(json: JSONObject): Adjustments = Adjustments(
        exposure = json.float("exposure", -1.5f..1.5f),
        contrast = json.float("contrast"),
        highlights = json.float("highlights"),
        shadows = json.float("shadows"),
        whites = json.float("whites"),
        blacks = json.float("blacks"),
        temperature = json.float("temperature"),
        tint = json.float("tint"),
        vibrance = json.float("vibrance"),
        saturation = json.float("saturation"),
        fade = json.float("fade", 0f..1f),
        vignette = json.float("vignette", 0f..1f),
        grain = json.float("grain", 0f..1f),
        sharpen = json.float("sharpen", 0f..1f),
        shadowHue = json.float("shadowHue", 0f..360f, 220f),
        shadowTone = json.float("shadowTone", 0f..1f),
        highlightHue = json.float("highlightHue", 0f..360f, 42f),
        highlightTone = json.float("highlightTone", 0f..1f),
        toneCurve = json.optJSONArray("toneCurve").toCurvePoints(),
        hueMix = json.optJSONArray("hueMix").toMix(),
        saturationMix = json.optJSONArray("saturationMix").toMix(),
        luminanceMix = json.optJSONArray("luminanceMix").toMix(),
    )

    private fun parseCurvePoint(text: String): CurvePoint? {
        val parts = text.split(',').map { it.trim().toFloatOrNull() ?: return null }
        if (parts.size < 2) return null
        return CurvePoint((parts[0] / 255f).coerceIn(0f, 1f), (parts[1] / 255f).coerceIn(0f, 1f))
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IllegalArgumentException("Preset file is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: "preset"

    private fun JSONObject.float(
        key: String,
        range: ClosedFloatingPointRange<Float> = -1f..1f,
        default: Float = 0f,
    ): Float = if (has(key)) optDouble(key, default.toDouble()).toFloat().coerceIn(range) else default

    private fun JSONArray?.toMix(): List<Float> = List(8) { index ->
        this?.optDouble(index, 0.0)?.toFloat()?.coerceIn(-1f, 1f) ?: 0f
    }

    private fun JSONArray?.toCurvePoints(): List<CurvePoint> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val point = optJSONArray(index)
            if (point != null && point.length() >= 2) {
                CurvePoint(point.optDouble(0).toFloat(), point.optDouble(1).toFloat())
            } else {
                val objectPoint = optJSONObject(index)
                CurvePoint(objectPoint?.optDouble("input")?.toFloat() ?: 0f, objectPoint?.optDouble("output")?.toFloat() ?: 0f)
            }
        }.map { CurvePoint(it.input.coerceIn(0f, 1f), it.output.coerceIn(0f, 1f)) }
    }

    private const val MAX_PRESET_BYTES = 2 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 25 * 1024 * 1024
    private const val MAX_ZIP_PRESETS = 100
}

object ImportedPresetStore {
    fun load(context: Context): List<PhotoPreset> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            List(array.length()) { index -> presetFromJson(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, presets: List<PhotoPreset>) {
        val array = JSONArray()
        presets.forEach { array.put(presetToJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun presetToJson(preset: PhotoPreset): JSONObject = JSONObject().apply {
        put("id", preset.id)
        put("name", preset.name)
        put("swatchStart", preset.swatchStart)
        put("swatchEnd", preset.swatchEnd)
        put("adjustments", adjustmentsToJson(preset.adjustments))
    }

    private fun presetFromJson(json: JSONObject): PhotoPreset = PhotoPreset(
        id = json.getString("id"),
        name = json.getString("name"),
        collection = "Imported",
        swatchStart = json.optLong("swatchStart", 0xFF7CC6B8),
        swatchEnd = json.optLong("swatchEnd", 0xFF4E5579),
        adjustments = adjustmentsFromStoredJson(json.getJSONObject("adjustments")),
    )

    private fun adjustmentsToJson(value: Adjustments): JSONObject = JSONObject().apply {
        put("exposure", value.exposure)
        put("contrast", value.contrast)
        put("highlights", value.highlights)
        put("shadows", value.shadows)
        put("whites", value.whites)
        put("blacks", value.blacks)
        put("temperature", value.temperature)
        put("tint", value.tint)
        put("vibrance", value.vibrance)
        put("saturation", value.saturation)
        put("fade", value.fade)
        put("vignette", value.vignette)
        put("grain", value.grain)
        put("sharpen", value.sharpen)
        put("shadowHue", value.shadowHue)
        put("shadowTone", value.shadowTone)
        put("highlightHue", value.highlightHue)
        put("highlightTone", value.highlightTone)
        put("toneCurve", JSONArray(value.toneCurve.map { JSONArray(listOf(it.input, it.output)) }))
        put("hueMix", JSONArray(value.hueMix))
        put("saturationMix", JSONArray(value.saturationMix))
        put("luminanceMix", JSONArray(value.luminanceMix))
    }

    private fun adjustmentsFromStoredJson(json: JSONObject): Adjustments = Adjustments(
        exposure = json.optDouble("exposure").toFloat(),
        contrast = json.optDouble("contrast").toFloat(),
        highlights = json.optDouble("highlights").toFloat(),
        shadows = json.optDouble("shadows").toFloat(),
        whites = json.optDouble("whites").toFloat(),
        blacks = json.optDouble("blacks").toFloat(),
        temperature = json.optDouble("temperature").toFloat(),
        tint = json.optDouble("tint").toFloat(),
        vibrance = json.optDouble("vibrance").toFloat(),
        saturation = json.optDouble("saturation").toFloat(),
        fade = json.optDouble("fade").toFloat(),
        vignette = json.optDouble("vignette").toFloat(),
        grain = json.optDouble("grain").toFloat(),
        sharpen = json.optDouble("sharpen").toFloat(),
        shadowHue = json.optDouble("shadowHue", 220.0).toFloat(),
        shadowTone = json.optDouble("shadowTone").toFloat(),
        highlightHue = json.optDouble("highlightHue", 42.0).toFloat(),
        highlightTone = json.optDouble("highlightTone").toFloat(),
        toneCurve = json.optJSONArray("toneCurve").toStoredCurve(),
        hueMix = json.optJSONArray("hueMix").toStoredMix(),
        saturationMix = json.optJSONArray("saturationMix").toStoredMix(),
        luminanceMix = json.optJSONArray("luminanceMix").toStoredMix(),
    )

    private fun JSONArray?.toStoredMix(): List<Float> = List(8) { index -> this?.optDouble(index)?.toFloat() ?: 0f }

    private fun JSONArray?.toStoredCurve(): List<CurvePoint> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val point = getJSONArray(index)
            CurvePoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
        }
    }

    private const val PREFS = "imported_presets"
    private const val KEY = "presets_v1"
}
