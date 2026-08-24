package edu.fnosari.momedm.activities.managed.screens

import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
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
    val childName by vm.childName.collectAsState()
    val naming by vm.namingOpen.collectAsState()
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
    // The name goes inside the time-of-day greeting rather than replacing it: a bare "Hi Max!" at
    // eleven at night reads oddly, and the hour is half of what makes the greeting feel like a person
    // rather than a label.
    val greetingText = if (childName.isBlank()) {
        stringResource(
            when {
                hour < 12 -> R.string.launcher_greeting_morning
                hour < 18 -> R.string.launcher_greeting_afternoon
                else -> R.string.launcher_greeting_evening
            },
        )
    } else {
        stringResource(
            when {
                hour < 12 -> R.string.launcher_greeting_morning_named
                hour < 18 -> R.string.launcher_greeting_afternoon_named
                else -> R.string.launcher_greeting_evening_named
            },
            childName,
        )
    }

    // Soft accent gradient (accent-tinted primaryContainer fading into the themed background), calm in
    // both light and dark because it is built from theme roles rather than fixed colors.
    // One very faint layer of time of day over the parent's chosen accent — warm early, neutral
    // through the day, cool in the evening. Felt rather than looked at, and deliberately tied to the
    // clock and nothing else: it is identical whether the child has been cooperative or is mid-
    // argument, so it can never read as a reward or a telling-off. Rides the 30s tick already running.
    val mood = when {
        hour < 11 -> Color(0xFFFFB067)
        hour < 17 -> Color.Transparent
        else -> Color(0xFF8E86D6)
    }
    // Tint the accent itself and keep the original translucency: compositing over the background
    // first makes the top of the screen opaque and much darker, which is what the accent's 45% alpha
    // was avoiding in the first place.
    val top = if (mood == Color.Transparent) MaterialTheme.colorScheme.primaryContainer
              else lerp(MaterialTheme.colorScheme.primaryContainer, mood, 0.18f)
    val gradient = Brush.verticalGradient(
        listOf(top.copy(alpha = 0.45f), MaterialTheme.colorScheme.background),
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
                    Text(clock, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground)
                    // A plain tap belongs to the child (choose your name); the long-press stays the
                    // parent's. Crossfade so the greeting changing at an hour boundary, or the moment a
                    // name is saved, is a soft change rather than a flicker.
                    Crossfade(targetState = greetingText, label = "greeting") { g ->
                        Text(
                            g,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.clickable { vm.namingOpen.value = true },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // A single bounce when the link changes — the register of a chat app's presence dot,
                // not a pulse that runs forever on a phone that sits on a table.
                val dotScale = remember { Animatable(1f) }
                LaunchedEffect(connected) {
                    dotScale.animateTo(1.35f, tween(140)); dotScale.animateTo(1f, tween(220))
                }
                val dotColor = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                val dotCd = stringResource(if (connected) R.string.launcher_online else R.string.launcher_offline)
                Box(
                    Modifier
                        .size(14.dp)
                        .scale(dotScale.value)
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
                    // Bare centred text on an empty screen reads as a crash. The same soft rounded card
                    // the tiles use says "this is the app, working, with nothing to show yet" instead.
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            stringResource(emptyText),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
                        // The grid arrives rather than appears: each tile fades and lifts in, a beat
                        // after the one before it. One-shot on first composition — nothing here runs
                        // after the screen has settled. The stagger is capped so a full grid finishes
                        // in well under half a second instead of crawling down the screen.
                        val enter = remember { Animatable(0f) }
                        LaunchedEffect(app.pkg) {
                            delay(index.coerceAtMost(6) * 45L)
                            enter.animateTo(1f, tween(260))
                        }
                        AppTile(
                            app = app,
                            onClick = { vm.open(app.pkg) },
                            modifier = Modifier
                                .alpha(enter.value)
                                .scale(0.92f + 0.08f * enter.value),
                        )
                    }
                }
            }
        }
    }

    if (naming) {
        var draft by remember { mutableStateOf(childName) }
        AlertDialog(
            onDismissRequest = { vm.namingOpen.value = false },
            title = { Text(stringResource(R.string.launcher_name_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.launcher_name_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(20) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.launcher_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setChildName(draft) }) { Text(stringResource(R.string.settings_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.namingOpen.value = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) }
            },
        )
    }

    if (showPin) PinDialog(
        onDismiss = { vm.pinDialogOpen.value = false; vm.clearPinError() },
        onSubmit = { pin -> vm.tryPin(pin) },
        error = pinError,
        lockedForMs = pinLockedRemaining,
    )
}
