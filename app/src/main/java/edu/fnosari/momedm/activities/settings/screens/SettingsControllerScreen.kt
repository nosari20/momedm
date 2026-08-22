package edu.fnosari.momedm.activities.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.controller.ControllerLink
import edu.fnosari.momedm.controller.ControllerService
import edu.fnosari.momedm.controller.provisioning.ControllerIdentity
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.Crypto
import edu.fnosari.momedm.protocol.PinHash
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
    var pinDialog by remember { mutableStateOf(false) }
    var confirmClearPin by remember { mutableStateOf(false) }
    val pinSet by prefs.pinSet.collectAsState(false)
    LaunchedEffect(Unit) { identity = prefs.ensureIdentity() }
    BasicLayoutWithTopBar(title = stringResource(R.string.settings_screen_category_controller), leftAction = { navController.popBackStack() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_pin_title), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(if (pinSet) R.string.settings_pin_status_set else R.string.settings_pin_status_unset), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { pinDialog = true }) { Text(stringResource(if (pinSet) R.string.settings_pin_change else R.string.settings_pin_set)) }
            if (pinSet) OutlinedButton(onClick = { confirmClearPin = true }) { Text(stringResource(R.string.settings_pin_clear)) }
            HorizontalDivider()
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
                // Rotating the secret while the service is running needs an in-place restart of its BLE
                // server/session state with the new identity — a separate stop() + start() pair is async
                // and can collapse into a no-op (or race), leaving the running SessionManager on the old secret.
                if (ControllerLink.advertising.value) ControllerService.reloadIdentity(context)
                info = context.getString(R.string.settings_controller_regenerated)
            }
        }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } })
    if (confirmClearPin) AlertDialog(onDismissRequest = { confirmClearPin = false },
        text = { Text(stringResource(R.string.settings_pin_clear)) },
        confirmButton = { TextButton(onClick = {
            confirmClearPin = false
            scope.launch { prefs.clearPin(); ControllerLink.prefsChanged.tryEmit(Unit) }
        }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmClearPin = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } })
    if (pinDialog) {
        var pin by remember { mutableStateOf("") }
        var pinConfirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { pinDialog = false },
            title = { Text(stringResource(if (pinSet) R.string.settings_pin_change else R.string.settings_pin_set)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pin, onValueChange = { pin = it; error = null }, label = { Text(stringResource(R.string.settings_pin_hint)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pinConfirm, onValueChange = { pinConfirm = it; error = null }, label = { Text(stringResource(R.string.settings_pin_confirm_hint)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = {
                when {
                    !PinHash.isValidPin(pin) -> error = context.getString(R.string.settings_pin_invalid)
                    pin != pinConfirm -> error = context.getString(R.string.settings_pin_mismatch)
                    else -> scope.launch {
                        if (prefs.setPin(pin)) {
                            ControllerLink.prefsChanged.tryEmit(Unit)
                            info = context.getString(R.string.settings_pin_saved)
                            pinDialog = false
                        } else error = context.getString(R.string.settings_pin_invalid)
                    }
                }
            }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } })
    }
}
