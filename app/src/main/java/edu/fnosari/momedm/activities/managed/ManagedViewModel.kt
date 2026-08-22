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

    /** Bumped by the Activity's ON_RESUME observer; combined with [kioskConfig] to drive the pinned-app bounce. */
    val resumeTick = MutableStateFlow(0)
    /** True while [edu.fnosari.momedm.activities.managed.components.PinDialog] is showing — suppresses the pinned-app bounce. */
    val pinDialogOpen = MutableStateFlow(false)

    private val _apps = MutableStateFlow<List<LauncherApp>>(emptyList())
    val launcherApps: StateFlow<List<LauncherApp>> = _apps
    private val _pauseRemaining = MutableStateFlow(0L)
    val pauseRemainingMs: StateFlow<Long> = _pauseRemaining
    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError
    private val _pinLockedRemaining = MutableStateFlow(0L)
    /** Milliseconds left on the PIN lockout, ticking every 250 ms; 0 when not locked out. */
    val pinLockedRemainingMs: StateFlow<Long> = _pinLockedRemaining
    private var pinLockDeadline = 0L
    private var pinFailures = 0
    private var pauseJob: Job? = null
    private var pinLockJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch { kioskConfig.collect { refreshApps(); trackPause(it) } }
    }

    /** Bumps [resumeTick] — called from the Activity's ON_RESUME observer. */
    fun onResumed() { resumeTick.value++ }

    /** Recomputes the tile list (allowed apps while locked, every launchable app otherwise); cancels any prior run. */
    fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val c = kioskConfig.value
            val locked = c.isLocked(System.currentTimeMillis())
            _apps.value = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))
                    .filter { it.activityInfo.packageName != getApplication<Application>().packageName }
                    .map { LauncherApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
                    .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
                if (locked) all.filter { it.pkg in c.apps } else all
            }
        }
    }

    /**
     * Tracks the pause countdown, and resumes lock task as soon as it lapses — including when this
     * [ManagedViewModel] is recreated (e.g. process death) *after* a stored [KioskConfig.pauseUntil]
     * has already passed: without this, nothing would ever call [PolicyManager.resume] for it.
     */
    private fun trackPause(c: KioskConfig) {
        pauseJob?.cancel()
        val now = System.currentTimeMillis()
        if (c.on && c.pauseUntil > 0L && !c.isPaused(now)) { _pauseRemaining.value = 0L; viewModelScope.launch { policy.resume() }; return }
        if (!c.isPaused(now)) { _pauseRemaining.value = 0L; return }
        pauseJob = viewModelScope.launch {
            while (isActive) {
                val left = c.pauseUntil - System.currentTimeMillis()
                if (left <= 0L) { _pauseRemaining.value = 0L; policy.resume(); break }
                _pauseRemaining.value = left; delay(1_000L)
            }
        }
    }

    /** Opens [pkg] — via [PolicyManager.launchAllowed], which requests lock task only while locked. */
    fun open(pkg: String) {
        val locked = kioskConfig.value.isLocked(System.currentTimeMillis())
        val ok = policy.launchAllowed(pkg, locked)
        if (!ok) Log.w(LOG_TAG, "Could not open $pkg")
    }

    /** Verifies [pin]; on success starts a pause and calls [onSuccess] (the Activity then releases lock task). */
    fun tryPin(pin: String, onSuccess: () -> Unit) { viewModelScope.launch {
        if (System.currentTimeMillis() < pinLockDeadline) return@launch
        if (policy.verifyPin(pin)) {
            pinFailures = 0; _pinError.value = null; pinLockDeadline = 0L; pinLockJob?.cancel(); _pinLockedRemaining.value = 0L
            policy.pause(); onSuccess()
        } else {
            pinFailures++
            val lock = (PIN_LOCK_BASE_MS shl (pinFailures - 1).coerceAtMost(5)).coerceAtMost(PIN_LOCK_MAX_MS)
            pinLockDeadline = System.currentTimeMillis() + lock
            _pinError.value = getApplication<Application>().getString(edu.fnosari.momedm.R.string.pin_wrong)
            startPinLockTicker()
        }
    } }

    /** Ticks [pinLockedRemainingMs] down to 0 every 250 ms so the dialog's countdown is observable, not frozen. */
    private fun startPinLockTicker() {
        pinLockJob?.cancel()
        pinLockJob = viewModelScope.launch {
            while (isActive) {
                val left = pinLockDeadline - System.currentTimeMillis()
                if (left <= 0L) { _pinLockedRemaining.value = 0L; break }
                _pinLockedRemaining.value = left; delay(250L)
            }
        }
    }

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
