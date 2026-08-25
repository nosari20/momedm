package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R

/**
 * Whether this phone is currently discoverable, plus how many children are connected.
 *
 * Filled when discoverable, a hollow ring when not — the state is carried by shape as well as by
 * colour, because green-vs-red alone says nothing to a colourblind parent, and this dot is how they
 * would notice that "Visible to children" is off. That switch being off is the usual reason a child
 * fails to connect, so it is the one piece of state in the app worth over-communicating.
 *
 * The count beside it answers a different question ("how many are here"), which is why the dot needs
 * its own description rather than borrowing that label.
 */
@Composable
fun OnlineIndicator(advertising: Boolean, online: Int, modifier: Modifier = Modifier) {
    val color = if (advertising) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    val label = stringResource(if (advertising) R.string.conn_indicator_on else R.string.conn_indicator_off)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(horizontal = 8.dp)) {
        Spacer(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .then(if (advertising) Modifier.background(color) else Modifier.border(2.dp, color, CircleShape))
                .semantics { contentDescription = label },
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.children_online_count, online), style = MaterialTheme.typography.labelLarge)
    }
}
