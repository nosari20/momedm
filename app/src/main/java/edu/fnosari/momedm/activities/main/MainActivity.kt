package edu.fnosari.momedm.activities.main

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.main.components.OnlineIndicator
import edu.fnosari.momedm.activities.main.components.ServiceBanner
import edu.fnosari.momedm.activities.main.navigation.Routes
import edu.fnosari.momedm.activities.main.screens.DeviceScreen
import edu.fnosari.momedm.activities.main.screens.DevicesScreen
import edu.fnosari.momedm.activities.main.screens.ProvisionScreen
import edu.fnosari.momedm.activities.managed.ManagedHomeActivity
import edu.fnosari.momedm.activities.settings.SettingsActivity
import edu.fnosari.momedm.ui.components.ButtonRequestPermission
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.ui.layouts.Layout
import edu.fnosari.momedm.ui.theme.MomeDMTheme

/** Launcher entry. Device owner → managed home; otherwise the controller UI. */
class MainActivity : ComponentActivity() {
    companion object { private const val LOG_TAG = "MainActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(packageName)) {
            Log.d(LOG_TAG, "Device owner → managed role")
            startActivity(Intent(this, ManagedHomeActivity::class.java)); finish(); return
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            val vm: ControllerViewModel = viewModel()
            val snackbar = remember { SnackbarHostState() }
            MomeDMTheme {
                val required = remember { mutableStateListOf(BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, POST_NOTIFICATIONS, NEARBY_WIFI_DEVICES) }
                val missing = required.filter { ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                Log.d(LOG_TAG, "Missing permissions: $missing")
                if (missing.isNotEmpty()) {
                    BasicLayoutWithTopBar(title = context.getString(R.string.main_activity_title)) {
                        Column { for (p in missing) ButtonRequestPermission(context, p, p, granted = { required.remove(p) }, denied = { Log.d(LOG_TAG, "$p denied") }) }
                    }
                } else {
                    val advertising by vm.advertising.collectAsState()
                    val online by vm.online.collectAsState()
                    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
                    LaunchedEffect(Unit) { vm.startServiceIfWanted() }
                    Layout.BasicLayoutWithTopBarAndDrawer(
                        title = context.getString(R.string.main_activity_title),
                        rightActions = {
                            OnlineIndicator(advertising, online.size)
                            IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) { Icon(Icons.Filled.Settings, contentDescription = context.getString(R.string.main_settings_button)) }
                        },
                        drawerItems = Routes.entries.map { r -> Layout.DrawerItem(context.getString(r.label), r.icon) { navController.navigate(r.name) } },
                        drawerName = context.getString(R.string.main_drawer_name),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            ServiceBanner(advertising)
                            NavHost(navController, startDestination = Routes.DEVICES.name, modifier = Modifier.weight(1f)) {
                                composable(Routes.DEVICES.name) { DevicesScreen(navController, vm) }
                                composable(Routes.PROVISION.name) { ProvisionScreen(navController, vm) }
                                composable(Routes.ROUTE_DEVICE) { back -> DeviceScreen(navController, vm, back.arguments?.getString("deviceId") ?: "") }
                            }
                            SnackbarHost(snackbar)
                        }
                    }
                }
            }
        }
    }
}
