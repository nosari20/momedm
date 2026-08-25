package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.activities.main.navigation.Routes
import edu.fnosari.momedm.persistence.DeviceRecord
import edu.fnosari.momedm.ui.common.AccentPill
import edu.fnosari.momedm.ui.common.stripeEdge
import edu.fnosari.momedm.ui.theme.classColor

/** Online-dot green, matching [edu.fnosari.momedm.activities.main.components.OnlineIndicator]. */
private val OnlineGreen = Color(0xFF2E7D32)

/** "Paused" amber for [ChildStateChip], distinct from the palette's class accents. */
private val PausedAmber = Color(0xFFB07213)

/** "My children" list: one card per paired device, a visibility switch, and a FAB to pair another. */
@Composable
fun DevicesScreen(navController: NavHostController, viewModel: ControllerViewModel) {
    val devices by viewModel.devices.collectAsState()
    val online by viewModel.online.collectAsState()
    val advertising by viewModel.advertising.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.children_visible), style = MaterialTheme.typography.titleMedium)
                Switch(checked = advertising, onCheckedChange = { viewModel.setAdvertising(it) })
            }
            if (devices.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.children_empty), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                    itemsIndexed(devices, key = { _, d -> d.deviceId }) { index, d ->
                        ChildCard(d, d.deviceId in online, classColor(index.toLong())) { navController.navigate(Routes.device(d.deviceId)) }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { navController.navigate(Routes.PROVISION.name) }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.pair_title))
        }
    }
}

/** One child's card: nickname/model, a Pronote-style left accent stripe, and its live state chip. */
@Composable
private fun ChildCard(record: DeviceRecord, online: Boolean, accent: Color, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().stripeEdge(accent).padding(start = 44.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.nickname ?: record.model, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (record.nickname != null) Text(record.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) OnlineGreen else MaterialTheme.colorScheme.outline))
                ChildStateChip(record, online)
            }
        }
    }
}

/**
 * Pastel pill summarizing a child device's live state: offline (grey), paused (amber), child
 * mode on (green) or off (grey) — read from [DeviceRecord.lastStatus], the last STATUS this
 * device pushed.
 */
@Composable
fun ChildStateChip(record: DeviceRecord, online: Boolean) {
    val (label, accent) = when {
        !online -> stringResource(R.string.child_offline) to MaterialTheme.colorScheme.onSurfaceVariant
        record.lastStatus?.kioskPaused == true -> stringResource(R.string.child_mode_paused) to PausedAmber
        record.lastStatus?.kiosk == true -> stringResource(R.string.child_mode_on) to OnlineGreen
        // Online but no status yet is an unknown, not "child mode off" — which read as "my child's
        // phone is unrestricted?" while the first status was still in flight.
        record.lastStatus == null -> stringResource(R.string.child_state_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.child_mode_off) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    AccentPill(text = label, accent = accent)
}
