package edu.fnosari.momedm.activities.managed.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.ManagedViewModel
import edu.fnosari.momedm.activities.managed.components.PinDialog
import edu.fnosari.momedm.protocol.LockState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What the child sees while the device is completely locked: a deliberately quiet screen so it reads
 * as "closed", with no app tiles at all. A long-press anywhere opens the parent PIN dialog (only when
 * a PIN is set) — the same hidden affordance as the day launcher, so a child cannot find it by sight.
 */
@Composable
fun BedtimeScreen(vm: ManagedViewModel, onUnlocked: () -> Unit) {
    val lock by vm.lockState.collectAsState()
    val pinSet by vm.pinSet.collectAsState()
    val pinError by vm.pinError.collectAsState()
    val pinLockedRemaining by vm.pinLockedRemainingMs.collectAsState()
    val showPin by vm.pinDialogOpen.collectAsState()

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); tick++ } }
    val clock = remember(tick) {
        val c = Calendar.getInstance()
        String.format(Locale.getDefault(), "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    val night = lock?.reason == LockState.REASON_NIGHT
    val until = lock?.until
    val subtitle = if (night && until != null)
        stringResource(R.string.bedtime_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until)))
    else stringResource(R.string.bedtime_manual)

    val a11y = stringResource(R.string.launcher_lock_cd)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface))),
        contentAlignment = Alignment.Center,
    ) {
        // Long-press is scoped to the clock/title column (spec §1.7: "long-press the header"), not the
        // whole screen — matching ChildLauncherScreen's header-only affordance. A child with no app
        // tiles to touch on this screen would otherwise find the hidden gesture on literally any tap.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.then(
                if (pinSet) Modifier
                    .semantics { contentDescription = a11y }
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { vm.pinDialogOpen.value = true }) }
                else Modifier,
            ),
        ) {
            // Both colours are explicit on purpose: this Column sits in a bare Box, not a Surface, so
            // LocalContentColor falls back to black and the clock renders nearly invisible against the
            // dark gradient. The subtitle below already sets its own colour, which is why it was the
            // only legible line before this was fixed.
            Text(clock, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.bedtime_title), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }

    if (showPin) PinDialog(
        onDismiss = { vm.pinDialogOpen.value = false; vm.clearPinError() },
        onSubmit = { pin -> vm.tryPin(pin) { vm.pinDialogOpen.value = false; onUnlocked() } },
        error = pinError,
        lockedForMs = pinLockedRemaining,
    )
}
