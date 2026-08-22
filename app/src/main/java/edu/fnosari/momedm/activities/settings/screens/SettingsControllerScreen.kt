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

/** Controller identity settings: shows the controller id and a secret fingerprint, with a rotate action. */
@Composable
fun SettingsControllerScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember { ControllerPrefs(DataStorePreferencesProvider(context)) }
    val scope = rememberCoroutineScope()
    var identity by remember { mutableStateOf<ControllerIdentity?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { identity = prefs.ensureIdentity() }
    BasicLayoutWithTopBar(title = stringResource(R.string.settings_screen_category_controller), leftAction = { navController.popBackStack() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_controller_id), style = MaterialTheme.typography.labelLarge)
            Text(identity?.controllerId ?: "…", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_controller_secret), style = MaterialTheme.typography.labelLarge)
            // Show only a fingerprint, never the secret itself.
            Text(identity?.let { Crypto.hmacHex(it.secretBytes, "fingerprint").take(16) } ?: "…", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { confirm = true }) { Text(stringResource(R.string.settings_controller_regenerate)) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        text = { Text(stringResource(R.string.settings_controller_regenerate_warning)) },
        confirmButton = { TextButton(onClick = {
            confirm = false
            scope.launch {
                identity = prefs.regenerateSecret()
                if (ControllerLink.advertising.value) { ControllerService.stop(context); ControllerService.start(context) }
                info = context.getString(R.string.settings_controller_regenerated)
            }
        }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } })
}
