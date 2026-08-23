package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.protocol.EntryType
import edu.fnosari.momedm.protocol.SchemaEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A form built from an app's *own* declared settings, so any app that supports managed configuration
 * can be configured without this app knowing anything about it in advance.
 *
 * Groups of fields are rendered inline, and lists of groups — a list of servers, of bookmarks — can
 * have entries added and removed, since that is the shape apps reach for whenever a setting is more
 * than one value. Anything left that a form cannot represent is listed as not editable rather than
 * hidden, so a parent looking for a setting learns it exists but cannot be set here.
 *
 * [current] is what the child already holds for this package, so opening the form shows the values in
 * force rather than blanks, and saving does not silently reset the ones left untouched.
 */
@Composable
fun AppConfigDialog(
    label: String,
    entries: List<SchemaEntry>,
    current: JsonObject,
    onConfirm: (JsonObject) -> Unit,
    onDismiss: () -> Unit,
) {
    // Only keys the parent actually touches are written back; everything else is carried over from
    // `current` untouched on save.
    val edits = remember { mutableStateMapOf<String, JsonElement>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.appcfg_none), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.appcfg_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    for (e in entries) {
                        val value = edits[e.key] ?: current[e.key]
                        Field(e, value) { edits[e.key] = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = entries.isNotEmpty(),
                onClick = { onConfirm(JsonObject(current + edits)) },
            ) { Text(stringResource(R.string.settings_dialog_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
    )
}

/**
 * Shown while the child is being asked what an app declares.
 *
 * This is not instant: Chrome declares hundreds of settings and the answer crosses a BLE link, which
 * takes a few seconds. Without a dialog here the picker simply closed and nothing appeared, so the
 * parent concluded their tap had not registered and pressed the button again.
 */
@Composable
fun AppConfigLoadingDialog(label: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.appcfg_loading), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
    )
}

@Composable
private fun Field(entry: SchemaEntry, value: JsonElement?, onChange: (JsonElement) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (entry.type) {
            EntryType.BOOLEAN -> Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = (value as? JsonPrimitive)?.booleanOrNull == true,
                    onCheckedChange = { onChange(JsonPrimitive(it)) },
                )
            }

            EntryType.CHOICE -> {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                val selected = (value as? JsonPrimitive)?.content
                entry.choiceValues.forEachIndexed { i, v ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == v, onClick = { onChange(JsonPrimitive(v)) })
                        Text(entry.choiceLabels.getOrElse(i) { v }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            EntryType.MULTI_SELECT -> {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                val chosen = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toSet()
                entry.choiceValues.forEachIndexed { i, v ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = v in chosen,
                            onCheckedChange = { on ->
                                val next = if (on) chosen + v else chosen - v
                                onChange(JsonArray(next.map { JsonPrimitive(it) }))
                            },
                        )
                        Text(entry.choiceLabels.getOrElse(i) { v }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            EntryType.INTEGER -> OutlinedTextField(
                value = (value as? JsonPrimitive)?.intOrNull?.toString().orEmpty(),
                onValueChange = { t -> t.toIntOrNull()?.let { onChange(JsonPrimitive(it)) } },
                label = { Text(entry.title) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            EntryType.STRING -> OutlinedTextField(
                value = (value as? JsonPrimitive)?.content.orEmpty(),
                onValueChange = { onChange(JsonPrimitive(it)) },
                label = { Text(entry.title) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // A group of fields: rendered inline and indented, writing into a nested object.
            EntryType.BUNDLE -> {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                val obj = value as? JsonObject ?: JsonObject(emptyMap())
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (child in entry.itemFields) {
                        Field(child, obj[child.key]) { v -> onChange(JsonObject(obj + (child.key to v))) }
                    }
                }
            }

            // A repeatable list of groups — a list of servers, bookmarks and the like. The schema
            // declares one item's shape; the parent adds and removes as many as they need.
            EntryType.BUNDLE_ARRAY -> {
                val items = (value as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                Text(
                    stringResource(R.string.appcfg_items, entry.title, items.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                items.forEachIndexed { index, item ->
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (child in entry.itemFields) {
                            Field(child, item[child.key]) { v ->
                                val updated = JsonObject(item + (child.key to v))
                                onChange(JsonArray(items.toMutableList().also { it[index] = updated }))
                            }
                        }
                        TextButton(onClick = {
                            onChange(JsonArray(items.toMutableList().also { it.removeAt(index) }))
                        }) { Text(stringResource(R.string.appcfg_remove)) }
                    }
                }
                TextButton(onClick = { onChange(JsonArray(items + JsonObject(emptyMap()))) }) {
                    Text(stringResource(R.string.appcfg_add, entry.title))
                }
            }

            // Shown, not hidden: a parent hunting for a setting should learn it exists and that this
            // form cannot set it, rather than conclude the app does not have it.
            EntryType.UNSUPPORTED -> Text(
                stringResource(R.string.appcfg_unsupported, entry.title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.editable) {
            Text(entry.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
