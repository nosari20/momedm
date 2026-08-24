package edu.fnosari.momedm.activities.managed

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.fnosari.momedm.R
import edu.fnosari.momedm.activities.managed.screens.BedtimeScreen
import edu.fnosari.momedm.activities.managed.screens.ChildLauncherScreen
import edu.fnosari.momedm.activities.managed.screens.ChildMenuScreen
import edu.fnosari.momedm.ui.ManagedThemed
import edu.fnosari.momedm.ui.components.ButtonRequestPermission
import edu.fnosari.momedm.ui.layouts.BasicLayoutWithTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull

/**
 * HOME activity of a managed device: permission gate, then the child launcher (tiles grid,
 * pinned relaunch, PIN dialog).
 *
 * Language: at this app's `minSdk 34`, [android.app.LocaleManager] always owns the per-app
 * locale ([edu.fnosari.momedm.ui.AppLocale.apply], driven by [edu.fnosari.momedm.managed.PolicyManager]
 * applying pushed prefs) — there is nothing to do in `attachBaseContext` here.
 */
class ManagedHomeActivity : ComponentActivity() {
    companion object {
        private const val LOG_TAG = "ManagedHomeActivity"
        /** Grace period before bouncing into the pinned app, so a parent can tap the launcher's lock icon first. */
        private const val PINNED_BOUNCE_DELAY_MS = 1_500L

    }

    /**
     * HOME lands on the launcher, never back on the parent's menu.
     *
     * Enabling the home button in lock task made this reachable: this activity is `singleTask` and is
     * the device's HOME, so pressing home delivers a new intent here instead of starting anything —
     * and a child pressing home while the menu happened to be open would be handed the parent's menu.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            ViewModelProvider(this)[ManagedViewModel::class.java].closeMenu()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val vm: ManagedViewModel = viewModel()
            ManagedThemed(this) {
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
                        Column { for (p in missing) ButtonRequestPermission(context, p, granted = { required.remove(p) }, denied = { Log.d(LOG_TAG, "$p denied") }) }
                    }
                } else {
                    LaunchedEffect(Unit) { vm.ensureLink() }
                    // Pinned app: reacts to config changes and to ON_RESUME (via resumeTick) rather than reading a
                    // one-shot snapshot, so it also fires on a cold start once the persisted config has loaded.
                    // filterNotNull skips the "still loading" seed — there is nothing to bounce into yet.
                    LaunchedEffect(Unit) {
                        combine(vm.kioskConfig, vm.resumeTick) { c, _ -> c }.filterNotNull().collect { c ->
                            val now = System.currentTimeMillis()
                            if (vm.lockState.value?.locked != true && c.isLocked(now) && c.pinned != null &&
                                !vm.menuOpen.value && !vm.pinDialogOpen.value && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                delay(PINNED_BOUNCE_DELAY_MS)
                                // A finger on the header means someone is starting the long-press that
                                // opens the parent menu. Waiting it out costs the child nothing — they
                                // are held on the launcher, not let out of it — and it is what makes the
                                // menu reachable at all when an app is pinned.
                                while (vm.headerPressed.value) delay(200L)
                                // RESUMED is re-checked after the grace period too: the child may have opened
                                // another allowed app while we waited, and bouncing from a launcher that is no
                                // longer in front would yank them straight back out of it. The menu is checked
                                // for the same reason — a parent reading it must not be thrown out of it.
                                if (vm.lockState.value?.locked != true && !vm.pinDialogOpen.value && !vm.menuOpen.value &&
                                    vm.kioskConfig.value?.isLocked(System.currentTimeMillis()) == true &&
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.open(c.pinned)
                            }
                        }
                    }
                    DisposableEffect(owner) {
                        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onResumed() }
                        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
                    }
                    val lock by vm.lockState.collectAsState()
                    val onUnlocked: () -> Unit = {
                        // PolicyManager.pause() already persisted the deadline; release lock task here.
                        runCatching { stopLockTask() }.onFailure { Log.w(LOG_TAG, "stopLockTask failed", it) }
                    }
                    val menuOpen by vm.menuOpen.collectAsState()
                    when {
                        // The menu is a state of this Activity, not its own: pausing child mode from
                        // it has to call stopLockTask(), which only the Activity holding the lock task
                        // can do.
                        menuOpen -> ChildMenuScreen(vm, onPause = { vm.pauseFromMenu(onUnlocked) })
                        lock?.locked == true -> BedtimeScreen(vm)
                        else -> ChildLauncherScreen(vm)
                    }
                }
            }
        }
    }
}
