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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import edu.fnosari.momedm.ui.ControllerThemed
import edu.fnosari.momedm.ui.components.ButtonRequestPermissions
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.ui.layouts.Layout

/**
 * Launcher entry. Device owner → managed home; otherwise the controller UI.
 *
 * Language: at this app's `minSdk 34`, [android.app.LocaleManager] always owns the per-app
 * locale — [edu.fnosari.momedm.ui.AppLocale.apply] (called from Settings) is sufficient and
 * there is nothing to do in `attachBaseContext` here.
 */
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
            ControllerThemed(this) {
                // ACCESS_LOCAL_NETWORK is deliberately NOT gated here: it is only needed to serve the
                // APK while pairing, so ProvisionScreen asks for it in context rather than blocking
                // the whole app — everything else works over BLE without it.
                val required = remember { mutableStateListOf(BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, POST_NOTIFICATIONS, NEARBY_WIFI_DEVICES) }
                // A permission granted from system Settings (rather than through our own dialog) never
                // fires the launcher callback, so re-check on every resume and drop what is now granted
                // — otherwise the gate stays up until the activity is recreated. Mirrors
                // ManagedHomeActivity.
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) required.removeAll { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
                    owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
                }
                val missing = required.filter { ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                Log.d(LOG_TAG, "Missing permissions: $missing")
                if (missing.isNotEmpty()) {
                    BasicLayoutWithTopBar(title = context.getString(R.string.children_title)) {
                        // One explained request, not a wall of identical "Allow Bluetooth" buttons:
                        // the three Bluetooth grants are a single system dialog anyway, and a parent
                        // told the why first is far less likely to tap "Don't allow" their way into
                        // a never-ask-again dead end on the app's very first screen.
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(context.getString(R.string.perm_intro))
                            ButtonRequestPermissions(
                                permissions = missing,
                                onResult = { required.removeAll { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } },
                            )
                        }
                    }
                } else {
                    val advertising by vm.advertising.collectAsState()
                    val online by vm.online.collectAsState()
                    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
                    LaunchedEffect(Unit) { vm.startServiceIfWanted() }
                    Layout.BasicLayoutWithTopBarAndDrawer(
                        title = context.getString(R.string.children_title),
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
