package edu.fnosari.momedm.activities.managed.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.ManagedViewModel

/** Status card + local actions for the managed device home screen. */
@Composable
fun HomeScreen(navController: NavHostController, viewModel: ManagedViewModel) {
    val status by viewModel.lastStatus.collectAsState()
    val error by viewModel.lastError.collectAsState()
    val yes = stringResource(R.string.managed_yes); val no = stringResource(R.string.managed_no)
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.managed_status_title), style = MaterialTheme.typography.titleMedium)
                StatusRow(stringResource(R.string.managed_status_kiosk), status?.let { if (it.kiosk) it.kioskPkg ?: yes else no } ?: "—")
                StatusRow(stringResource(R.string.managed_status_account), status?.let { if (it.account) yes else no } ?: "—")
                StatusRow(stringResource(R.string.managed_status_battery), status?.let { "${it.battery}%" } ?: "—")
                StatusRow(stringResource(R.string.managed_status_current), status?.currentApp ?: "—")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Button(onClick = { viewModel.addAccount() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.managed_action_account)) }
        Button(onClick = { viewModel.openUsageAccess() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.managed_action_usage)) }
        OutlinedButton(onClick = { viewModel.restartLink() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.managed_action_restart)) }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
