package edu.fnosari.momedm.activities.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.ControllerViewModel
import edu.fnosari.momedm.activities.main.navigation.Routes

@Composable
fun DevicesScreen(navController: NavHostController, viewModel: ControllerViewModel) {
    val devices by viewModel.devices.collectAsState()
    val online by viewModel.online.collectAsState()
    val advertising by viewModel.advertising.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.main_advertising), style = MaterialTheme.typography.titleMedium)
                Switch(checked = advertising, onCheckedChange = { viewModel.setAdvertising(it) })
            }
            if (devices.isEmpty()) Text(stringResource(R.string.devices_empty), Modifier.padding(16.dp))
            LazyColumn { items(devices, key = { it.deviceId }) { d ->
                Surface(onClick = { navController.navigate(Routes.device(d.deviceId)) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(d.nickname ?: d.model, style = MaterialTheme.typography.bodyLarge)
                            Text(if (d.nickname != null) d.model else d.deviceId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(if (d.deviceId in online) R.string.devices_online else R.string.devices_offline),
                            color = if (d.deviceId in online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            } }
        }
        FloatingActionButton(onClick = { navController.navigate(Routes.PROVISION.name) }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) { Icon(Icons.Default.Add, contentDescription = null) }
    }
}
