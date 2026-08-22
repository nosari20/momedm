package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.protocol.AppInfo

/** Multi-select of the child's apps (null = still loading) with an optional "pin a single app" choice. */
@Composable
fun AppPickerDialog(apps: List<AppInfo>?, initiallySelected: Set<String>, initiallyPinned: String?,
                    onConfirm: (apps: List<String>, pinned: String?) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(initiallySelected) }
    var pinOne by remember { mutableStateOf(initiallyPinned != null) }
    var pinned by remember { mutableStateOf(initiallyPinned) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_title)) },
        confirmButton = { TextButton(enabled = selected.isNotEmpty() && (!pinOne || pinned in selected),
            onClick = { onConfirm(selected.toList(), if (pinOne) pinned else null) }) { Text(stringResource(R.string.apps_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_cancel)) } },
        text = {
            if (apps == null) { Column { CircularProgressIndicator(); Text(stringResource(R.string.apps_loading)) } }
            else Column {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.apps_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.apps_pin_one), Modifier.weight(1f)); Switch(checked = pinOne, onCheckedChange = { pinOne = it; if (!it) pinned = null })
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }, key = { it.pkg }) { a ->
                        val checked = a.pkg in selected
                        Row(Modifier.fillMaxWidth().clickable {
                            if (checked && pinned == a.pkg) pinned = null
                            selected = if (checked) selected - a.pkg else selected + a.pkg
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(a.label, style = MaterialTheme.typography.bodyLarge); Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (pinOne && checked) RadioButton(selected = pinned == a.pkg, onClick = { pinned = a.pkg })
                        }
                    }
                }
            }
        })
}
