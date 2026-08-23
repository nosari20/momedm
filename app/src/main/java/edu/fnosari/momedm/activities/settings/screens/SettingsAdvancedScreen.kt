package edu.fnosari.momedm.activities.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.controller.ControllerLink
import edu.fnosari.momedm.controller.ControllerService
import edu.fnosari.momedm.controller.provisioning.ControllerIdentity
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.Crypto
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import kotlinx.coroutines.launch

/**
 * Controller identity settings: shows the parent id and a secret fingerprint, with a rotate
 * action. Split out of the former `SettingsControllerScreen`, which also held Parent-PIN UI
 * (now [SettingsPinScreen]).
 */
@Composable
fun SettingsAdvancedScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember { ControllerPrefs(DataStorePreferencesProvider(context)) }
    val scope = rememberCoroutineScope()
    var identity by remember { mutableStateOf<ControllerIdentity?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { identity = prefs.ensureIdentity() }
    BasicLayoutWithTopBar(title = stringResource(R.string.settings_advanced), leftAction = { navController.popBackStack() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.parent_id), style = MaterialTheme.typography.labelLarge)
            Text(identity?.controllerId ?: "…", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.pairing_key_fp), style = MaterialTheme.typography.labelLarge)
            // Show only a fingerprint, never the secret itself.
            Text(identity?.let { Crypto.hmacHex(it.secretBytes, "fingerprint").take(16) } ?: "…", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { confirm = true }) { Text(stringResource(R.string.regenerate_key)) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text(stringResource(R.string.regenerate_key_warning)) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    identity = prefs.regenerateSecret()
                    // Rotating the secret while the service is running needs an in-place restart of its BLE
                    // server/session state with the new identity — a separate stop() + start() pair is async
                    // and can collapse into a no-op (or race), leaving the running SessionManager on the old secret.
                    if (ControllerLink.advertising.value) ControllerService.reloadIdentity(context)
                    info = context.getString(R.string.regenerate_key_done)
                }
            }) { Text(stringResource(R.string.settings_dialog_confirm)) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
    )
}
