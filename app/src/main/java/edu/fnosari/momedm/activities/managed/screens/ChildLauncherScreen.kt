package edu.fnosari.momedm.activities.managed.screens

import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.ui.components.NightSky
import edu.fnosari.momedm.ui.components.MoonStyle
import edu.fnosari.momedm.activities.managed.ManagedViewModel
import edu.fnosari.momedm.persistence.KioskConfig
import edu.fnosari.momedm.activities.managed.RepairScanActivity
import edu.fnosari.momedm.activities.managed.components.AppTile
import edu.fnosari.momedm.activities.managed.components.PinDialog
import edu.fnosari.momedm.managed.ManagedLinkState.LinkState
import java.util.Calendar

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
    val hidden by vm.hiddenApps.collectAsState()
    val moon by vm.moonStyle.collectAsState()
    val paused = pauseLeft > 0L
    val context = LocalContext.current
    val connected = link == LinkState.AUTHENTICATED

    // Config still loading (null): no tiles rather than guessing — rendering every installed tile
    // for what turns out to be a locked-down device would be a real escape hatch, however briefly.
    // But paint the same calm ground the real screen uses: a raw empty Box let the bare window
    // through, and a slow DataStore read looked like a dead phone instead of an app settling.
    val loadingGradient = Brush.verticalGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), MaterialTheme.colorScheme.background),
    )
    val config = loadedConfig ?: run { Box(Modifier.fillMaxSize().background(loadingGradient)); return }

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
    // The device's own 12/24-hour setting, not a hard-coded 24h clock — a US-set phone showed a
    // giant "21:30" over a bedtime subtitle that said "9:30 PM".
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val clock = remember(tick) { timeFmt.format(cal.time) }
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
    // H2: the launcher's one primary action must never fail silently — a child reads a dead tap as
    // "my phone is broken" and taps harder. Shown briefly, in the launcher's own soft register.
    val openFailTick by vm.openFailedTick.collectAsState()
    var showOpenFailed by remember { mutableStateOf(false) }
    LaunchedEffect(openFailTick) {
        if (openFailTick == 0) return@LaunchedEffect
        showOpenFailed = true
        delay(3_000L)
        showOpenFailed = false
    }
    var confirmRelock by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(gradient)) {
        // After dusk a few fixed stars come out at the top of the sky — the same celestial language
        // as the icon and the bedtime screen, tied to the clock and nothing else (like the mood
        // tint: identical whatever the child did today). Static and faint; scenery, not a feature.
        if (hour >= 19 || hour < 6) {
            Canvas(Modifier.fillMaxWidth().height(170.dp).alpha(0.35f)) {
                for ((fx, fy, sr) in HEADER_STARS) {
                    val x = size.width * fx; val y = size.height * fy; val r = sr.dp.toPx()
                    drawPath(Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }, StarDim)
                }
            }
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            // Header — long-press anywhere on it reveals the parent PIN pad (kids can't discover it by sight).
            val headerA11y = stringResource(R.string.launcher_lock_cd)
            val menuCd = stringResource(R.string.launcher_menu_cd)
            val parentMenuAction = stringResource(R.string.launcher_parent_menu_action)
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
                            .semantics {
                                contentDescription = headerA11y
                                customActions = listOf(
                                    CustomAccessibilityAction(parentMenuAction) { vm.pinDialogOpen.value = true; true },
                                )
                            }
                            .pointerInput(canUnlock, config.on) {
                                detectTapGestures(
                                    onPress = { vm.headerPressed.value = true; tryAwaitRelease(); vm.headerPressed.value = false },
                                    onLongPress = { vm.pinDialogOpen.value = true },
                                )
                            }
                        else if (config.on) Modifier
                            .semantics {
                                contentDescription = menuCd
                                customActions = listOf(
                                    CustomAccessibilityAction(parentMenuAction) { vm.menuOpen.value = true; true },
                                )
                            }
                            .pointerInput(canUnlock, config.on) {
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
                    val customiseLabel = stringResource(R.string.launcher_customise_title)
                    Crossfade(targetState = greetingText, label = "greeting") { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                g,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    // A named action on a full-size target: TalkBack says what the
                                    // tap does, and a slow tap no longer drifts past the timeout
                                    // into the parent long-press by way of an undersized target.
                                    .minimumInteractiveComponentSize()
                                    .clickable(onClickLabel = customiseLabel) { vm.namingOpen.value = true },
                            )
                            // A quiet pencil: the child's own feature should be findable by sight —
                            // unlike the parent's gesture, this one was never meant to be hidden.
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.padding(start = 6.dp).size(16.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // A single bounce when the link changes — the register of a chat app's presence dot,
                // not a pulse that runs forever on a phone that sits on a table.
                val dotScale = remember { Animatable(1f) }
                var firstDot by remember { mutableStateOf(true) }
                LaunchedEffect(connected) {
                    // Skip the initial composition: firing on entry diluted the one-shot signal.
                    if (firstDot) { firstDot = false; return@LaunchedEffect }
                    dotScale.animateTo(1.35f, tween(140)); dotScale.animateTo(1f, tween(220))
                }
                val dotCd = stringResource(if (connected) R.string.launcher_online else R.string.launcher_offline)
                // The icon draws the parent as a warm sun; this is the same fact at 16dp — the
                // parent in range means the sun is out, out of range leaves a thin empty ring.
                // Same size, place, semantics and one-shot bounce as the dot it replaces.
                val ringColor = MaterialTheme.colorScheme.outline
                Canvas(
                    Modifier
                        .size(16.dp)
                        .scale(dotScale.value)
                        .semantics { contentDescription = dotCd },
                ) {
                    val r = size.minDimension / 2f
                    if (connected) {
                        drawCircle(SunWarm.copy(alpha = 0.30f), radius = r)
                        drawCircle(SunWarm, radius = r * 0.62f)
                        drawCircle(SunHighlight, radius = r * 0.24f,
                            center = center - androidx.compose.ui.geometry.Offset(r * 0.18f, r * 0.18f))
                    } else {
                        drawCircle(ringColor, radius = r * 0.55f, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }

            if (paused) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    // A Column, not one packed Row: at large font scales the single row
                    // clipped its buttons, and the text line deserves the full width anyway.
                    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
                        // S3: minutes and a gentle bar, not a per-second countdown — MM:SS is the
                        // visual grammar of exams and bombs, and "roughly how much break is left"
                        // never needed that resolution for either audience. The glyph (S2) gives a
                        // pre-reader the state without the sentence.
                        val minutesLeft = (pauseLeft + 59_999L) / 60_000L
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PauseGlyph(MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(stringResource(R.string.launcher_paused, "$minutesLeft min"), style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(
                            progress = { (1f - pauseLeft.toFloat() / KioskConfig.PAUSE_MS).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            // Only reachable while paused, which took the parent PIN: a child cannot
                            // re-point their own phone at a different parent.
                            TextButton(onClick = { vm.menuOpen.value = true }) { Text(stringResource(R.string.menu_open)) }
                            // Confirmed: ending the pause is one stray child tap, but undoing it
                            // costs the parent a full PIN entry.
                            TextButton(onClick = { confirmRelock = true }) { Text(stringResource(R.string.launcher_relock)) }
                        }
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        ) {
                            // S2: a picture lane for pre-readers — and the same picture as the app's
                            // icon: the parent sun with the child planet beside it. "Nothing here yet,
                            // and the place is fine" — the two of you are still here, apps or not.
                            CelestialGlyph()
                            Text(
                                stringResource(emptyText),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Says plainly that the short grid is a choice someone made, not a fault. Only
                    // when apps are genuinely hidden — on a phone with nothing held back it would be
                    // a reminder of restriction where there is none.
                    if (hidden > 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.launcher_some_hidden, hidden),
                                style = MaterialTheme.typography.bodySmall,
                                // onSurfaceVariant is contrast-managed per scheme; 70% alpha over
                                // the mood-tinted gradient could dip below 4.5:1 on light accents.
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                        }
                    }
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

        if (showOpenFailed) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            ) {
                Text(
                    stringResource(R.string.launcher_cant_open),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (confirmRelock) AlertDialog(
        onDismissRequest = { confirmRelock = false },
        title = { Text(stringResource(R.string.launcher_relock)) },
        text = { Text(stringResource(R.string.launcher_relock_confirm)) },
        confirmButton = { TextButton(onClick = { confirmRelock = false; vm.relock() }) { Text(stringResource(R.string.launcher_relock)) } },
        dismissButton = { TextButton(onClick = { confirmRelock = false }) { Text(stringResource(R.string.settings_dialog_dismiss)) } },
    )

    if (naming) {
        var draft by remember { mutableStateOf(childName) }
        var draftMoon by remember { mutableStateOf(moon) }
        AlertDialog(
            onDismissRequest = { vm.namingOpen.value = false },
            title = { Text(stringResource(R.string.launcher_customise_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.launcher_name_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(20) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.launcher_name_label)) },
                        // The cap used to be silent — "the keyboard stopped working" is a child's
                        // read of a truncating field.
                        supportingText = { Text(draft.length.toString() + "/20") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.launcher_moon_label), style = MaterialTheme.typography.bodyMedium)
                    // Chosen here rather than on the bedtime screen on purpose: that screen exists to
                    // end phone use, and something to fiddle with on it would quietly make the lock
                    // negotiable. The child picks it here; the night sky simply shows what they chose.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (option in MoonStyle.entries) {
                            val selected = option == draftMoon
                            // Resolved out here: semantics {} is not a composable scope.
                            val name = moonName(option)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                // Selection is announced (radio semantics) and drawn (border), not
                                // colour-only: the two container tones can sit very close under
                                // some parent-picked accents, and TalkBack heard no state at all.
                                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .selectable(selected = selected, role = Role.RadioButton) { draftMoon = option }
                                    .semantics { contentDescription = name },
                            ) {
                                NightSky(
                                    // Shown full, not at tonight's phase: the three styles are only
                                    // told apart on a full disc, and at a thin crescent the picker
                                    // would offer three identical pictures.
                                    phase = 0.5f,
                                    moonColor = MaterialTheme.colorScheme.onSurface,
                                    starColor = Color.Transparent,
                                    size = 64.dp,
                                    style = option,
                                    stars = false,
                                    modifier = Modifier.padding(4.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveChildLook(draft, draftMoon) }) { Text(stringResource(R.string.settings_dialog_confirm)) }
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

/** A screen-reader name for each moon, so the picker is not three unlabelled pictures. */
@Composable
private fun moonName(style: MoonStyle): String = stringResource(
    when (style) {
        MoonStyle.SOLID -> R.string.moon_solid
        MoonStyle.OUTLINE -> R.string.moon_outline
        MoonStyle.CRATERS -> R.string.moon_craters
    },
)

/**
 * The icon's composition at card size: the parent sun, the child planet beside it, two stars —
 * drawn rather than written, for the child who reads haltingly or not at all (S2). Fixed geometry,
 * no motion, no assets.
 */
@Composable
private fun CelestialGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier.size(56.dp)) {
        val w = size.width; val h = size.height
        drawCircle(SunWarm.copy(alpha = 0.30f), radius = w * 0.30f, center = Offset(w * 0.40f, h * 0.44f))
        drawCircle(SunWarm, radius = w * 0.24f, center = Offset(w * 0.40f, h * 0.44f))
        drawCircle(SunHighlight, radius = w * 0.085f, center = Offset(w * 0.32f, h * 0.36f))
        drawCircle(MoonCream, radius = w * 0.13f, center = Offset(w * 0.78f, h * 0.68f))
        drawCircle(MoonCrater, radius = w * 0.035f, center = Offset(w * 0.74f, h * 0.64f))
        drawCircle(MoonCrater, radius = w * 0.025f, center = Offset(w * 0.81f, h * 0.72f))
        for ((fx, fy, sr) in listOf(Triple(0.16f, 0.16f, 0.045f), Triple(0.82f, 0.22f, 0.035f))) {
            val x = w * fx; val y = h * fy; val r = w * sr
            drawPath(Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }, StarDim)
        }
    }
}

/** The universal pause mark, for the banner's picture lane (S2). */
@Composable
private fun PauseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val barW = size.width * 0.3f
        val corner = CornerRadius(barW / 2f, barW / 2f)
        drawRoundRect(color, topLeft = Offset(size.width * 0.1f, 0f), size = Size(barW, size.height), cornerRadius = corner)
        drawRoundRect(color, topLeft = Offset(size.width * 0.6f, 0f), size = Size(barW, size.height), cornerRadius = corner)
    }
}

/** The celestial palette shared with the launcher icon and the bedtime screen. */
private val SunWarm = Color(0xFFE9A15C)
private val SunHighlight = Color(0xFFF2BE85)
private val MoonCream = Color(0xFFF0EAD8)
private val MoonCrater = Color(0xFFD8CDAE)
private val StarDim = Color(0xFFC7D0E8)

/** Fixed star positions for the evening header, as fractions of the star field. Fixed: no flicker. */
private val HEADER_STARS = listOf(
    Triple(0.12f, 0.55f, 2.2f), Triple(0.30f, 0.28f, 1.6f), Triple(0.55f, 0.62f, 1.8f),
    Triple(0.72f, 0.20f, 1.5f), Triple(0.90f, 0.48f, 2.0f),
)
