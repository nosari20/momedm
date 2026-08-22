package edu.fnosari.momedm.activities.main.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.graphics.vector.ImageVector
import edu.fnosari.momedm.R

/** Top-level destinations of the controller UI, plus the parameterized device-detail route. */
enum class Routes(val label: Int, val icon: ImageVector) {
    DEVICES(R.string.main_route_devices, Icons.Default.List),
    PROVISION(R.string.main_route_provision, Icons.Default.Add);
    companion object { const val ROUTE_DEVICE = "device/{deviceId}"; fun device(id: String) = "device/$id" }
}
