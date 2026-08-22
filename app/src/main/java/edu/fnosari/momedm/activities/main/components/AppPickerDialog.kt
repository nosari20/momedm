package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.protocol.AppInfo

/** Lists apps reported by the managed device; null = still loading. */
@Composable
fun AppPickerDialog(apps: List<AppInfo>?, onPick: (AppInfo) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_cancel)) } },
        title = { Text(stringResource(R.string.apps_title)) },
        text = {
            if (apps == null) { Column { CircularProgressIndicator(); Text(stringResource(R.string.apps_loading)) } }
            else LazyColumn { items(apps) { a ->
                Column(Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 10.dp)) {
                    Text(a.label, style = MaterialTheme.typography.bodyLarge); Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } } }
        })
}
