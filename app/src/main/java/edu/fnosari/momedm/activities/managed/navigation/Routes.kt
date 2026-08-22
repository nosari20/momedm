package edu.fnosari.momedm.activities.managed.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import edu.fnosari.momedm.R

/** Drawer/navigation destinations of [edu.fnosari.momedm.activities.managed.ManagedHomeActivity]. */
enum class Routes(val label: Int, val icon: ImageVector) {
    HOME(R.string.managed_route_home, Icons.Default.Home),
}
