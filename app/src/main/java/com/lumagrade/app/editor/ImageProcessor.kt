package com.lumagrade.app.editor

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Deterministic, dependency-free photo pipeline. All controls run in floating point and the
 * original pixels are blended back at the end, which gives preset intensity a natural result.
 */
object ImageProcessor {
    suspend fun apply(
        source: Bitmap,
        adjustments: Adjustments,
        intensity: Float,
    ): Bitmap {
        val width = source.width
        val height = source.height
        val sourcePixels = IntArray(width * height)
        val processedPixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        val mix = intensity.coerceIn(0f, 1f)
        val exposureGain = 2.0.pow(adjustments.exposure.toDouble()).toFloat()
        val contrastGain = 1f + adjustments.contrast * 1.45f
        val shadowTint = hueToRgb(adjustments.shadowHue)
        val highlightTint = hueToRgb(adjustments.highlightHue)
        val toneCurve = buildToneCurveLut(adjustments.toneCurve)
        val hueAdjustment = buildColorMixLut(adjustments.hueMix)
        val saturationAdjustment = buildColorMixLut(adjustments.saturationMix)
        val luminanceAdjustment = buildColorMixLut(adjustments.luminanceMix)
        val hasColorMix = adjustments.hueMix.any { it != 0f } ||
            adjustments.saturationMix.any { it != 0f } ||
            adjustments.luminanceMix.any { it != 0f }
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val maxDistance = max(1f, kotlin.math.sqrt(cx * cx + cy * cy))

        for (y in 0 until height) {
            if (y % 32 == 0) currentCoroutineContext().ensureActive()
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val input = sourcePixels[index]
                val alpha = Color.alpha(input)
                val originalR = Color.red(input) / 255f
                val originalG = Color.green(input) / 255f
                val originalB = Color.blue(input) / 255f

                var r = originalR * exposureGain
                var g = originalG * exposureGain
                var b = originalB * exposureGain

                var luminance = luma(r, g, b).coerceIn(0f, 1f)
                val shadowMask = (1f - luminance).pow(2)
                val highlightMask = luminance.pow(2)
                val blackMask = (1f - luminance).pow(4)
                val whiteMask = luminance.pow(4)
                val tonalShift =
                    adjustments.shadows * shadowMask * 0.34f +
                        adjustments.highlights * highlightMask * 0.31f +
                        adjustments.blacks * blackMask * 0.23f +
                        adjustments.whites * whiteMask * 0.23f
                r += tonalShift
                g += tonalShift
                b += tonalShift

                r = 0.5f + (r - 0.5f) * contrastGain
                g = 0.5f + (g - 0.5f) * contrastGain
                b = 0.5f + (b - 0.5f) * contrastGain

                if (toneCurve != null) {
                    val beforeCurve = luma(r, g, b).coerceIn(0f, 1f)
                    val mapped = toneCurve[(beforeCurve * 255f).toInt().coerceIn(0, 255)]
                    val curveShift = mapped - beforeCurve
                    r += curveShift
                    g += curveShift
                    b += curveShift
                }

                val warmth = adjustments.temperature
                r += warmth * 0.115f * (1.1f - clamp01(r) * 0.25f)
                g += warmth * 0.018f
                b -= warmth * 0.120f * (1.1f - clamp01(b) * 0.20f)

                val tint = adjustments.tint
                r += tint * 0.052f
                g -= tint * 0.095f
                b += tint * 0.052f

                if (hasColorMix) {
                    val maxChannel = max(r, max(g, b))
                    val minChannel = min(r, min(g, b))
                    val delta = maxChannel - minChannel
                    var lightness = (maxChannel + minChannel) / 2f
                    var hue = when {
                        delta < 0.0001f -> 0f
                        maxChannel == r -> 60f * (((g - b) / delta) % 6f)
                        maxChannel == g -> 60f * (((b - r) / delta) + 2f)
                        else -> 60f * (((r - g) / delta) + 4f)
                    }
                    if (hue < 0f) hue += 360f
                    val lookup = hue.toInt().coerceIn(0, 359)
                    hue = (hue + hueAdjustment[lookup] * 30f + 360f) % 360f
                    val baseSaturation = if (delta < 0.0001f) {
                        0f
                    } else {
                        delta / (1f - abs(2f * lightness - 1f)).coerceAtLeast(0.0001f)
                    }
                    val mixedSaturation = (baseSaturation * (1f + saturationAdjustment[lookup])).coerceIn(0f, 1f)
                    lightness = (lightness + luminanceAdjustment[lookup] * 0.22f).coerceIn(0f, 1f)
                    if (mixedSaturation < 0.0001f) {
                        r = lightness
                        g = lightness
                        b = lightness
                    } else {
                        val q = if (lightness < 0.5f) {
                            lightness * (1f + mixedSaturation)
                        } else {
                            lightness + mixedSaturation - lightness * mixedSaturation
                        }
                        val p = 2f * lightness - q
                        val normalizedHue = hue / 360f
                        r = hueChannel(p, q, normalizedHue + 1f / 3f)
                        g = hueChannel(p, q, normalizedHue)
                        b = hueChannel(p, q, normalizedHue - 1f / 3f)
                    }
                }

                luminance = luma(r, g, b).coerceIn(0f, 1f)
                val shadowGrade = adjustments.shadowTone * (1f - luminance).pow(2) * 0.72f
                val highlightGrade = adjustments.highlightTone * luminance.pow(2) * 0.65f
                r = lerp(r, shadowTint[0], shadowGrade)
                g = lerp(g, shadowTint[1], shadowGrade)
                b = lerp(b, shadowTint[2], shadowGrade)
                r = lerp(r, highlightTint[0], highlightGrade)
                g = lerp(g, highlightTint[1], highlightGrade)
                b = lerp(b, highlightTint[2], highlightGrade)

                val neutral = luma(r, g, b)
                val saturationScale = 1f + adjustments.saturation
                r = neutral + (r - neutral) * saturationScale
                g = neutral + (g - neutral) * saturationScale
                b = neutral + (b - neutral) * saturationScale

                val high = max(r, max(g, b))
                val low = min(r, min(g, b))
                val currentSaturation = if (abs(high) < 0.0001f) 0f else ((high - low) / abs(high)).coerceIn(0f, 1f)
                val vibranceScale = if (adjustments.vibrance >= 0f) {
                    1f + adjustments.vibrance * (1f - currentSaturation) * 1.45f
                } else {
                    1f + adjustments.vibrance * 0.82f
                }
                val vibrantNeutral = luma(r, g, b)
                r = vibrantNeutral + (r - vibrantNeutral) * vibranceScale
                g = vibrantNeutral + (g - vibrantNeutral) * vibranceScale
                b = vibrantNeutral + (b - vibrantNeutral) * vibranceScale

                val fade = adjustments.fade.coerceIn(0f, 1f)
                if (fade > 0f) {
                    val lift = fade * 0.095f
                    val compression = 1f - fade * 0.12f
                    r = lift + r * compression
                    g = lift + g * compression
                    b = lift + b * compression
                }

                val vignette = adjustments.vignette.coerceIn(0f, 1f)
                if (vignette > 0f) {
                    val dx = x - cx
                    val dy = y - cy
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy) / maxDistance
                    val edge = smoothstep(0.28f, 1f, distance) * vignette * 0.70f
                    val multiplier = 1f - edge
                    r *= multiplier
                    g *= multiplier
                    b *= multiplier
                }

                val grain = adjustments.grain.coerceIn(0f, 1f)
                if (grain > 0f) {
                    val noise = hashNoise(x, y) * grain * 0.090f
                    val grainMask = 0.55f + 0.45f * (1f - luma(r, g, b).coerceIn(0f, 1f))
                    r += noise * grainMask
                    g += noise * grainMask
                    b += noise * grainMask
                }

                r = lerp(originalR, clamp01(r), mix)
                g = lerp(originalG, clamp01(g), mix)
                b = lerp(originalB, clamp01(b), mix)

                processedPixels[index] = Color.argb(
                    alpha,
                    (clamp01(r) * 255f + 0.5f).toInt(),
                    (clamp01(g) * 255f + 0.5f).toInt(),
                    (clamp01(b) * 255f + 0.5f).toInt(),
                )
            }
        }

        val finalPixels = if (adjustments.sharpen > 0f && mix > 0f && width > 2 && height > 2) {
            sharpen(processedPixels, sourcePixels, width, height, adjustments.sharpen * mix)
            sourcePixels
        } else {
            processedPixels
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(finalPixels, 0, width, 0, 0, width, height)
        }
    }

    private suspend fun sharpen(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        amount: Float,
    ) {
        input.copyInto(output)
        val strength = amount.coerceIn(0f, 1f) * 0.62f
        for (y in 1 until height - 1) {
            if (y % 32 == 0) currentCoroutineContext().ensureActive()
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val center = input[index]
                val left = input[index - 1]
                val right = input[index + 1]
                val up = input[index - width]
                val down = input[index + width]

                output[index] = Color.argb(
                    Color.alpha(center),
                    sharpenValue(Color.red(center), Color.red(left), Color.red(right), Color.red(up), Color.red(down), strength),
                    sharpenValue(Color.green(center), Color.green(left), Color.green(right), Color.green(up), Color.green(down), strength),
                    sharpenValue(Color.blue(center), Color.blue(left), Color.blue(right), Color.blue(up), Color.blue(down), strength),
                )
            }
        }
    }

    private fun sharpenValue(center: Int, left: Int, right: Int, up: Int, down: Int, strength: Float): Int {
        val neighbors = (left + right + up + down) / 4f
        return (center + (center - neighbors) * strength).toInt().coerceIn(0, 255)
    }

    private fun buildToneCurveLut(points: List<CurvePoint>): FloatArray? {
        if (points.size < 2) return null
        val sorted = points
            .map { CurvePoint(it.input.coerceIn(0f, 1f), it.output.coerceIn(0f, 1f)) }
            .distinctBy { it.input }
            .sortedBy { it.input }
        if (sorted.size < 2) return null
        return FloatArray(256) { index ->
            val input = index / 255f
            val rightIndex = sorted.indexOfFirst { it.input >= input }.let { if (it < 0) sorted.lastIndex else it }
            val right = sorted[rightIndex]
            val left = sorted[(rightIndex - 1).coerceAtLeast(0)]
            if (right.input == left.input) {
                right.output
            } else {
                val amount = (input - left.input) / (right.input - left.input)
                lerp(left.output, right.output, amount)
            }
        }
    }

    private fun buildColorMixLut(values: List<Float>): FloatArray {
        val normalized = FloatArray(8) { index -> values.getOrNull(index)?.coerceIn(-1f, 1f) ?: 0f }
        val centers = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)
        return FloatArray(360) { hue ->
            var weighted = 0f
            var total = 0f
            for (index in centers.indices) {
                val rawDistance = abs(hue - centers[index])
                val distance = min(rawDistance, 360f - rawDistance)
                val weight = (1f - distance / 48f).coerceAtLeast(0f)
                weighted += normalized[index] * weight
                total += weight
            }
            if (total > 0f) weighted / total else 0f
        }
    }

    private fun hueChannel(p: Float, q: Float, rawHue: Float): Float {
        var hue = rawHue
        if (hue < 0f) hue += 1f
        if (hue > 1f) hue -= 1f
        return when {
            hue < 1f / 6f -> p + (q - p) * 6f * hue
            hue < 1f / 2f -> q
            hue < 2f / 3f -> p + (q - p) * (2f / 3f - hue) * 6f
            else -> p
        }
    }

    private fun luma(r: Float, g: Float, b: Float): Float = r * 0.2126f + g * 0.7152f + b * 0.0722f

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount.coerceIn(0f, 1f)

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hueToRgb(hue: Float): FloatArray {
        val h = ((hue % 360f) + 360f) % 360f
        val c = 1f
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val rgb = when {
            h < 60f -> floatArrayOf(c, x, 0f)
            h < 120f -> floatArrayOf(x, c, 0f)
            h < 180f -> floatArrayOf(0f, c, x)
            h < 240f -> floatArrayOf(0f, x, c)
            h < 300f -> floatArrayOf(x, 0f, c)
            else -> floatArrayOf(c, 0f, x)
        }
        // Slightly soften pure primaries so split toning behaves photographically.
        return floatArrayOf(
            0.08f + rgb[0] * 0.84f,
            0.08f + rgb[1] * 0.84f,
            0.08f + rgb[2] * 0.84f,
        )
    }

    private fun hashNoise(x: Int, y: Int): Float {
        var value = x * 0x1f1f1f1f xor y * 0x45d9f3b
        value = value xor (value ushr 16)
        value *= 0x45d9f3b
        value = value xor (value ushr 16)
        return ((value and 0xFFFF) / 65535f) - 0.5f
    }
}
