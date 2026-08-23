package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.ui.common.AccentPill
import edu.fnosari.momedm.ui.theme.pastelOf

/**
 * Multi-select of the child's apps (null = still loading) with an optional "pin a single app" choice.
 *
 * [singleChoice] reuses the same list to pick exactly one app — the advanced settings form needs to
 * ask "which app?" and this list, with its search box, is already the right answer. In that mode the
 * pin switch and the allowed-count pill are hidden, since neither means anything for a single pick,
 * and the row acts as a menu entry: tapping an app opens it, with no tick to place and nothing to
 * confirm. Ticking a box and then confirming asks for two taps to express one choice.
 */
@Composable
fun AppPickerDialog(apps: List<AppInfo>?, initiallySelected: Set<String>, initiallyPinned: String?,
                    onConfirm: (apps: List<String>, pinned: String?) -> Unit, onDismiss: () -> Unit,
                    singleChoice: Boolean = false, title: String? = null) {
    var selected by remember { mutableStateOf(initiallySelected) }
    var pinOne by remember { mutableStateOf(initiallyPinned != null) }
    var pinned by remember { mutableStateOf(initiallyPinned) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title ?: stringResource(R.string.apps_title))
                if (apps != null && !singleChoice) AccentPill(text = stringResource(R.string.child_allowed_count, selected.size), accent = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            if (!singleChoice) TextButton(enabled = selected.isNotEmpty() && (!pinOne || pinned in selected),
                onClick = { onConfirm(selected.toList(), if (pinOne) pinned else null) }) { Text(stringResource(R.string.apps_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_cancel)) } },
        text = {
            if (apps == null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text(stringResource(R.string.apps_loading)) }
            } else Column {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!singleChoice) Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp).background(pastelOf(MaterialTheme.colorScheme.primary), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.apps_pin_one), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = pinOne, onCheckedChange = { pinOne = it; if (!it) pinned = null })
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }, key = { it.pkg }) { a ->
                        val checked = !singleChoice && a.pkg in selected
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    // One tap is the whole choice: hand the app straight back.
                                    if (singleChoice) { onConfirm(listOf(a.pkg), null); return@clickable }
                                    if (checked && pinned == a.pkg) pinned = null
                                    selected = if (checked) selected - a.pkg else selected + a.pkg
                                }
                                .background(if (checked) pastelOf(MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!singleChoice) Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(a.label, style = MaterialTheme.typography.bodyLarge); Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (singleChoice) Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (pinOne && checked) RadioButton(selected = pinned == a.pkg, onClick = { pinned = a.pkg })
                        }
                    }
                }
            }
        })
}
