package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.activities.main.components.AppPickerDialog
import edu.fnosari.momedm.activities.main.components.AppConfigDialog
import edu.fnosari.momedm.activities.main.components.AppConfigLoadingDialog
import edu.fnosari.momedm.activities.main.components.SafetyDialog
import kotlinx.serialization.json.JsonObject
import edu.fnosari.momedm.protocol.SafetyConfig
import edu.fnosari.momedm.protocol.SafetyLevel
import edu.fnosari.momedm.activities.main.components.TimeRangeRow
import edu.fnosari.momedm.protocol.LockSchedule
import edu.fnosari.momedm.protocol.LockState
import edu.fnosari.momedm.ui.common.AccentPill
import edu.fnosari.momedm.ui.common.SectionLabel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** One child's detail page: live status card, start/stop + app-picker + relock, install/account/refresh, rename. */
@Composable
fun DeviceScreen(navController: NavHostController, viewModel: ControllerViewModel, deviceId: String) {
    val devices by viewModel.devices.collectAsState()
    val online by viewModel.online.collectAsState()
    val appsFor by viewModel.appsFor.collectAsState()
    val lastCmd by viewModel.lastCommand.collectAsState()
    val d = devices.firstOrNull { it.deviceId == deviceId }
    val isOnline = deviceId in online
    val yes = stringResource(R.string.yes); val no = stringResource(R.string.no)
    var pkg by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var editingSafety by remember { mutableStateOf(false) }
    var pickingConfigApp by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    // Captured when the app is chosen: the picker's list is cleared before the form opens, so the
    // label has to be remembered here or the form can only show a package name.
    var configAppLabel by remember { mutableStateOf("") }
    val s = d?.lastStatus
    // Self-freshening (silently): the stored status can be minutes old, or from yesterday after
    // an app restart with the pill still showing green. No snackbar — the parent didn't ask.
    LaunchedEffect(deviceId, isOnline) { if (isOnline) viewModel.refreshSilent(deviceId) }
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(d?.nickname ?: d?.model ?: deviceId, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.child_rename)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AccentPill(
                text = stringResource(if (isOnline) R.string.child_online else R.string.child_offline),
                accent = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            // The page-defining state change lives up here beside the state it changes, not buried
            // mid-scroll (P2). Stopping is confirmed, starting is not: only one of them hands a
            // child back an unrestricted phone — same company as the PIN-removal confirm.
            Button(
                onClick = { if (s?.kiosk == true) confirmStop = true else viewModel.requestApps(deviceId) },
                enabled = isOnline,
            ) { Text(stringResource(if (s?.kiosk == true) R.string.child_stop else R.string.child_start)) }
        }
        // P4: one stateful line instead of paired snackbars — "Sent — waiting" is replaced in
        // place by the outcome; a failure stays until tapped away.
        lastCmd?.takeIf { it.deviceId == deviceId }?.let { cmd ->
            LaunchedEffect(cmd) {
                if (cmd.phase == ControllerViewModel.CmdPhase.OK) { delay(4_000L); viewModel.clearCommand() }
            }
            Text(
                cmd.text,
                style = MaterialTheme.typography.bodyMedium,
                color = when (cmd.phase) {
                    ControllerViewModel.CmdPhase.FAILED -> MaterialTheme.colorScheme.error
                    ControllerViewModel.CmdPhase.OK -> MaterialTheme.colorScheme.primary
                    ControllerViewModel.CmdPhase.SENT -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.clickable { viewModel.clearCommand() },
            )
        }
        if (s?.kioskPaused == true) OutlinedButton(onClick = { viewModel.relock(deviceId) }, Modifier.fillMaxWidth(), enabled = isOnline) { Text(stringResource(R.string.child_relock)) }
        // Offline, every control below is dead: commands go out over BLE and are not queued, so a tap
        // produces one snackbar and nothing else. A parent working through several changes on a phone
        // that has gone out of range would otherwise collect a string of them, or miss them entirely
        // while scrolling. Say it once, here, and disable what cannot work.
        if (!isOnline) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.child_offline_banner),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.child_page_status))
                    // Beside the data it refreshes, not exiled to the bottom of the scroll.
                    IconButton(onClick = { viewModel.refresh(deviceId) }, enabled = isOnline) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.child_refresh))
                    }
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_allowed_apps)); Text(s?.let { stringResource(R.string.child_allowed_count, it.kioskApps.size) } ?: "—") }
                // "Single app" is "—" almost always; show the row only when it says something.
                s?.kioskPkg?.let { one -> Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_single_app)); Text(prettyAppName(one)) } }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_current_app)); Text(s?.currentApp?.let(::prettyAppName) ?: "—") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_battery)); Text(s?.let { "${it.battery}%" } ?: "—") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_google)); Text(s?.let { if (it.account) yes else no } ?: "—") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.child_last_seen)); Text(d?.let { DateUtils.getRelativeTimeSpanString(it.lastSeen).toString() } ?: "—") }
                if (s?.kioskPaused == true) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.child_paused_until, s.pauseEndsAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "—"))
                }
            }
        }
        // P2: everything about apps in one card — which are allowed, per-app settings, and
        // installing a new one — right after the status a parent reads first.
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.child_apps_section))
                OutlinedButton(onClick = { viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth(), enabled = isOnline) {
                    Text(stringResource(R.string.child_choose_apps))
                }
                OutlinedButton(onClick = { pickingConfigApp = true; viewModel.requestApps(deviceId) }, Modifier.fillMaxWidth(), enabled = isOnline) {
                    Text(stringResource(R.string.appcfg_open))
                }
                OutlinedTextField(value = pkg, onValueChange = { pkg = it }, label = { Text(stringResource(R.string.child_install_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { viewModel.install(deviceId, pkg.trim()) }, enabled = isOnline && pkg.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.child_install)) }
            }
        }
        val schedule = s?.schedule ?: LockSchedule()
        // With no status yet, these times would be rendering defaults, not facts about the
        // child's phone — show "—" and disable, rather than teach the parent to distrust the page.
        val scheduleKnown = s?.schedule != null
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.child_night_section))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.child_night_enable), modifier = Modifier.weight(1f))
                    Switch(checked = schedule.enabled, enabled = isOnline && scheduleKnown, onCheckedChange = { viewModel.setSchedule(deviceId, schedule.copy(enabled = it)) })
                }
                TimeRangeRow(stringResource(R.string.child_night_school), schedule.weekdayStart, schedule.weekdayEnd, enabled = isOnline && scheduleKnown, known = scheduleKnown) { st, en ->
                    viewModel.setSchedule(deviceId, schedule.copy(weekdayStart = st, weekdayEnd = en))
                }
                TimeRangeRow(stringResource(R.string.child_night_weekend), schedule.weekendStart, schedule.weekendEnd, enabled = isOnline && scheduleKnown, known = scheduleKnown) { st, en ->
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
                        enabled = isOnline,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.child_unlock)) }
                    s?.locked != true -> OutlinedButton(
                        onClick = { viewModel.lockNow(deviceId) },
                        enabled = isOnline,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.child_lock_now)) }
                }
            }
        }
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.safety_section))
                // The level's wording is a full sentence, so it wraps. SpaceBetween put the wrapped
                // value flush against the label with no gap at all ("LevelModerate - block adult...").
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.safety_level))
                    Text(
                        stringResource(
                            when (s?.safetyLevel) {
                                SafetyLevel.STRICT -> R.string.safety_strict
                                SafetyLevel.MODERATE -> R.string.safety_moderate
                                else -> R.string.safety_off
                            },
                        ),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                }
                OutlinedButton(onClick = { editingSafety = true }, Modifier.fillMaxWidth(), enabled = isOnline) {
                    Text(stringResource(R.string.safety_change))
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.child_phone_section))
                OutlinedButton(onClick = { viewModel.addAccount(deviceId) }, Modifier.fillMaxWidth(), enabled = isOnline) { Text(stringResource(R.string.child_add_account)) }
            }
        }
    }
    appsFor?.let { (id, apps) ->
        // Both pickers are driven by the same app list the child reports, so this one stands aside
        // when the list was requested in order to choose an app to configure — otherwise asking for
        // advanced settings opens the allowed-apps picker instead.
        if (id == deviceId && !pickingConfigApp) AppPickerDialog(apps, initiallySelected = s?.kioskApps?.toSet() ?: emptySet(), initiallyPinned = s?.kioskPkg,
            // "Start child mode" opens this picker rather than flipping a switch; when it does,
            // say what confirming the ticks actually means for the child's phone.
            supportingText = if (s?.kiosk == true) null else stringResource(R.string.apps_start_hint),
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
            if (entries == null) AppConfigLoadingDialog(configAppLabel.ifBlank { pkg }) { viewModel.clearSchema() }
            else AppConfigDialog(
                label = configAppLabel.ifBlank { pkg },
                entries = entries,
                current = s?.safety?.appConfigs?.get(pkg) ?: JsonObject(emptyMap()),
                onConfirm = { values -> viewModel.setAppConfig(deviceId, s?.safety, pkg, values); viewModel.clearSchema() },
                onDismiss = { viewModel.clearSchema() },
                // Offered only when something is actually stored for this app.
                onRemoveAll = if (s?.safety?.appConfigs?.containsKey(pkg) == true) {
                    { viewModel.removeAppConfig(deviceId, s.safety, pkg); viewModel.clearSchema() }
                } else null,
            )
        }
    }
    if (editingSafety) SafetyDialog(
        // What the child actually holds, so the resolver field opens on the resolver in force rather
        // than blank — and so the level change merges into the per-app settings instead of losing them.
        current = s?.safety ?: SafetyConfig.of(s?.safetyLevel ?: SafetyLevel.OFF, null),
        onConfirm = { level, dns -> viewModel.setSafety(deviceId, s?.safety, level, dns); editingSafety = false },
        onDismiss = { editingSafety = false },
    )
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text(stringResource(R.string.child_stop)) },
        text = { Text(stringResource(R.string.child_stop_confirm)) },
        confirmButton = {
            TextButton(onClick = { viewModel.kioskOff(deviceId); confirmStop = false }) {
                Text(stringResource(R.string.child_stop))
            }
        },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
    )
    if (renaming) {
        var name by remember { mutableStateOf(d?.nickname ?: "") }
        AlertDialog(onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.child_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.child_rename_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { viewModel.rename(deviceId, name.trim().ifEmpty { null }); renaming = false }) { Text(stringResource(R.string.settings_dialog_confirm)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } })
    }
}

/**
 * Fallback prettifier for a package id ("com.google.android.youtube" -> "Youtube"): the status card
 * must never answer "what is my child doing?" with a raw package id. Labels the child reports stay
 * the better source where a list has been fetched; this covers the card between fetches.
 */
private fun prettyAppName(pkg: String): String =
    pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
