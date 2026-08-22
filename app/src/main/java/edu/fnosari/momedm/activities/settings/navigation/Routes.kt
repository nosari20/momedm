package edu.fnosari.momedm.activities.settings.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.graphics.vector.ImageVector
import edu.fnosari.momedm.R

enum class Routes(
    val label: Int,
    val icon: ImageVector,
) {
    CATEGORIES(R.string.settings_screen_title, Icons.Outlined.Settings),
    SETTINGS_CONTROLLER(R.string.settings_screen_category_controller, Icons.Outlined.Settings),
    SETTINGS_LEGAL(R.string.settings_screen_category_legal, Icons.Outlined.Info),
    SETTINGS_LICENSES(R.string.settings_screen_category_licenses, Icons.Outlined.Info),
    SETTINGS_EASTEREGG(R.string.settings_screen_category_easteregg, Icons.Outlined.Star),
}
