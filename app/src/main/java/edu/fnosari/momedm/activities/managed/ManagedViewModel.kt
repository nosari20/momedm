package edu.fnosari.momedm.activities.managed

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.fnosari.momedm.managed.ManagedLinkService
import edu.fnosari.momedm.managed.ManagedLinkState
import edu.fnosari.momedm.managed.ManagedSetup
import edu.fnosari.momedm.managed.PolicyManager
import edu.fnosari.momedm.persistence.KioskConfig
import edu.fnosari.momedm.persistence.ManagedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Child-launcher state: apps to show, child-mode config, PIN/pause handling, link state. */
class ManagedViewModel(application: Application) : AndroidViewModel(application) {
    companion object { private const val LOG_TAG = "ManagedViewModel"; private const val PIN_LOCK_BASE_MS = 3_000L; private const val PIN_LOCK_MAX_MS = 60_000L }

    data class LauncherApp(val pkg: String, val label: String, val icon: Drawable?)

    private val prefs: ManagedPrefs = ManagedSetup.prefs(application)
    private val policy = PolicyManager(application, prefs)
    val linkState: StateFlow<ManagedLinkState.LinkState> = ManagedLinkState.state
    val lastStatus = ManagedLinkState.lastStatus
    val lastError = ManagedLinkState.lastError
    val kioskConfig: StateFlow<KioskConfig> = prefs.kioskConfig.stateIn(viewModelScope, SharingStarted.Eagerly, KioskConfig())
    val pinSet: StateFlow<Boolean> = prefs.childPrefs.map { it.pinHash != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _apps = MutableStateFlow<List<LauncherApp>>(emptyList())
    val launcherApps: StateFlow<List<LauncherApp>> = _apps
    private val _pauseRemaining = MutableStateFlow(0L)
    val pauseRemainingMs: StateFlow<Long> = _pauseRemaining
    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError
    private val _pinLockedUntil = MutableStateFlow(0L)
    val pinLockedUntilMs: StateFlow<Long> = _pinLockedUntil
    private var pinFailures = 0
    private var pauseJob: Job? = null

    init {
        viewModelScope.launch { kioskConfig.collect { refreshApps(); trackPause(it) } }
    }

    /** Recomputes the tile list (allowed apps when child mode is on, every launchable app otherwise). */
    fun refreshApps() { viewModelScope.launch {
        val c = kioskConfig.value
        _apps.value = withContext(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))
                .filter { it.activityInfo.packageName != getApplication<Application>().packageName }
                .map { LauncherApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
                .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
            if (c.on) all.filter { it.pkg in c.apps } else all
        }
    } }

    private fun trackPause(c: KioskConfig) {
        pauseJob?.cancel()
        if (!c.isPaused(System.currentTimeMillis())) { _pauseRemaining.value = 0L; return }
        pauseJob = viewModelScope.launch {
            while (isActive) {
                val left = c.pauseUntil - System.currentTimeMillis()
                if (left <= 0L) { _pauseRemaining.value = 0L; policy.resume(); break }
                _pauseRemaining.value = left; delay(1_000L)
            }
        }
    }

    /** Pinned app to auto-launch when the launcher comes to the front, or null. */
    fun shouldAutoLaunchPinned(): String? = kioskConfig.value.let { if (it.isLocked(System.currentTimeMillis())) it.pinned else null }

    /** Opens [pkg] — via [PolicyManager.launchAllowed], which requests lock task only while locked. */
    fun open(pkg: String) {
        val locked = kioskConfig.value.isLocked(System.currentTimeMillis())
        val ok = policy.launchAllowed(pkg, locked)
        if (!ok) Log.w(LOG_TAG, "Could not open $pkg")
    }

    /** Verifies [pin]; on success starts a pause and calls [onSuccess] (the Activity then releases lock task). */
    fun tryPin(pin: String, onSuccess: () -> Unit) { viewModelScope.launch {
        if (System.currentTimeMillis() < _pinLockedUntil.value) return@launch
        if (policy.verifyPin(pin)) {
            pinFailures = 0; _pinError.value = null; _pinLockedUntil.value = 0L
            policy.pause(); onSuccess()
        } else {
            pinFailures++
            val lock = (PIN_LOCK_BASE_MS shl (pinFailures - 1).coerceAtMost(5)).coerceAtMost(PIN_LOCK_MAX_MS)
            _pinLockedUntil.value = System.currentTimeMillis() + lock
            _pinError.value = getApplication<Application>().getString(edu.fnosari.momedm.R.string.pin_wrong)
        }
    } }
    fun clearPinError() { _pinError.value = null }

    /** Ends a pause now (re-locks). */
    fun relock() { viewModelScope.launch { policy.resume() } }

    fun addAccount() { viewModelScope.launch { policy.openAddAccount() } }
    fun openUsageAccess() {
        runCatching { getApplication<Application>().startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(LOG_TAG, "Usage access settings unavailable", it); ManagedLinkState.lastError.value = "Usage access settings unavailable" }
    }
    fun restartLink() = ManagedLinkService.restart(getApplication())
    fun ensureLink() { if (ManagedLinkState.state.value == ManagedLinkState.LinkState.IDLE) ManagedLinkService.start(getApplication()) }
}
