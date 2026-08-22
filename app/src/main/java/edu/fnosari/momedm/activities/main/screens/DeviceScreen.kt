package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.activities.main.components.AppPickerDialog
import java.text.DateFormat
import java.util.Date

@Composable
fun DeviceScreen(navController: NavHostController, viewModel: ControllerViewModel, deviceId: String) {
    val devices by viewModel.devices.collectAsState()
    val online by viewModel.online.collectAsState()
    val appsFor by viewModel.appsFor.collectAsState()
    val d = devices.firstOrNull { it.deviceId == deviceId }
    val yes = stringResource(R.string.yes); val no = stringResource(R.string.no)
    var pkg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(d?.model ?: deviceId, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(if (deviceId in online) R.string.devices_online else R.string.devices_offline), color = if (deviceId in online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.device_status), style = MaterialTheme.typography.titleMedium)
            val s = d?.lastStatus
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_kiosk)); Text(s?.let { if (it.kiosk) it.kioskPkg ?: yes else no } ?: "—") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_account)); Text(s?.let { if (it.account) yes else no } ?: "—") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_battery)); Text(s?.let { "${it.battery}%" } ?: "—") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_current)); Text(s?.currentApp ?: "—") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.device_last_seen)); Text(d?.let { DateFormat.getDateTimeInstance().format(Date(it.lastSeen)) } ?: "—") }
        } }
        Button(onClick = { viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_kiosk_on)) }
        OutlinedButton(onClick = { viewModel.kioskOff(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_kiosk_off)) }
        OutlinedTextField(value = pkg, onValueChange = { pkg = it }, label = { Text(stringResource(R.string.device_install_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.install(deviceId, pkg.trim()) }, enabled = pkg.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_install)) }
        OutlinedButton(onClick = { viewModel.addAccount(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_add_account)) }
        OutlinedButton(onClick = { viewModel.refresh(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_refresh)) }
    }
    appsFor?.let { (id, apps) -> if (id == deviceId) AppPickerDialog(apps, onPick = { viewModel.kioskOn(deviceId, it.pkg) }, onDismiss = { viewModel.clearApps() }) }
}
