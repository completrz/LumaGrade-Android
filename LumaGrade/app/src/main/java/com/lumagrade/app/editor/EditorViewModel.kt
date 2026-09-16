package com.lumagrade.app.editor

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

data class EditorUiState(
    val originalPreview: Bitmap? = null,
    val editedPreview: Bitmap? = null,
    val presetPreviews: Map<String, Bitmap> = emptyMap(),
    val importedPresets: List<PhotoPreset> = emptyList(),
    val photoName: String = "Photo",
    val selectedPresetId: String = "original",
    val adjustments: Adjustments = Adjustments(),
    val presetIntensity: Float = 1f,
    val panel: EditorPanel = EditorPanel.Presets,
    val isLoading: Boolean = false,
    val isRendering: Boolean = false,
    val isExporting: Boolean = false,
    val isImportingPresets: Boolean = false,
    val message: String? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(
        EditorUiState(importedPresets = ImportedPresetStore.load(application)),
    )
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var fullSizeSource: Bitmap? = null
    private var previewSource: Bitmap? = null
    private var renderJob: Job? = null
    private var thumbnailJob: Job? = null
    private var renderGeneration = 0

    fun loadPhoto(context: Context, uri: Uri) {
        renderJob?.cancel()
        thumbnailJob?.cancel()
        _state.update { it.copy(isLoading = true, message = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { decode(context, uri, MAX_EXPORT_EDGE) }
            }.onSuccess { fullBitmap ->
                val importedPresets = _state.value.importedPresets
                fullSizeSource = fullBitmap
                val previewBitmap = makePreview(fullBitmap, MAX_PREVIEW_EDGE)
                previewSource = previewBitmap
                _state.value = EditorUiState(
                    originalPreview = previewBitmap,
                    editedPreview = previewBitmap,
                    photoName = displayName(context, uri),
                    importedPresets = importedPresets,
                )
                renderPreview(immediate = true)
                generatePresetPreviews(previewBitmap)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        message = error.userMessage("That photo could not be opened."),
                    )
                }
            }
        }
    }

    fun clearPhoto() {
        renderJob?.cancel()
        thumbnailJob?.cancel()
        renderGeneration++
        fullSizeSource = null
        previewSource = null
        _state.value = EditorUiState(importedPresets = _state.value.importedPresets)
    }

    fun selectPreset(preset: PhotoPreset) {
        _state.update {
            it.copy(
                selectedPresetId = preset.id,
                adjustments = preset.adjustments,
                presetIntensity = 1f,
                message = null,
            )
        }
        renderPreview()
    }

    fun setPresetIntensity(value: Float) {
        _state.update { it.copy(presetIntensity = value.coerceIn(0f, 1f), message = null) }
        renderPreview()
    }

    fun setAdjustments(adjustments: Adjustments) {
        _state.update {
            it.copy(
                selectedPresetId = "custom",
                adjustments = adjustments,
                message = null,
            )
        }
        renderPreview()
    }

    fun setPanel(panel: EditorPanel) {
        _state.update { it.copy(panel = panel) }
    }

    fun resetEdits() {
        selectPreset(PresetCatalog.byId("original"))
    }

    fun importPresets(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(isImportingPresets = true, message = null) }
        viewModelScope.launch {
            val imported = mutableListOf<PhotoPreset>()
            val failures = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                uris.take(MAX_IMPORT_FILES).forEach { uri ->
                    runCatching { PresetImporter.parse(context, uri) }
                        .onSuccess { imported.addAll(it) }
                        .onFailure { failures += it.message ?: "Unsupported preset" }
                }
            }

            if (imported.isNotEmpty()) {
                val merged = (imported + _state.value.importedPresets)
                    .distinctBy { it.id }
                    .take(MAX_SAVED_PRESETS)
                ImportedPresetStore.save(getApplication<Application>(), merged)
                val suffix = if (failures.isEmpty()) "" else "; ${failures.size} could not be read"
                _state.update {
                    it.copy(
                        importedPresets = merged,
                        isImportingPresets = false,
                        message = "Imported ${imported.size} preset${if (imported.size == 1) "" else "s"}$suffix",
                    )
                }
                previewSource?.let(::generatePresetPreviews)
            } else {
                _state.update {
                    it.copy(
                        isImportingPresets = false,
                        message = failures.firstOrNull() ?: "No compatible presets were selected",
                    )
                }
            }
        }
    }

    fun removeImportedPreset(id: String) {
        val current = _state.value
        val remaining = current.importedPresets.filterNot { it.id == id }
        if (remaining.size == current.importedPresets.size) return
        ImportedPresetStore.save(getApplication<Application>(), remaining)
        _state.update {
            it.copy(
                importedPresets = remaining,
                presetPreviews = it.presetPreviews - id,
                message = "Imported preset removed",
            )
        }
        if (current.selectedPresetId == id) resetEdits()
    }

    fun exportPhoto(context: Context, destination: Uri) {
        val source = fullSizeSource ?: return
        val snapshot = _state.value
        _state.update { it.copy(isExporting = true, message = null) }
        viewModelScope.launch {
            runCatching {
                val output = withContext(Dispatchers.Default) {
                    ImageProcessor.apply(source, snapshot.adjustments, snapshot.presetIntensity)
                }
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destination, "w")?.use { stream ->
                            if (!output.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                                throw IOException("JPEG encoder failed")
                            }
                        } ?: throw IOException("Could not create the output file")
                    }
                } finally {
                    output.recycle()
                }
            }.onSuccess {
                _state.update { it.copy(isExporting = false, message = "Saved full-quality JPEG") }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isExporting = false,
                        message = error.userMessage("The photo could not be exported."),
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun renderPreview(immediate: Boolean = false) {
        val source = previewSource ?: return
        renderJob?.cancel()
        val generation = ++renderGeneration
        val snapshot = _state.value
        renderJob = viewModelScope.launch {
            if (!immediate) delay(55)
            if (generation == renderGeneration) {
                _state.update { it.copy(isRendering = true) }
            }
            runCatching {
                withContext(Dispatchers.Default) {
                    ImageProcessor.apply(source, snapshot.adjustments, snapshot.presetIntensity)
                }
            }.onSuccess { bitmap ->
                if (generation == renderGeneration) {
                    _state.update { it.copy(editedPreview = bitmap, isRendering = false, isLoading = false) }
                } else {
                    bitmap.recycle()
                }
            }.onFailure {
                if (generation == renderGeneration) {
                    _state.update { it.copy(isRendering = false, isLoading = false) }
                }
            }
        }
    }

    private fun generatePresetPreviews(source: Bitmap) {
        thumbnailJob?.cancel()
        val thumbnail = makePreview(source, PRESET_PREVIEW_EDGE)
        thumbnailJob = viewModelScope.launch {
            val previews = linkedMapOf<String, Bitmap>()
            val allPresets = PresetCatalog.presets + _state.value.importedPresets
            for (preset in allPresets) {
                val result = withContext(Dispatchers.Default) {
                    ImageProcessor.apply(thumbnail, preset.adjustments, 1f)
                }
                previews[preset.id] = result
            }
            _state.update { it.copy(presetPreviews = previews) }
            if (thumbnail !== source) thumbnail.recycle()
        }
    }

    private fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            if (longest > maxEdge) {
                val scale = maxEdge / longest.toFloat()
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }

    private fun makePreview(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxEdge) return source
        val scale = maxEdge / longest.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.')?.take(32) ?: "Photo"
    }

    private fun Throwable.userMessage(fallback: String): String =
        message?.takeIf { it.isNotBlank() }?.let { "$fallback $it" } ?: fallback

    private companion object {
        const val MAX_PREVIEW_EDGE = 1400
        const val MAX_EXPORT_EDGE = 3072
        const val PRESET_PREVIEW_EDGE = 180
        const val MAX_IMPORT_FILES = 50
        const val MAX_SAVED_PRESETS = 100
    }
}
