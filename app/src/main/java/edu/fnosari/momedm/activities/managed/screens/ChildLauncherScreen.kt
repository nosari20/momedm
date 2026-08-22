package edu.fnosari.momedm.activities.managed.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.ManagedViewModel
import edu.fnosari.momedm.activities.managed.components.PinDialog
import edu.fnosari.momedm.managed.ManagedLinkState.LinkState

/**
 * The child device's home: a grid of big app tiles (allowed apps while child mode is on, all apps otherwise),
 * a slim header (link dot, battery, mode) and — when a parent PIN exists — a lock icon opening [PinDialog].
 * [onUnlocked] is called after a correct PIN; the hosting Activity releases the lock task.
 */
@Composable
fun ChildLauncherScreen(vm: ManagedViewModel, onUnlocked: () -> Unit) {
    val apps by vm.launcherApps.collectAsState()
    val config by vm.kioskConfig.collectAsState()
    val link by vm.linkState.collectAsState()
    val status by vm.lastStatus.collectAsState()
    val pinSet by vm.pinSet.collectAsState()
    val pauseLeft by vm.pauseRemainingMs.collectAsState()
    val pinError by vm.pinError.collectAsState()
    val pinLockedUntil by vm.pinLockedUntilMs.collectAsState()
    var showPin by remember { mutableStateOf(false) }
    val paused = pauseLeft > 0L

    Column(Modifier.fillMaxSize()) {
        // header
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (config.on) R.string.launcher_child_mode_on else R.string.launcher_child_mode_off), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(stringResource(if (link == LinkState.AUTHENTICATED) R.string.launcher_online else R.string.launcher_offline), style = MaterialTheme.typography.labelMedium)
                status?.let { Text("  ${it.battery}%", style = MaterialTheme.typography.labelMedium) }
                if (config.on && pinSet) IconButton(onClick = { showPin = true }) { Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.launcher_lock_cd)) }
            }
        }
        if (paused) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val m = pauseLeft / 60_000L; val s = (pauseLeft / 1_000L) % 60L
                    Text(stringResource(R.string.launcher_paused, String.format("%02d:%02d", m, s)), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.relock() }) { Text(stringResource(R.string.launcher_relock)) }
                }
            }
        }
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.launcher_no_apps), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp)) }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(apps, key = { it.pkg }) { app ->
                    Column(Modifier.fillMaxWidth().clickable { vm.open(app.pkg) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val bmp = remember(app.pkg) { app.icon?.toBitmap(144, 144)?.asImageBitmap() }
                        if (bmp != null) Image(bmp, contentDescription = app.label, modifier = Modifier.size(72.dp))
                        else Box(Modifier.size(72.dp))
                        Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    if (showPin) PinDialog(
        onDismiss = { showPin = false; vm.clearPinError() },
        onSubmit = { pin -> vm.tryPin(pin) { showPin = false; onUnlocked() } },
        error = pinError,
        lockedForMs = (pinLockedUntil - System.currentTimeMillis()).coerceAtLeast(0L),
    )
}
