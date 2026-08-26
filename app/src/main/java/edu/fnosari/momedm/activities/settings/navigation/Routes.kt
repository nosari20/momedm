package edu.fnosari.momedm.activities.settings.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.graphics.vector.ImageVector
import edu.fnosari.momedm.R

enum class Routes(
    val label: Int,
    val icon: ImageVector,
) {
    CATEGORIES(R.string.settings_screen_title, Icons.Outlined.Settings),
    APPEARANCE(R.string.settings_appearance, Icons.Outlined.Settings),
    PIN(R.string.settings_pin, Icons.Outlined.Lock),
    CONNECTION(R.string.settings_connection, Icons.Outlined.Share),
    ADVANCED(R.string.settings_advanced, Icons.Outlined.Build),
    PRIVACY(R.string.settings_privacy, Icons.Outlined.Info),
    LEGAL(R.string.settings_screen_category_legal, Icons.Outlined.Info),
    LICENSES(R.string.settings_screen_category_licenses, Icons.Outlined.Info),
    EASTEREGG(R.string.settings_screen_category_easteregg, Icons.Outlined.Star),
}
