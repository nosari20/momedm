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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.controller.provisioning.QrBitmap
import edu.fnosari.momedm.persistence.ControllerPrefs
import edu.fnosari.momedm.ui.common.SectionLabel
import edu.fnosari.momedm.ui.components.ButtonRequestPermission

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
                }
                if (s.mode == ControllerPrefs.MODE_HOTSPOT) {
                    // Hotspot credentials are transient and generated by the platform; show them read-only once available.
                    if (s.hotspotSsid.isNotBlank()) {
                        Text(stringResource(R.string.pair_ssid_value, stringResource(R.string.pair_ssid), s.hotspotSsid), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.pair_password_value, stringResource(R.string.pair_password), s.hotspotPassword), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedTextField(s.ssid, { pc.setManual(it, s.password) }, label = { Text(stringResource(R.string.pair_ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s.password, { pc.setManual(s.ssid, it) }, label = { Text(stringResource(R.string.pair_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pc.start() }, enabled = localNetworkGranted) { Text(stringResource(R.string.pair_generate)) }
            OutlinedButton(onClick = { pc.stop() }) { Text(stringResource(R.string.pair_stop)) }
        }
        if (s.mode == ControllerPrefs.MODE_HOTSPOT) s.ip?.takeIf { s.serverRunning }?.let {
            Text(stringResource(R.string.pair_serving, it), style = MaterialTheme.typography.bodySmall)
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        s.qrPayload?.let { payload ->
            SectionLabel(stringResource(R.string.pair_step3), modifier = Modifier.fillMaxWidth())
            // zxing throws (WriterException) when the payload exceeds what a QR code can hold — e.g. a
            // long custom URL plus credentials. Surface it instead of crashing the whole screen.
            val bmp = remember(payload) { runCatching { QrBitmap.render(payload, 800) }.getOrNull() }
            if (bmp == null) {
                Text(stringResource(R.string.pair_qr_error), color = MaterialTheme.colorScheme.error)
            } else {
                Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.pair_title), modifier = Modifier.size(320.dp))
                Text(stringResource(R.string.pair_help), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
