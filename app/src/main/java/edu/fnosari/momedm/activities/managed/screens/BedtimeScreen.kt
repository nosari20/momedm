package edu.fnosari.momedm.activities.managed.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.ui.components.NightSky
import edu.fnosari.momedm.ui.components.moonPhaseAt
import edu.fnosari.momedm.activities.managed.ManagedViewModel
import edu.fnosari.momedm.activities.managed.components.PinDialog
import edu.fnosari.momedm.protocol.LockState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * What the child sees while the device is completely locked: a deliberately quiet screen so it reads
 * as "closed", with no app tiles at all. A long-press anywhere opens the parent PIN dialog (only when
 * a PIN is set) — the same hidden affordance as the day launcher, so a child cannot find it by sight.
 */
@Composable
fun BedtimeScreen(vm: ManagedViewModel) {
    val lock by vm.lockState.collectAsState()
    val pinSet by vm.pinSet.collectAsState()
    val moon by vm.moonStyle.collectAsState()
    val pinError by vm.pinError.collectAsState()
    val pinLockedRemaining by vm.pinLockedRemainingMs.collectAsState()
    val showPin by vm.pinDialogOpen.collectAsState()

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); tick++ } }
    // The device's own 12/24-hour setting, not a hard-coded 24h clock: the subtitle below already
    // respects it, and the format a child is taught at home is the one they can actually read.
    val context = LocalContext.current
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val clock = remember(tick) { timeFmt.format(Date()) }

    val night = lock?.reason == LockState.REASON_NIGHT
    val until = lock?.until
    // "in 7 hours" answers the question a child actually has; the clock time answers a different one.
    // Both are shown — the duration first, because that is the reassurance — and the duration is
    // recomputed on the same slow tick as the clock above.
    val subtitle = if (night && until != null) {
        val left = remember(tick, until) { (until - System.currentTimeMillis()).coerceAtLeast(0L) }
        val h = (left / 3_600_000L).toInt()
        val m = ((left % 3_600_000L) / 60_000L).toInt()
        val relative = when {
            h > 0 && m > 0 -> stringResource(R.string.bedtime_in_hm, h, m)
            h > 0 -> pluralStringResource(R.plurals.bedtime_in_h, h, h)
            else -> m.coerceAtLeast(1).let { mm -> pluralStringResource(R.plurals.bedtime_in_m, mm, mm) }
        }
        stringResource(R.string.bedtime_until_relative, relative,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until)))
    } else stringResource(R.string.bedtime_manual)

    val a11y = stringResource(R.string.launcher_lock_cd)
    val menuCd = stringResource(R.string.launcher_menu_cd)
    val parentMenuAction = stringResource(R.string.launcher_parent_menu_action)
    // A slow dawn. As the unlock time approaches, a warm tint bleeds into the top of the sky, reaching
    // its full (still faint) strength right at the end. It is the same fact as the countdown above,
    // said in light instead of digits — and it moves at exactly the same rate whatever the child does,
    // so it cannot be read as a reward for waiting quietly. Recomputed on the clock's existing tick.
    val dawn = if (night && until != null) {
        val left = remember(tick, until) { (until - System.currentTimeMillis()).coerceAtLeast(0L) }
        ((DAWN_WINDOW_MS - left).coerceAtLeast(0L).toFloat() / DAWN_WINDOW_MS).coerceIn(0f, 1f)
    } else 0f
    // S1: this screen commits to night regardless of the pushed theme. Built from surface roles
    // it inverted its own metaphor in light mode — a pale grey "sky" with a near-black moon and
    // dark specks for stars. The parent's accent still tints the fixed navy, so their choice shows
    // through where it matters, and the dawn blend finally has a dark ground to warm up.
    val accent = MaterialTheme.colorScheme.primary
    val skyHigh = lerp(Color(0xFF151C30), accent, 0.10f)
    val skyLow = lerp(Color(0xFF0A0F1D), accent, 0.05f)
    val skyTop = lerp(skyHigh, DawnWarm, dawn * 0.25f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(skyTop, skyLow))),
        contentAlignment = Alignment.Center,
    ) {
        // Long-press is scoped to the clock/title column (spec §1.7: "long-press the header"), not the
        // whole screen — matching ChildLauncherScreen's header-only affordance. A child with no app
        // tiles to touch on this screen would otherwise find the hidden gesture on literally any tap.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.then(
                // With no PIN the gesture used to do nothing here, which left a completely locked
                // phone with no way to reach the parent menu — the one situation where someone most
                // needs to ask it what is going on.
                if (pinSet) Modifier
                    .semantics {
                        contentDescription = a11y
                        customActions = listOf(
                            CustomAccessibilityAction(parentMenuAction) { vm.pinDialogOpen.value = true; true },
                        )
                    }
                    .pointerInput(pinSet) { detectTapGestures(onLongPress = { vm.pinDialogOpen.value = true }) }
                else Modifier
                    .semantics {
                        contentDescription = menuCd
                        customActions = listOf(
                            CustomAccessibilityAction(parentMenuAction) { vm.menuOpen.value = true; true },
                        )
                    }
                    .pointerInput(pinSet) { detectTapGestures(onLongPress = { vm.menuOpen.value = true }) },
            ),
        ) {
            // Both colours are explicit on purpose: this Column sits in a bare Box, not a Surface, so
            // LocalContentColor falls back to black and the clock renders nearly invisible against the
            // dark gradient. The subtitle below already sets its own colour, which is why it was the
            // only legible line before this was fixed.
            // The sky only appears for a scheduled night lock. A manual "lock now" in the middle of a
            // Tuesday afternoon is not bedtime, and drawing a moon over it would be nonsense.
            if (night) {
                NightSky(
                    phase = remember(tick) { moonPhaseAt(System.currentTimeMillis()) },
                    moonColor = MoonLight.copy(alpha = 0.92f),
                    // The stars fade as dawn comes up, the way they actually do.
                    starColor = StarLight.copy(alpha = 0.55f * (1f - dawn * 0.8f)),
                    style = moon,
                )
            }
            Text(clock, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
                color = NightInk)
            Text(stringResource(if (night) R.string.bedtime_title else R.string.bedtime_break_title),
                style = MaterialTheme.typography.headlineSmall,
                color = NightInk)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = NightInkDim, modifier = Modifier.padding(horizontal = 32.dp))
        }

        // A completely locked phone must still be able to call for help, and the power-menu route the
        // design assumed does not exist on every device: on a Samsung running Android 14, long-pressing
        // power under lock task shows no menu at all even with LOCK_TASK_FEATURE_GLOBAL_ACTIONS set. So
        // the way out is explicit and visible rather than hidden behind a gesture a child would not
        // know. Deliberately outside the long-press Column above, so reaching for it can never open the
        // parent PIN pad by accident.
        OutlinedButton(
            onClick = { vm.openEmergencyDialer() },
            // Explicit light content: the theme's own outline and primary can vanish against the
            // fixed dark sky in light mode, and this is the one button that must never be the
            // hard-to-see thing on the screen.
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NightInk),
            border = BorderStroke(1.dp, NightInk.copy(alpha = 0.5f)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
        ) { Text(stringResource(R.string.bedtime_emergency)) }
    }

    if (showPin) PinDialog(
        onDismiss = { vm.pinDialogOpen.value = false; vm.clearPinError() },
        onSubmit = { pin -> vm.tryPin(pin) },
        error = pinError,
        lockedForMs = pinLockedRemaining,
    )
}

/** How long before the unlock time the sky starts to lighten. */
private const val DAWN_WINDOW_MS = 45 * 60_000L

/** The warmth dawn blends in. Fixed rather than themed: sunrise is not an accent colour. */
private val DawnWarm = Color(0xFFE9A15C)

/** The fixed night palette (S1): the sky is night in any theme; only the accent tint varies. */
private val NightInk = Color(0xFFEDEFF5)
private val NightInkDim = Color(0xFFB6BDD2)
private val MoonLight = Color(0xFFF0EAD8)
private val StarLight = Color(0xFFC7D0E8)
