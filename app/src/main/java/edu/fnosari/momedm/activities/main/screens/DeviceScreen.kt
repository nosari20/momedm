package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.activities.main.components.AppPickerDialog
import edu.fnosari.momedm.ui.common.AccentPill
import edu.fnosari.momedm.ui.common.SectionLabel
import java.text.DateFormat
import java.util.Date

/** One child's detail page: live status card, start/stop + app-picker + relock, install/account/refresh, rename. */
@Composable
fun DeviceScreen(navController: NavHostController, viewModel: ControllerViewModel, deviceId: String) {
    val devices by viewModel.devices.collectAsState()
    val online by viewModel.online.collectAsState()
    val appsFor by viewModel.appsFor.collectAsState()
    val d = devices.firstOrNull { it.deviceId == deviceId }
    val isOnline = deviceId in online
    val yes = stringResource(R.string.yes); val no = stringResource(R.string.no)
    var pkg by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    val s = d?.lastStatus
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(d?.nickname ?: d?.model ?: deviceId, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.child_rename)) }
        }
        AccentPill(
            text = stringResource(if (isOnline) R.string.child_online else R.string.child_offline),
            accent = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.child_page_status))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_allowed_apps)); Text(s?.let { stringResource(R.string.child_allowed_count, it.kioskApps.size) } ?: "—") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_single_app)); Text(s?.kioskPkg ?: "—") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_current_app)); Text(s?.currentApp ?: "—") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_battery)); Text(s?.let { "${it.battery}%" } ?: "—") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_google)); Text(s?.let { if (it.account) yes else no } ?: "—") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_last_seen)); Text(d?.let { DateFormat.getDateTimeInstance().format(Date(it.lastSeen)) } ?: "—") }
                if (s?.kioskPaused == true) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.child_paused_until, s.pauseEndsAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "—"))
                }
            }
        }
        Button(onClick = { if (s?.kiosk == true) viewModel.kioskOff(deviceId) else viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth()) {
            Text(stringResource(if (s?.kiosk == true) R.string.child_stop else R.string.child_start))
        }
        OutlinedButton(onClick = { viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_choose_apps)) }
        if (s?.kioskPaused == true) OutlinedButton(onClick = { viewModel.relock(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_relock)) }
        OutlinedTextField(value = pkg, onValueChange = { pkg = it }, label = { Text(stringResource(R.string.child_install_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.install(deviceId, pkg.trim()) }, enabled = pkg.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_install)) }
        OutlinedButton(onClick = { viewModel.addAccount(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_add_account)) }
        OutlinedButton(onClick = { viewModel.refresh(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_refresh)) }
    }
    appsFor?.let { (id, apps) ->
        if (id == deviceId) AppPickerDialog(apps, initiallySelected = s?.kioskApps?.toSet() ?: emptySet(), initiallyPinned = s?.kioskPkg,
            onConfirm = { selectedApps, pinned -> viewModel.kioskOn(deviceId, selectedApps, pinned) }, onDismiss = { viewModel.clearApps() })
    }
    if (renaming) {
        var name by remember { mutableStateOf(d?.nickname ?: "") }
        AlertDialog(onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.child_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.child_rename_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { viewModel.rename(deviceId, name); renaming = false }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } })
    }
}
