package edu.fnosari.momedm.activities.managed

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import edu.fnosari.momedm.activities.managed.components.LinkBanner
import edu.fnosari.momedm.activities.managed.navigation.Routes
import edu.fnosari.momedm.activities.managed.screens.HomeScreen
import edu.fnosari.momedm.ui.components.ButtonRequestPermission
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.ui.layouts.Layout
import edu.fnosari.momedm.ui.theme.MomeDMTheme

/** HOME activity of a managed device: permission gate, drawer + NavHost, link banner. */
class ManagedHomeActivity : ComponentActivity() {
    companion object { private const val LOG_TAG = "ManagedHomeActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            val vm: ManagedViewModel = viewModel()
            MomeDMTheme {
                val required = remember { mutableStateListOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, POST_NOTIFICATIONS) }
                val missing = required.filter { ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                Log.d(LOG_TAG, "Missing permissions: $missing")
                if (missing.isNotEmpty()) {
                    BasicLayoutWithTopBar(title = context.getString(R.string.managed_activity_title)) {
                        Column { for (p in missing) ButtonRequestPermission(context, p, p, granted = { required.remove(p) }, denied = { Log.d(LOG_TAG, "$p denied") }) }
                    }
                } else {
                    LaunchedEffect(Unit) { vm.ensureLink() }
                    val link by vm.linkState.collectAsState()
                    Layout.BasicLayoutWithTopBarAndDrawer(
                        title = context.getString(R.string.managed_activity_title),
                        drawerItems = listOf(Layout.DrawerItem(context.getString(Routes.HOME.label), Routes.HOME.icon) { navController.navigate(Routes.HOME.name) }),
                        drawerName = context.getString(R.string.managed_drawer_name),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            LinkBanner(link)
                            NavHost(navController, startDestination = Routes.HOME.name) {
                                composable(Routes.HOME.name) { HomeScreen(navController, vm) }
                            }
                        }
                    }
                }
            }
        }
    }
}
