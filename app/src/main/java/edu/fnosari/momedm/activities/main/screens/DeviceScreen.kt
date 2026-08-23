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
import androidx.compose.material3.Switch
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
import edu.fnosari.momedm.activities.main.components.AppConfigDialog
import edu.fnosari.momedm.activities.main.components.SafetyDialog
import kotlinx.serialization.json.JsonObject
import edu.fnosari.momedm.protocol.SafetyConfig
import edu.fnosari.momedm.protocol.SafetyLevel
import edu.fnosari.momedm.activities.main.components.TimeRangeRow
import edu.fnosari.momedm.protocol.LockSchedule
import edu.fnosari.momedm.protocol.LockState
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
    var editingSafety by remember { mutableStateOf(false) }
    var pickingConfigApp by remember { mutableStateOf(false) }
    // Captured when the app is chosen: the picker's list is cleared before the form opens, so the
    // label has to be remembered here or the form can only show a package name.
    var configAppLabel by remember { mutableStateOf("") }
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
        val schedule = s?.schedule ?: LockSchedule()
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.child_night_section))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.child_night_enable), modifier = Modifier.weight(1f))
                    Switch(checked = schedule.enabled, onCheckedChange = { viewModel.setSchedule(deviceId, schedule.copy(enabled = it)) })
                }
                TimeRangeRow(stringResource(R.string.child_night_school), schedule.weekdayStart, schedule.weekdayEnd) { st, en ->
                    viewModel.setSchedule(deviceId, schedule.copy(weekdayStart = st, weekdayEnd = en))
                }
                TimeRangeRow(stringResource(R.string.child_night_weekend), schedule.weekendStart, schedule.weekendEnd) { st, en ->
                    viewModel.setSchedule(deviceId, schedule.copy(weekendStart = st, weekendEnd = en))
                }
                Text(
                    when {
                        s?.lockReason == LockState.REASON_MANUAL -> stringResource(R.string.child_locked_manual)
                        s?.locked == true -> stringResource(R.string.child_locked_until,
                            s.lockUntil?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "—")
                        else -> stringResource(R.string.child_unlocked)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // The button only ever performs an action UNLOCK can actually undo: clearing a manual
                // lock. Offering it during a night lock would send UNLOCK, which clears an
                // already-false manualLock and changes nothing (still inside the window) while the
                // parent gets a success toast — see the brief. There is deliberately no "cancel
                // tonight's window" action here; that needs new persisted state this fix does not add.
                when {
                    s?.lockReason == LockState.REASON_MANUAL -> OutlinedButton(
                        onClick = { viewModel.unlock(deviceId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.child_unlock)) }
                    s?.locked != true -> OutlinedButton(
                        onClick = { viewModel.lockNow(deviceId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.child_lock_now)) }
                }
            }
        }
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.safety_section))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.safety_level))
                    Text(
                        stringResource(
                            when (s?.safetyLevel) {
                                SafetyLevel.STRICT -> R.string.safety_strict
                                SafetyLevel.MODERATE -> R.string.safety_moderate
                                else -> R.string.safety_off
                            },
                        ),
                    )
                }
                OutlinedButton(onClick = { editingSafety = true }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.safety_change))
                }
                // Which apps a child may open is the same question as what they can reach, so it sits
                // here beside the level and the per-app settings rather than adrift below the card.
                OutlinedButton(onClick = { viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.child_choose_apps))
                }
                OutlinedButton(onClick = { pickingConfigApp = true; viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appcfg_open))
                }
            }
        }

        Button(onClick = { if (s?.kiosk == true) viewModel.kioskOff(deviceId) else viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth()) {
            Text(stringResource(if (s?.kiosk == true) R.string.child_stop else R.string.child_start))
        }
        if (s?.kioskPaused == true) OutlinedButton(onClick = { viewModel.relock(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_relock)) }
        OutlinedTextField(value = pkg, onValueChange = { pkg = it }, label = { Text(stringResource(R.string.child_install_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.install(deviceId, pkg.trim()) }, enabled = pkg.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_install)) }
        OutlinedButton(onClick = { viewModel.addAccount(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_add_account)) }
        OutlinedButton(onClick = { viewModel.refresh(deviceId) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_refresh)) }
    }
    appsFor?.let { (id, apps) ->
        // Both pickers are driven by the same app list the child reports, so this one stands aside
        // when the list was requested in order to choose an app to configure — otherwise asking for
        // advanced settings opens the allowed-apps picker instead.
        if (id == deviceId && !pickingConfigApp) AppPickerDialog(apps, initiallySelected = s?.kioskApps?.toSet() ?: emptySet(), initiallyPinned = s?.kioskPkg,
            onConfirm = { selectedApps, pinned -> viewModel.kioskOn(deviceId, selectedApps, pinned) }, onDismiss = { viewModel.clearApps() })
    }
    val schema by viewModel.schemaFor.collectAsState()
    // Picking which app to configure reuses the installed-apps list the child already reports.
    appsFor?.let { (id, apps) ->
        if (id == deviceId && pickingConfigApp && apps != null && schema == null) AppPickerDialog(
            apps,
            initiallySelected = emptySet(),
            initiallyPinned = null,
            singleChoice = true,
            title = stringResource(R.string.appcfg_pick),
            onConfirm = { selected, _ ->
                selected.firstOrNull()?.let { pkg ->
                    configAppLabel = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
                    viewModel.requestSchema(deviceId, pkg)
                }
                pickingConfigApp = false; viewModel.clearApps()
            },
            onDismiss = { pickingConfigApp = false; viewModel.clearApps() },
        )
    }
    schema?.let { (id, pkg, entries) ->
        if (id == deviceId) {
            if (entries == null) Text(stringResource(R.string.appcfg_loading))
            else AppConfigDialog(
                label = configAppLabel.ifBlank { pkg },
                entries = entries,
                current = s?.safety?.appConfigs?.get(pkg) ?: JsonObject(emptyMap()),
                onConfirm = { values -> viewModel.setAppConfig(deviceId, s?.safety, pkg, values); viewModel.clearSchema() },
                onDismiss = { viewModel.clearSchema() },
            )
        }
    }
    if (editingSafety) SafetyDialog(
        current = SafetyConfig.of(s?.safetyLevel ?: SafetyLevel.OFF, null),
        onConfirm = { level, dns -> viewModel.setSafety(deviceId, level, dns); editingSafety = false },
        onDismiss = { editingSafety = false },
    )
    if (renaming) {
        var name by remember { mutableStateOf(d?.nickname ?: "") }
        AlertDialog(onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.child_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.child_rename_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { viewModel.rename(deviceId, name); renaming = false }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } })
    }
}
