package edu.fnosari.momedm.activities.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.controller.ControllerLink
import edu.fnosari.momedm.controller.ControllerService
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.persistence.DeviceRecord
import edu.fnosari.momedm.persistence.DeviceRegistry
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.ui.common.SectionLabel
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Parent-side view of the BLE link: whether this phone is discoverable, which children are actually
 * connected, and a log of what the link has been doing.
 *
 * It exists because the device list only ever shows the end state, and the two ways pairing fails
 * look identical from there. The event log separates them: a child that connects and then disappears
 * without authenticating holds a different shared secret, while no connection events at all means it
 * never reached us — out of range, this phone not discoverable, or the child not scanning.
 */
@Composable
fun SettingsConnectionScreen(navController: NavHostController) {
    val context = LocalContext.current
    val advertising by ControllerLink.advertising.collectAsState()
    val online by ControllerLink.online.collectAsState()
    val events by ControllerLink.events.collectAsState()
    val prefs = remember { ControllerPrefs(DataStorePreferencesProvider(context)) }
    var controllerId by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<DeviceRecord>>(emptyList()) }

    LaunchedEffect(Unit) { controllerId = prefs.ensureIdentity().controllerId }
    // The registry is read from DataStore rather than observed: this screen is a snapshot a parent
    // reads for a few seconds, and a slow poll keeps "last seen" honest without another live collector.
    LaunchedEffect(Unit) {
        val registry = DeviceRegistry(prefs, this)
        while (true) {
            registry.reload()
            devices = registry.devices.value
            delay(2_000)
        }
    }

    BasicLayoutWithTopBar(title = stringResource(R.string.settings_connection), leftAction = { navController.popBackStack() }) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.conn_this_phone))
                    LabelledValue(
                        stringResource(R.string.conn_discoverable),
                        stringResource(if (advertising) R.string.conn_yes else R.string.conn_no),
                    )
                    LabelledValue(stringResource(R.string.conn_children_connected), online.size.toString())
                    LabelledValue(stringResource(R.string.parent_id), controllerId?.take(8) ?: "…")
                    OutlinedButton(onClick = { ControllerService.reloadIdentity(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.conn_restart))
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.conn_children))
                    if (devices.isEmpty()) {
                        Text(stringResource(R.string.conn_no_children), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        for (d in devices) {
                            LabelledValue(
                                d.nickname ?: d.model,
                                stringResource(if (d.deviceId in online) R.string.child_online else R.string.child_offline),
                            )
                            Text(
                                stringResource(
                                    R.string.conn_child_detail,
                                    d.deviceId.take(8),
                                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(d.lastSeen)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(stringResource(R.string.conn_activity))
                    if (events.isEmpty()) {
                        Text(stringResource(R.string.conn_no_activity), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        for (e in events) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(e.atMs)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(stringResource(labelFor(e.kind)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            stringResource(R.string.conn_rejected_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Parent-facing wording for a link event. The raw BLE address in [ControllerLink.LinkEvent.detail] is
 * deliberately not shown — it identifies hardware, means nothing to a parent, and the interesting
 * information is which *kind* of event happened and when.
 */
private fun labelFor(kind: ControllerLink.LinkEvent.Kind): Int = when (kind) {
    ControllerLink.LinkEvent.Kind.CONNECTED -> R.string.conn_event_connected
    ControllerLink.LinkEvent.Kind.AUTHENTICATED -> R.string.conn_event_authenticated
    ControllerLink.LinkEvent.Kind.REJECTED -> R.string.conn_event_rejected
    ControllerLink.LinkEvent.Kind.DISCONNECTED -> R.string.conn_event_disconnected
    ControllerLink.LinkEvent.Kind.ADVERTISING -> R.string.conn_event_advertising
    ControllerLink.LinkEvent.Kind.ERROR -> R.string.conn_event_error
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
