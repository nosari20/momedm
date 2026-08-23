package edu.fnosari.momedm.activities.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.PinHash
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import kotlinx.coroutines.launch

/**
 * Parent-PIN settings: set/change/remove, pushed to every online child via `SET_PREFS`
 * (see [ControllerLink.prefsChanged]). Split out of the former `SettingsControllerScreen`.
 */
@Composable
fun SettingsPinScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember { ControllerPrefs(DataStorePreferencesProvider(context)) }
    val scope = rememberCoroutineScope()
    val pinSet by prefs.pinSet.collectAsState(false)
    var pinDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    BasicLayoutWithTopBar(title = stringResource(R.string.settings_pin), leftAction = { navController.popBackStack() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (pinSet) R.string.pin_status_set else R.string.pin_status_unset), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { pinDialog = true }) { Text(stringResource(if (pinSet) R.string.pin_change else R.string.pin_set)) }
            if (pinSet) OutlinedButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.pin_remove)) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.pin_remove)) },
        text = { Text(stringResource(R.string.pin_clear_warning)) },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch { prefs.clearPin(); ControllerLink.prefsChanged.tryEmit(Unit); info = context.getString(R.string.pin_removed) }
            }) { Text(stringResource(R.string.settings_dialog_confirm)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
    )

    if (pinDialog) {
        var pin by remember { mutableStateOf("") }
        var pinConfirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text(stringResource(if (pinSet) R.string.pin_change else R.string.pin_set)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it; error = null },
                        label = { Text(stringResource(R.string.pin_digits)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pinConfirm, onValueChange = { pinConfirm = it; error = null },
                        label = { Text(stringResource(R.string.pin_confirm)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        !PinHash.isValidPin(pin) -> error = context.getString(R.string.pin_invalid)
                        pin != pinConfirm -> error = context.getString(R.string.pin_mismatch)
                        else -> scope.launch {
                            if (prefs.setPin(pin)) {
                                ControllerLink.prefsChanged.tryEmit(Unit)
                                info = context.getString(R.string.pin_saved)
                                pinDialog = false
                            } else error = context.getString(R.string.pin_invalid)
                        }
                    }
                }) { Text(stringResource(R.string.settings_dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
        )
    }
}
