package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.protocol.SafetyConfig
import edu.fnosari.momedm.protocol.SafetyLevel

/**
 * Lets the parent pick a content-restriction level and the filtering DNS resolver.
 *
 * The copy is deliberately honest about the shape of what this can and cannot do: browser settings
 * only reach Chrome, and the YouTube *app* is reachable only through the DNS filter, because it
 * declares no managed configuration for any MDM to set.
 */
@Composable
fun SafetyDialog(current: SafetyConfig, onConfirm: (SafetyLevel, String?) -> Unit, onDismiss: () -> Unit) {
    var level by remember { mutableStateOf(current.level) }
    var dns by remember { mutableStateOf(current.dnsHost) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.safety_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in SafetyLevel.entries) {
                    Choice(
                        selected = level == l,
                        label = stringResource(
                            when (l) {
                                SafetyLevel.OFF -> R.string.safety_off
                                SafetyLevel.MODERATE -> R.string.safety_moderate
                                SafetyLevel.STRICT -> R.string.safety_strict
                            },
                        ),
                        onClick = { level = l },
                    )
                }
                Text(stringResource(R.string.safety_dns), style = MaterialTheme.typography.labelLarge)
                Choice(dns == SafetyConfig.DNS_CLEANBROWSING, stringResource(R.string.safety_dns_cleanbrowsing)) { dns = SafetyConfig.DNS_CLEANBROWSING }
                Choice(dns == SafetyConfig.DNS_ADGUARD, stringResource(R.string.safety_dns_adguard)) { dns = SafetyConfig.DNS_ADGUARD }
                Choice(dns == null, stringResource(R.string.safety_dns_none)) { dns = null }
                Text(stringResource(R.string.safety_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(level, dns) }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
    )
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
