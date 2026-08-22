package edu.fnosari.momedm.activities.managed.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R
import edu.fnosari.momedm.managed.ManagedLinkState.LinkState

/** Full-width banner visible whenever the link is not authenticated. */
@Composable
fun LinkBanner(state: LinkState, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = state != LinkState.AUTHENTICATED, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically(), modifier = modifier) {
        val busy = state == LinkState.SCANNING || state == LinkState.CONNECTED
        val bg = if (busy) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
        val fg = if (busy) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
        Surface(color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg); Spacer(Modifier.width(12.dp)) }
                Text(stringResource(when (state) { LinkState.SCANNING -> R.string.managed_link_scanning; LinkState.CONNECTED -> R.string.managed_link_connected; else -> R.string.managed_link_idle }), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
