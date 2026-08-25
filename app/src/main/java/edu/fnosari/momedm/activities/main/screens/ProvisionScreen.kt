package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.controller.provisioning.ProvisionError
import edu.fnosari.momedm.controller.provisioning.QrBitmap
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.ui.common.SectionLabel
import edu.fnosari.momedm.ui.components.ButtonRequestPermission
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * `Manifest.permission.ACCESS_LOCAL_NETWORK`, as a literal so this compiles against an SDK older
 * than 37, and the first SDK level that enforces it. Without the grant, Android completes the TCP
 * handshake to our APK server and then silently drops the traffic, so the child device's download
 * hangs with no error anywhere — worth asking for explicitly, right where it is needed.
 */
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val SDK_LOCAL_NETWORK = 37

/** "Pair a device" flow: three numbered steps — how it connects, generate the code, scan it. */
@Composable
fun ProvisionScreen(navController: NavHostController, viewModel: ControllerViewModel) {
    val pc = viewModel.provisioning
    val s by pc.state.collectAsState()
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { pc.stop() } }

    // The enrolment's missing "step 4": success used to be discoverable only by leaving the screen
    // — which tears the hotspot and the APK server down (the DisposableEffect above), killing a
    // download in progress. Baseline the known devices when a code appears; any new id after that
    // is the phone being enrolled right now.
    val devices by viewModel.devices.collectAsState()
    var baselineIds by remember { mutableStateOf<Set<String>?>(null) }
    var pairedName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.qrPayload) { if (s.qrPayload != null && baselineIds == null) baselineIds = devices.map { it.deviceId }.toSet() }
    LaunchedEffect(devices) {
        val base = baselineIds ?: return@LaunchedEffect
        devices.firstOrNull { it.deviceId !in base }?.let { pairedName = it.nickname ?: it.model }
    }
    // The QR must survive a display timeout: a screen going dark mid-scan is a dead end.
    val view = LocalView.current
    DisposableEffect(s.qrPayload != null) {
        view.keepScreenOn = s.qrPayload != null
        onDispose { view.keepScreenOn = false }
    }

    // Pre-fill the Wi-Fi name with the network this phone is already on: the child has to join the
    // same one, so typing it by hand is only a chance to get it wrong. Only when the field is empty,
    // so it never overwrites something the parent typed.
    LaunchedEffect(s.mode) { pc.prefillSsidIfBlank() }

    // Asked for here rather than in the app-wide permission gate: local network access is needed only
    // to serve the APK during pairing, and blocking the whole app for it would be disproportionate.
    var localNetworkGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < SDK_LOCAL_NETWORK ||
                ActivityCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The two facts a parent must know before anything else — reset the child's phone first,
        // and the code is short-lived — used to appear only after they had stopped being actionable.
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pair_help), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        SectionLabel(stringResource(R.string.pair_step1), modifier = Modifier.fillMaxWidth())
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ControllerPrefs.MODE_HOTSPOT to R.string.pair_wifi_hotspot,
                    ControllerPrefs.MODE_MANUAL to R.string.pair_wifi_manual,
                    ControllerPrefs.MODE_CUSTOM_URL to R.string.pair_wifi_custom,
                ).forEach { (mode, label) ->
                    // The whole row is the target, not just the little circle: tapping the label and
                    // having nothing happen reads as "I chose Shared Wi-Fi" while the app is still in
                    // hotspot mode, which then shows hotspot credentials the parent did not expect.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = s.mode == mode, role = Role.RadioButton, onClick = { pc.setMode(mode) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.mode == mode, onClick = null)
                        Text(stringResource(label))
                    }
                    Text(
                        stringResource(
                            when (mode) {
                                ControllerPrefs.MODE_HOTSPOT -> R.string.pair_wifi_hotspot_help
                                ControllerPrefs.MODE_MANUAL -> R.string.pair_wifi_manual_help
                                else -> R.string.pair_wifi_custom_help
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 48.dp),
                    )
                }
                if (s.mode == ControllerPrefs.MODE_HOTSPOT) {
                    // Hotspot credentials are transient and generated by the platform; show them read-only once available.
                    if (s.hotspotSsid.isNotBlank()) {
                        Text(stringResource(R.string.pair_ssid_value, stringResource(R.string.pair_ssid), s.hotspotSsid), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.pair_password_value, stringResource(R.string.pair_password), s.hotspotPassword), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedTextField(s.ssid, { pc.setManual(it, s.password) }, label = { Text(stringResource(R.string.pair_ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    // Masked by default: this is typed with a child, and often a neighbour, in the room.
                    // The toggle is a text button because the eye glyph lives in material-icons-extended,
                    // which this project does not ship.
                    var showPassword by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        s.password,
                        { pc.setManual(s.ssid, it) },
                        label = { Text(stringResource(R.string.pair_password)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(stringResource(if (showPassword) R.string.pair_password_hide else R.string.pair_password_show))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.mode == ControllerPrefs.MODE_CUSTOM_URL) OutlinedTextField(s.customUrl, { pc.setCustomUrl(it) }, label = { Text(stringResource(R.string.pair_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }

        SectionLabel(stringResource(R.string.pair_step2), modifier = Modifier.fillMaxWidth())
        if (!localNetworkGranted) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pair_local_network_why), style = MaterialTheme.typography.bodyMedium)
                    ButtonRequestPermission(
                        context = context,
                        permission = ACCESS_LOCAL_NETWORK,
                        granted = { localNetworkGranted = true },
                        denied = { },
                    )
                }
            }
        }
        // Disabled while a code is live: tapping again was a silent no-op (the controller refuses
        // re-entrant starts), which read as a broken button.
        val live = s.serverRunning || s.hotspotSsid.isNotBlank() || s.qrPayload != null
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pc.start() }, enabled = localNetworkGranted && !live) { Text(stringResource(R.string.pair_generate)) }
            OutlinedButton(onClick = { pc.stop() }, enabled = live) { Text(stringResource(R.string.pair_stop)) }
        }
        if (!live) Text(stringResource(R.string.pair_code_life), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.serverRunning) Text(stringResource(R.string.pair_ready), style = MaterialTheme.typography.bodySmall)
        // H2: stable codes mapped to plain sentences with a next step — "no IPv4 address" fired at
        // the least recoverable audience at the most fragile moment, in English only.
        s.error?.let {
            Text(
                stringResource(
                    when (it) {
                        ProvisionError.HOTSPOT -> R.string.pair_err_hotspot
                        ProvisionError.NO_ADDRESS -> R.string.pair_err_no_address
                        ProvisionError.SERVER -> R.string.pair_err_server
                        ProvisionError.CHECKSUM -> R.string.pair_err_checksum
                        ProvisionError.MISSING_URL -> R.string.pair_err_missing_url
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Says why the code went away, rather than leaving the parent staring at an empty step 3.
        if (s.expired) Text(stringResource(R.string.pair_expired), style = MaterialTheme.typography.bodyMedium)

        s.qrPayload?.let { payload ->
            SectionLabel(stringResource(R.string.pair_step3), modifier = Modifier.fillMaxWidth())
            // zxing throws (WriterException) when the payload exceeds what a QR code can hold — e.g. a
            // long custom URL plus credentials. Surface it instead of crashing the whole screen.
            val bmp = remember(payload) { runCatching { QrBitmap.render(payload, 800) }.getOrNull() }
            if (bmp == null) {
                Text(stringResource(R.string.pair_qr_error), color = MaterialTheme.colorScheme.error)
            } else {
                Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.pair_title), modifier = Modifier.size(320.dp))
                // A live countdown: the five-minute life was invisible until the code had already
                // died — and a factory reset takes longer than five minutes.
                s.expiresAt?.let { deadline ->
                    var now by remember { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(payload) { while (true) { now = System.currentTimeMillis(); delay(1_000L) } }
                    val left = (deadline - now).coerceAtLeast(0L)
                    Text(
                        stringResource(R.string.pair_countdown, String.format(Locale.US, "%d:%02d", left / 60_000L, (left / 1_000L) % 60L)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.pair_keep_open), style = MaterialTheme.typography.bodySmall)
                if (s.mode == ControllerPrefs.MODE_HOTSPOT) {
                    // Samsung and friends interrupt the join with a "no internet" warning; put the
                    // answer where the parent is standing when it appears (docs/architecture.md).
                    Text(stringResource(R.string.pair_hotspot_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (pairedName == null && s.apkServed) Text(stringResource(R.string.pair_downloading), style = MaterialTheme.typography.bodyMedium)
        pairedName?.let { Text(stringResource(R.string.pair_paired, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}
