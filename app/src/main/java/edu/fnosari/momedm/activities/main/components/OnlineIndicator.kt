package edu.fnosari.momedm.activities.main.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import edu.fnosari.momedm.R

/** Green/red dot + "n online" label for the top bar. */
@Composable
fun OnlineIndicator(advertising: Boolean, online: Int, modifier: Modifier = Modifier) {
    val color = if (advertising) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(horizontal = 8.dp)) {
        Spacer(Modifier.size(12.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.main_online_count, online), style = MaterialTheme.typography.labelLarge)
    }
}
