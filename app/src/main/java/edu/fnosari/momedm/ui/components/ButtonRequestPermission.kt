package edu.fnosari.momedm.ui.components

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import edu.fnosari.momedm.R

/** Maps a raw runtime-permission constant to a short, parent-facing name; falls back to the raw permission string for anything unmapped. */
private fun friendlyPermissionName(context: Context, permission: String): String = when (permission) {
    BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE -> context.getString(R.string.perm_bluetooth)
    POST_NOTIFICATIONS -> context.getString(R.string.perm_notifications)
    NEARBY_WIFI_DEVICES -> context.getString(R.string.perm_nearby)
    else -> permission
}

/** Runtime-permission request button, shared by both role activities. Label reads "Allow <friendly name>". */
@Composable
fun ButtonRequestPermission(context: Context, permission: String, granted: () -> Unit, denied: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) granted() else denied() }
    val label = stringResource(R.string.permission_allow, friendlyPermissionName(context, permission))
    Button(onClick = { launcher.launch(permission) }) { Text(label) }
}
