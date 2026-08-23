package edu.fnosari.momedm.activities.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SettingsCategoryItem(title: String, icon: ImageVector, supportingText: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dp(28.0F)), tint = MaterialTheme.colorScheme.onSurface)
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/**
 * One appearance-style setting row: a title, an optional value line, and either a trailing
 * colour swatch (for the accent picker) or nothing, opening [onClick] when tapped.
 */
@Composable
fun SettingRow(title: String, value: String?, trailingSwatch: Int? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it) } },
        trailingContent = trailingSwatch?.let {
            {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(it))
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SettingsCategoryDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
fun SettingsAppVersion(versionText: String, copyrights: String, onClick: () -> Unit) {
    Surface(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Box(
                modifier = Modifier.size(30.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(versionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.44f))
                Text(copyrights, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.44f))
            }
        }
    }
}




