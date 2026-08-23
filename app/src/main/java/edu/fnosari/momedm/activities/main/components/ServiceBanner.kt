package edu.fnosari.momedm.activities.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R

/** Persistent warning strip shown whenever BLE advertising is off, since managed devices can't reach the controller. */
@Composable
fun ServiceBanner(advertising: Boolean) {
    AnimatedVisibility(visible = !advertising) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.children_not_visible), Modifier.padding(16.dp, 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
