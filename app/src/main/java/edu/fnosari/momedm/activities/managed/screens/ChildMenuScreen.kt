package edu.fnosari.momedm.activities.managed.screens

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.ManagedViewModel
import edu.fnosari.momedm.activities.managed.RepairScanActivity
import edu.fnosari.momedm.managed.ManagedLinkState.LinkState
import edu.fnosari.momedm.protocol.LockState
import edu.fnosari.momedm.protocol.SafetyLevel
import edu.fnosari.momedm.ui.common.SectionLabel
import edu.fnosari.momedm.utils.getAppVersion

/**
 * The parent's menu on the child device: what is being enforced here, whether the phone can see its
 * parent, and the actions that only make sense from this side.
 *
 * Everything is read from local storage, so it still answers "why is this phone behaving like this?"
 * when the parent is nowhere in range — which is exactly when someone picks the child's phone up to
 * find out. Reached by long-pressing the launcher header, behind the parent PIN when one is set.
 */
@Composable
fun ChildMenuScreen(vm: ManagedViewModel, onPause: () -> Unit) {
    val context = LocalContext.current
    val config by vm.kioskConfig.collectAsState()
    val lock by vm.lockState.collectAsState()
    val schedule by vm.lockSchedule.collectAsState()
    val safety by vm.safety.collectAsState()
    val link by vm.linkState.collectAsState()
    val controllerId by vm.controllerId.collectAsState()
    val pauseLeft by vm.pauseRemainingMs.collectAsState()
    val version = remember { getAppVersion(context)?.versionName ?: "?" }

    // Back closes the menu. Without this the gesture falls through to a launcher that deliberately
    // ignores it, so the only way out is the Close button at the very bottom of a long scroll — and
    // a parent who scrolled past it is stuck on a screen with no visible exit.
    BackHandler { vm.menuOpen.value = false }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.menu_title), style = MaterialTheme.typography.headlineSmall)

        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.menu_rules))
                Line(
                    stringResource(R.string.menu_child_mode),
                    stringResource(if (config?.on == true) R.string.conn_yes else R.string.conn_no),
                )
                if (config?.on == true) {
                    Line(stringResource(R.string.child_allowed_apps), (config?.apps?.size ?: 0).toString())
                    config?.pinned?.let { Line(stringResource(R.string.child_single_app), it) }
                }
                Line(
                    stringResource(R.string.menu_lock_now),
                    when (lock?.reason) {
                        LockState.REASON_MANUAL -> stringResource(R.string.child_locked_manual)
                        LockState.REASON_NIGHT -> stringResource(R.string.menu_lock_night)
                        else -> stringResource(R.string.child_unlocked)
                    },
                )
                Line(
                    stringResource(R.string.menu_night_lock),
                    if (schedule.enabled) {
                        stringResource(
                            R.string.menu_windows,
                            hhmm(schedule.weekdayStart), hhmm(schedule.weekdayEnd),
                            hhmm(schedule.weekendStart), hhmm(schedule.weekendEnd),
                        )
                    } else stringResource(R.string.conn_no),
                )
                Line(
                    stringResource(R.string.safety_level),
                    stringResource(
                        when (safety.level) {
                            SafetyLevel.STRICT -> R.string.safety_strict
                            SafetyLevel.MODERATE -> R.string.safety_moderate
                            SafetyLevel.OFF -> R.string.safety_off
                        },
                    ),
                )
                safety.dnsHost?.let { Line(stringResource(R.string.safety_dns), it) }
                if (safety.appConfigs.isNotEmpty()) {
                    Line(stringResource(R.string.menu_app_settings), safety.appConfigs.keys.joinToString())
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.menu_connection))
                Line(
                    stringResource(R.string.menu_parent_link),
                    stringResource(if (link == LinkState.AUTHENTICATED) R.string.child_online else R.string.child_offline),
                )
                Line(
                    stringResource(R.string.parent_id),
                    controllerId.take(8).ifBlank { stringResource(R.string.menu_not_paired) },
                )
            }
        }

        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.menu_device))
                Line(stringResource(R.string.menu_model), "${Build.MANUFACTURER} ${Build.MODEL}")
                Line(stringResource(R.string.menu_android), Build.VERSION.RELEASE)
                Line(stringResource(R.string.menu_version), version)
                Line(
                    stringResource(R.string.menu_managed),
                    stringResource(if (vm.deviceOwner) R.string.conn_yes else R.string.conn_no),
                )
            }
        }

        // Only offered while child mode is on and not already paused — pausing an unrestricted phone
        // would mean nothing, and the launcher's own banner already handles an active pause.
        if (config?.on == true && pauseLeft <= 0L) {
            Button(onClick = onPause, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.menu_pause)) }
        }
        OutlinedButton(
            onClick = { context.startActivity(Intent(context, RepairScanActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.repair_open)) }
        OutlinedButton(onClick = { vm.menuOpen.value = false }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.menu_close))
        }
    }
}

/** Minutes since midnight as HH:mm. */
private fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
