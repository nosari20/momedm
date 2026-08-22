package edu.fnosari.momedm.ui.components

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import edu.fnosari.momedm.R

/** Runtime-permission request button, shared by both role activities. */
@Composable
fun ButtonRequestPermission(context: Context, permission: String, description: String, granted: () -> Unit, denied: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) granted() else denied() }
    Button(onClick = { launcher.launch(permission) }) { Text("${context.getString(R.string.managed_allow)} $description") }
}
