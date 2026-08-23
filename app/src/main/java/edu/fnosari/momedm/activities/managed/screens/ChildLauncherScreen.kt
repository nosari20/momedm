package edu.fnosari.momedm.activities.managed.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.ManagedViewModel
import edu.fnosari.momedm.activities.managed.RepairScanActivity
import edu.fnosari.momedm.activities.managed.components.AppTile
import edu.fnosari.momedm.activities.managed.components.PinDialog
import edu.fnosari.momedm.managed.ManagedLinkState.LinkState
import java.util.Calendar
import java.util.Locale

/**
 * The child device's home screen — designed to be friendly for kids up to ~14, not toddler-cartoonish.
 * A soft accent gradient behind a calm header (big clock + time-of-day greeting + a small connection dot)
 * and a grid of big rounded app tiles (allowed apps while child mode is on, all apps otherwise). No MDM
 * jargon, no battery %, no visible lock button: a long-press on the header opens the parent [PinDialog]
 * (only when child mode is on and a PIN exists). A correct PIN opens the parent menu; lock task is
 * released only when the parent actually pauses child mode from there.
 */
@Composable
fun ChildLauncherScreen(vm: ManagedViewModel) {
    val apps by vm.launcherApps.collectAsState()
    val loadedConfig by vm.kioskConfig.collectAsState()
    val link by vm.linkState.collectAsState()
    val pinSet by vm.pinSet.collectAsState()
    val pauseLeft by vm.pauseRemainingMs.collectAsState()
    val pinError by vm.pinError.collectAsState()
    val pinLockedRemaining by vm.pinLockedRemainingMs.collectAsState()
    val showPin by vm.pinDialogOpen.collectAsState()
    val paused = pauseLeft > 0L
    val context = LocalContext.current
    val connected = link == LinkState.AUTHENTICATED

    // Config still loading (null): draw a bare surface rather than guessing. Rendering every installed
    // tile for what turns out to be a locked-down device would be a real escape hatch, however briefly.
    val config = loadedConfig ?: run { Box(Modifier.fillMaxSize()); return }

    // A slow clock: recompute the current minute periodically. `tick` just forces recomposition.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            tick++
        }
    }
    val cal = remember(tick) { Calendar.getInstance() }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val clock = remember(tick) { String.format(Locale.getDefault(), "%02d:%02d", hour, cal.get(Calendar.MINUTE)) }
    val greeting = when {
        hour < 12 -> R.string.launcher_greeting_morning
        hour < 18 -> R.string.launcher_greeting_afternoon
        else -> R.string.launcher_greeting_evening
    }

    // Soft accent gradient (accent-tinted primaryContainer fading into the themed background), calm in
    // both light and dark because it is built from theme roles rather than fixed colors.
    val gradient = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.background,
        ),
    )
    val canUnlock = config.on && pinSet

    Box(Modifier.fillMaxSize().background(gradient)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            // Header — long-press anywhere on it reveals the parent PIN pad (kids can't discover it by sight).
            val headerA11y = stringResource(R.string.launcher_lock_cd)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        // With a PIN set, the long-press asks for it and a correct PIN pauses child
                        // mode. With no PIN there is nothing to verify, so it goes straight to
                        // re-pairing — otherwise the gesture is simply dead, and a family that never
                        // set a PIN would have no way to re-pair a child device at all, which is
                        // exactly the situation a lost or replaced parent phone creates.
                        // onPress holds the pinned-app bounce off for as long as the finger is down, so
                        // the long-press can be completed at leisure instead of raced against a grace period.
                        if (canUnlock) Modifier
                            .semantics { contentDescription = headerA11y }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { vm.headerPressed.value = true; tryAwaitRelease(); vm.headerPressed.value = false },
                                    onLongPress = { vm.pinDialogOpen.value = true },
                                )
                            }
                        else if (config.on) Modifier
                            .semantics { contentDescription = headerA11y }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { vm.headerPressed.value = true; tryAwaitRelease(); vm.headerPressed.value = false },
                                    onLongPress = { vm.menuOpen.value = true },
                                )
                            }
                        else Modifier,
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(clock, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(greeting), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.weight(1f))
                val dotColor = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                val dotCd = stringResource(if (connected) R.string.launcher_online else R.string.launcher_offline)
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .semantics { contentDescription = dotCd },
                )
            }

            if (paused) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val m = pauseLeft / 60_000L
                        val s = (pauseLeft / 1_000L) % 60L
                        Text(stringResource(R.string.launcher_paused, String.format(Locale.US, "%02d:%02d", m, s)), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        // Only reachable while paused, which took the parent PIN: a child cannot
                        // re-point their own phone at a different parent.
                        TextButton(onClick = { vm.menuOpen.value = true }) { Text(stringResource(R.string.menu_open)) }
                        TextButton(onClick = { vm.relock() }) { Text(stringResource(R.string.launcher_relock)) }
                    }
                }
            }

            if (apps.isEmpty()) {
                val emptyText = if (config.on) R.string.launcher_no_apps else R.string.launcher_no_apps_installed
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(emptyText), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(apps, key = { it.pkg }) { app ->
                        AppTile(app = app, onClick = { vm.open(app.pkg) })
                    }
                }
            }
        }
    }

    if (showPin) PinDialog(
        onDismiss = { vm.pinDialogOpen.value = false; vm.clearPinError() },
        onSubmit = { pin -> vm.tryPin(pin) },
        error = pinError,
        lockedForMs = pinLockedRemaining,
    )
}
