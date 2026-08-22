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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.screens.ChildLauncherScreen
import edu.fnosari.momedm.ui.components.ButtonRequestPermission
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import edu.fnosari.momedm.ui.theme.MomeDMTheme

/** HOME activity of a managed device: permission gate, then the child launcher (tiles grid, pinned relaunch, PIN dialog). */
class ManagedHomeActivity : ComponentActivity() {
    companion object { private const val LOG_TAG = "ManagedHomeActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val vm: ManagedViewModel = viewModel()
            MomeDMTheme {
                val required = remember { mutableStateListOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, POST_NOTIFICATIONS) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) required.removeAll { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
                    owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
                }
                val missing = required.filter { ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                Log.d(LOG_TAG, "Missing permissions: $missing")
                if (missing.isNotEmpty()) {
                    BasicLayoutWithTopBar(title = context.getString(R.string.managed_activity_title)) {
                        Column { for (p in missing) ButtonRequestPermission(context, p, p, granted = { required.remove(p) }, denied = { Log.d(LOG_TAG, "$p denied") }) }
                    }
                } else {
                    LaunchedEffect(Unit) { vm.ensureLink(); vm.refreshApps() }
                    // Pinned app: every time the launcher comes to the front while locked, bounce into it.
                    DisposableEffect(owner) {
                        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.shouldAutoLaunchPinned()?.let { vm.open(it) } }
                        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
                    }
                    ChildLauncherScreen(vm, onUnlocked = {
                        // PolicyManager.pause() already persisted the deadline; release lock task from the Activity.
                        runCatching { stopLockTask() }.onFailure { Log.w(LOG_TAG, "stopLockTask failed", it) }
                    })
                }
            }
        }
    }
}
