package edu.fnosari.momedm.activities.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.ui.theme.Palette

/**
 * The three MaClasse-style colour/choice pickers used by [edu.fnosari.momedm.activities.settings.screens.SettingsAppearanceScreen].
 * Ported from ClassManager's `ui/settings/SettingsScreen.kt` private composables, re-namespaced onto
 * our [R] resources and [Palette], and made public so the appearance screen can drive them.
 */

/** A titled list of radio-button options with a label mapper; used for the language and theme pickers. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    label: @Composable (String) -> String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onPick(option) })
                        Text(label(option), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Grid of [Palette.PRESETS] swatches, with a checkmark on the current [current] colour and a "Custom…" escape hatch. */
@Composable
fun AccentDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accent_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Palette.PRESETS.chunked(4).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { preset ->
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(preset))
                                    .clickable { onPick(preset) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (preset == current) {
                                    Icon(
                                        Icons.Default.Check,
                                        stringResource(R.string.accent_color),
                                        tint = Color(Palette.onColor(preset)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCustom) {
                Text(stringResource(R.string.custom_color))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Free colour choice: hue/saturation/brightness sliders kept in step with a hex field. */
@Composable
fun CustomColorDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember(initial) { Palette.argbToHsv(initial) }
    var hue by remember { mutableFloatStateOf(start.first) }
    var sat by remember { mutableFloatStateOf(start.second) }
    var value by remember { mutableFloatStateOf(start.third) }
    val color = Palette.hsvToArgb(hue, sat, value)
    // the field is only rewritten from the sliders when it is not being edited into a valid colour
    var hex by remember { mutableStateOf(Palette.toHex(initial)) }
    var hexFocused by remember { mutableStateOf(false) }
    if (!hexFocused) hex = Palette.toHex(color)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_color)) },
        text = {
            Column {
                // preview, with the text colour the app would actually put on it
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color(color)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        color = Color(Palette.onColor(color)),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    stringResource(R.string.color_hue),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(
                            Brush.horizontalGradient(
                                (0..6).map { Color(Palette.hsvToArgb(it * 60f, 1f, 1f)) }
                            )
                        )
                )
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text(stringResource(R.string.color_saturation), style = MaterialTheme.typography.labelMedium)
                Slider(value = sat, onValueChange = { sat = it })
                Text(stringResource(R.string.color_brightness), style = MaterialTheme.typography.labelMedium)
                Slider(value = value, onValueChange = { value = it })
                OutlinedTextField(
                    value = hex,
                    onValueChange = { typed ->
                        hex = typed.uppercase()
                        Palette.parseHex(typed)?.let {
                            val (h, s, v) = Palette.argbToHsv(it)
                            hue = h
                            sat = s
                            value = v
                        }
                    },
                    label = { Text(stringResource(R.string.color_hex)) },
                    prefix = { Text("#") },
                    singleLine = true,
                    isError = Palette.parseHex(hex) == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { hexFocused = it.isFocused },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(Palette.parseHex(hex) ?: color) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
