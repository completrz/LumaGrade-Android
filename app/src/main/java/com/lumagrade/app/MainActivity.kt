package com.lumagrade.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumagrade.app.editor.Adjustments
import com.lumagrade.app.editor.EditorPanel
import com.lumagrade.app.editor.EditorUiState
import com.lumagrade.app.editor.EditorViewModel
import com.lumagrade.app.editor.PhotoPreset
import com.lumagrade.app.editor.PresetCatalog
import com.lumagrade.app.ui.Acid
import com.lumagrade.app.ui.Ink
import com.lumagrade.app.ui.LumaGradeTheme
import com.lumagrade.app.ui.Muted
import com.lumagrade.app.ui.Panel
import com.lumagrade.app.ui.SoftPanel
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val editorViewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LumaGradeTheme {
                LumaGradeApp(editorViewModel)
            }
        }
    }
}

@Composable
private fun LumaGradeApp(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.loadPhoto(context, it)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        uri?.let { viewModel.exportPhoto(context, it) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            if (state.originalPreview == null) {
                WelcomeScreen(
                    isLoading = state.isLoading,
                    onOpenPhoto = { photoPicker.launch(arrayOf("image/*")) },
                )
            } else {
                EditorScreen(
                    state = state,
                    onOpenPhoto = { photoPicker.launch(arrayOf("image/*")) },
                    onClose = viewModel::clearPhoto,
                    onReset = viewModel::resetEdits,
                    onExport = {
                        val safeName = state.photoName.replace(Regex("[^A-Za-z0-9_-]"), "-")
                        exporter.launch("${safeName.ifBlank { "photo" }}-lumagrade.jpg")
                    },
                    onPanelChange = viewModel::setPanel,
                    onPresetSelected = viewModel::selectPreset,
                    onIntensityChange = viewModel::setPresetIntensity,
                    onAdjustmentsChange = viewModel::setAdjustments,
                )
            }

            if (state.isExporting) {
                ExportOverlay()
            }
        }
    }
}

@Composable
private fun WelcomeScreen(
    isLoading: Boolean,
    onOpenPhoto: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF171A22), Ink, Color(0xFF11150B)),
                ),
            )
            .padding(28.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Acid, Color(0xFF78DDF7), Color(0xFFB884FF)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "LG",
                    color = Color(0xFF10120D),
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "LumaGrade",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = "Strong presets. Real controls. No account and no uploads.",
                color = Muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onOpenPhoto,
                enabled = !isLoading,
                modifier = Modifier.height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Acid, contentColor = Color(0xFF11150A)),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color(0xFF11150A),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Opening photo…")
                } else {
                    Text("Choose a photo", fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "16 handcrafted looks • JPEG export up to 3072 px",
            modifier = Modifier.align(Alignment.BottomCenter),
            color = Color(0xFF747780),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EditorScreen(
    state: EditorUiState,
    onOpenPhoto: () -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
    onPanelChange: (EditorPanel) -> Unit,
    onPresetSelected: (PhotoPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onAdjustmentsChange: (Adjustments) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        EditorTopBar(
            title = state.photoName,
            onClose = onClose,
            onOpenPhoto = onOpenPhoto,
            onReset = onReset,
            onExport = onExport,
        )
        PhotoStage(state = state, modifier = Modifier.weight(1f))
        EditTray(
            state = state,
            onPanelChange = onPanelChange,
            onPresetSelected = onPresetSelected,
            onIntensityChange = onIntensityChange,
            onAdjustmentsChange = onAdjustmentsChange,
        )
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    onClose: () -> Unit,
    onOpenPhoto: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextAction("×", onClose, large = true)
        TextAction("Open", onOpenPhoto)
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        TextAction("Reset", onReset)
        TextAction("Export", onExport, accent = true)
    }
}

@Composable
private fun TextAction(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    large: Boolean = false,
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (large) 9.dp else 8.dp, vertical = 9.dp),
        color = if (accent) Acid else MaterialTheme.colorScheme.onBackground,
        fontWeight = if (accent) FontWeight.Bold else FontWeight.Medium,
        fontSize = if (large) 28.sp else 13.sp,
    )
}

@Composable
private fun PhotoStage(state: EditorUiState, modifier: Modifier = Modifier) {
    var showingBefore by remember { mutableStateOf(false) }
    val bitmap = if (showingBefore) state.originalPreview else state.editedPreview

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        showingBefore = true
                        tryAwaitRelease()
                        showingBefore = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = if (showingBefore) "Original photo" else "Edited photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            color = Color.Black.copy(alpha = 0.58f),
            shape = RoundedCornerShape(9.dp),
        ) {
            Text(
                text = if (showingBefore) "BEFORE" else "HOLD TO COMPARE",
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
            )
        }

        AnimatedVisibility(
            visible = state.isRendering,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Acid,
                trackColor = Color.Transparent,
            )
        }
    }
}

@Composable
private fun EditTray(
    state: EditorUiState,
    onPanelChange: (EditorPanel) -> Unit,
    onPresetSelected: (PhotoPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onAdjustmentsChange: (Adjustments) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp),
        color = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 4.dp,
    ) {
        Column {
            PanelTabs(selected = state.panel, onSelected = onPanelChange)
            HorizontalDivider(color = Color(0xFF282B32))
            when (state.panel) {
                EditorPanel.Presets -> PresetPanel(
                    selectedId = state.selectedPresetId,
                    intensity = state.presetIntensity,
                    previews = state.presetPreviews,
                    onPresetSelected = onPresetSelected,
                    onIntensityChange = onIntensityChange,
                )
                EditorPanel.Light -> LightControls(state.adjustments, onAdjustmentsChange)
                EditorPanel.Color -> ColorControls(state.adjustments, onAdjustmentsChange)
                EditorPanel.Effects -> EffectControls(state.adjustments, onAdjustmentsChange)
            }
        }
    }
}

@Composable
private fun PanelTabs(selected: EditorPanel, onSelected: (EditorPanel) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 8.dp),
    ) {
        EditorPanel.entries.forEach { panel ->
            val active = panel == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelected(panel) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = panel.label,
                        color = if (active) Acid else Muted,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .background(if (active) Acid else Color.Transparent, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetPanel(
    selectedId: String,
    intensity: Float,
    previews: Map<String, android.graphics.Bitmap>,
    onPresetSelected: (PhotoPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Preset strength", color = Muted, fontSize = 12.sp)
            Slider(
                value = intensity,
                onValueChange = onIntensityChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                colors = editorSliderColors(),
            )
            Text("${(intensity * 100).toInt()}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(PresetCatalog.presets, key = { it.id }) { preset ->
                PresetCard(
                    preset = preset,
                    preview = previews[preset.id],
                    selected = preset.id == selectedId,
                    onClick = { onPresetSelected(preset) },
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: PhotoPreset,
    preview: android.graphics.Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(92.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 92.dp, height = 106.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(preset.swatchStart), Color(preset.swatchEnd)),
                    ),
                )
                .then(
                    if (selected) Modifier.border(2.dp, Acid, RoundedCornerShape(17.dp))
                    else Modifier,
                )
                .clickable(onClick = onClick),
        ) {
            preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "${preset.name} preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp),
                color = Color.Black.copy(alpha = 0.42f),
                shape = RoundedCornerShape(7.dp),
            ) {
                Text(
                    text = preset.collection.uppercase(Locale.US),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(20.dp)
                        .background(Acid, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color(0xFF11150A), fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = preset.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) Acid else MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun LightControls(value: Adjustments, onChange: (Adjustments) -> Unit) {
    ControlList {
        item {
            ControlSlider("Exposure", value.exposure, -1.5f..1.5f, signedDecimal = true) {
                onChange(value.copy(exposure = it))
            }
        }
        item { ControlSlider("Contrast", value.contrast, -1f..1f) { onChange(value.copy(contrast = it)) } }
        item { ControlSlider("Highlights", value.highlights, -1f..1f) { onChange(value.copy(highlights = it)) } }
        item { ControlSlider("Shadows", value.shadows, -1f..1f) { onChange(value.copy(shadows = it)) } }
        item { ControlSlider("Whites", value.whites, -1f..1f) { onChange(value.copy(whites = it)) } }
        item { ControlSlider("Blacks", value.blacks, -1f..1f) { onChange(value.copy(blacks = it)) } }
    }
}

@Composable
private fun ColorControls(value: Adjustments, onChange: (Adjustments) -> Unit) {
    ControlList {
        item { ControlSlider("Temperature", value.temperature, -1f..1f) { onChange(value.copy(temperature = it)) } }
        item { ControlSlider("Tint", value.tint, -1f..1f) { onChange(value.copy(tint = it)) } }
        item { ControlSlider("Vibrance", value.vibrance, -1f..1f) { onChange(value.copy(vibrance = it)) } }
        item { ControlSlider("Saturation", value.saturation, -1f..1f) { onChange(value.copy(saturation = it)) } }
    }
}

@Composable
private fun EffectControls(value: Adjustments, onChange: (Adjustments) -> Unit) {
    ControlList {
        item { ControlSlider("Fade", value.fade, 0f..1f) { onChange(value.copy(fade = it)) } }
        item { ControlSlider("Vignette", value.vignette, 0f..1f) { onChange(value.copy(vignette = it)) } }
        item { ControlSlider("Film grain", value.grain, 0f..1f) { onChange(value.copy(grain = it)) } }
        item { ControlSlider("Sharpen", value.sharpen, 0f..1f) { onChange(value.copy(sharpen = it)) } }
    }
}

@Composable
private fun ControlList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

@Composable
private fun ControlSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    signedDecimal: Boolean = false,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                text = if (signedDecimal) signed(value) else signed((value * 100).toInt()),
                color = if (kotlin.math.abs(value) > 0.001f) Acid else Muted,
                fontSize = 12.sp,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.height(28.dp),
            colors = editorSliderColors(),
        )
    }
}

@Composable
private fun editorSliderColors() = SliderDefaults.colors(
    thumbColor = Acid,
    activeTrackColor = Acid,
    inactiveTrackColor = SoftPanel,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

private fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    else -> value.toString()
}

private fun signed(value: Float): String {
    val formatted = String.format(Locale.US, "%.2f", value)
    return if (value > 0f) "+$formatted" else formatted
}

@Composable
private fun ExportOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.64f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = Panel, shape = RoundedCornerShape(22.dp), tonalElevation = 8.dp) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Acid, strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text("Rendering full-quality photo…", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
