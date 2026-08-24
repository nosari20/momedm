package edu.fnosari.momedm.activities.managed

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.fnosari.momedm.R
import edu.fnosari.momedm.managed.LockController
import edu.fnosari.momedm.managed.ManagedLinkService
import edu.fnosari.momedm.managed.ManagedLinkState
import edu.fnosari.momedm.managed.ManagedSetup
import edu.fnosari.momedm.managed.PolicyManager
import edu.fnosari.momedm.persistence.KioskConfig
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.LockSchedule
import edu.fnosari.momedm.protocol.SafetyConfig
import edu.fnosari.momedm.protocol.LockState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Child-launcher state: apps to show, child-mode config, PIN/pause handling, link state. */
class ManagedViewModel(application: Application) : AndroidViewModel(application) {
    companion object { private const val LOG_TAG = "ManagedViewModel"; private const val PIN_LOCK_BASE_MS = 3_000L; private const val PIN_LOCK_MAX_MS = 60_000L }

    data class LauncherApp(val pkg: String, val label: String, val icon: Drawable?)

    private val prefs: ManagedPrefs = ManagedSetup.prefs(application)
    private val policy = PolicyManager(application, prefs)
    val linkState: StateFlow<ManagedLinkState.LinkState> = ManagedLinkState.state
    val lastStatus = ManagedLinkState.lastStatus
    /**
     * The persisted child-mode config, or **null while it is still loading**.
     *
     * Seeding with `KioskConfig()` instead would claim "child mode off" for the first frames of a
     * cold start, and a device that is actually in child mode would flash its whole app list (and
     * skip the pinned-app bounce) before the real config landed. Callers must treat null as "not
     * known yet": no tiles, no bounce, no resume.
     */
    val kioskConfig: StateFlow<KioskConfig?> = prefs.kioskConfig.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /**
     * The name the child chose for themselves, or blank.
     *
     * Local to this phone: it is not in ChildPrefs, never crosses the link, and never appears in the
     * parent's app. The parent decides the rules; this is the one thing the child decides.
     */
    val childName: StateFlow<String> = prefs.childName.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** True while the child is editing their name. */
    val namingOpen = MutableStateFlow(false)

    fun setChildName(name: String) { viewModelScope.launch { prefs.setChildName(name); namingOpen.value = false } }

    val pinSet: StateFlow<Boolean> = prefs.childPrefs.map { it.pinHash != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True while the parent menu is open on the child device.
     *
     * Deliberately state inside the launcher rather than a separate Activity: pausing child mode from
     * the menu has to call `stopLockTask()`, which only the Activity holding the lock task can do.
     */
    val menuOpen = MutableStateFlow(false)

    /**
     * True when the menu currently open was reached by entering the parent PIN.
     *
     * The menu is also reachable with no PIN at all, deliberately — a family that never set one must
     * still be able to see why the phone is behaving as it is, and must still have a way back if the
     * parent's phone is lost. But "reachable" and "may act" are different questions: without this, a
     * child on a locked phone could open the menu and simply pause child mode, or re-pair the device
     * to a controller of their own. Actions consult this; the information does not.
     */
    val menuAuthed = MutableStateFlow(false)

    /** Closes the menu and drops any authentication it was carrying. */
    fun closeMenu() { menuOpen.value = false; menuAuthed.value = false }

    /**
     * True while a finger is down on the launcher header.
     *
     * The pinned-app bounce waits for this to clear. Without it the parent had to complete a
     * long-press inside the bounce's grace period — the gesture alone takes most of it — so with an
     * app pinned the parent menu was, in practice, unreachable.
     */
    val headerPressed = MutableStateFlow(false)

    /** Everything the menu shows, read locally so it still works with no parent in range. */
    val controllerId: StateFlow<String> = prefs.controllerId.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val lockSchedule: StateFlow<LockSchedule> = prefs.lockSchedule.stateIn(viewModelScope, SharingStarted.Eagerly, LockSchedule())
    val safety: StateFlow<SafetyConfig> = prefs.safety.stateIn(viewModelScope, SharingStarted.Eagerly, SafetyConfig())
    val deviceOwner: Boolean get() = policy.isDeviceOwner

    /** Bumped by the Activity's ON_RESUME observer; combined with [kioskConfig] to drive the pinned-app bounce. */
    val resumeTick = MutableStateFlow(0)
    /** True while [edu.fnosari.momedm.activities.managed.components.PinDialog] is showing — suppresses the pinned-app bounce. */
    val pinDialogOpen = MutableStateFlow(false)

    /**
     * The current complete-lock state, or null while the persisted inputs are still loading (the
     * launcher must not flash its tiles at a device that turns out to be locked).
     *
     * Recomputed on every input change, on each ON_RESUME (via [resumeTick]) and once a minute, so a
     * window that opens while the screen is on takes effect without waiting for the alarm.
     */
    val lockState: StateFlow<LockState?> = combine(
        prefs.lockSchedule, prefs.manualLock, prefs.kioskConfig, resumeTick,
        flow { while (true) { emit(Unit); delay(30_000L) } },
    ) { schedule, manual, config, _, _ ->
        LockState.evaluate(schedule, manual, config.pauseUntil, System.currentTimeMillis(), ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _hiddenApps = MutableStateFlow(0)
    /**
     * How many installed apps the allowlist is currently keeping off the grid.
     *
     * Shown to the child, because "my parent chose this" and "my phone is broken" look identical from
     * a short grid, and only one of those is worth being upset about.
     */
    val hiddenApps: StateFlow<Int> = _hiddenApps

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
        // The lockout survives process death: restore it before the first tryPin can run, or
        // force-stopping the launcher would reset the brute-force backoff to zero every time.
        viewModelScope.launch {
            pinFailures = prefs.pinFailures.first()
            pinLockDeadline = prefs.pinLockUntil.first()
            if (pinLockDeadline > System.currentTimeMillis()) startPinLockTicker()
        }
        viewModelScope.launch { kioskConfig.filterNotNull().collect { refreshApps(); trackPause(it) } }
    }

    /**
     * Bumps [resumeTick] and re-evaluates the lock — called from the Activity's ON_RESUME observer.
     *
     * This is spec §1.4's "launcher resumes" trigger, and §1.10's compensating control for a missed
     * alarm (doze, an OEM task killer): as long as the launcher is ever brought back to the
     * foreground, its lock state converges within one resume, even if the alarm never fired. It is
     * safe to call unconditionally on every resume — including the resume that
     * [LockController.reevaluate] itself causes via `lockComplete()`/`applyKiosk()`'s
     * `CLEAR_TASK` relaunch — because [LockController] skips re-applying a [LockState] identical to
     * the one it last applied; see its KDoc for why that cannot get the device stuck in a wrong state.
     */
    fun onResumed() {
        resumeTick.value++
        viewModelScope.launch { LockController(getApplication(), prefs, policy).reevaluate() }
    }

    /**
     * Recomputes the tile list (allowed apps while locked, every launchable app otherwise); cancels
     * any prior run. A no-op while [kioskConfig] is still null — we cannot tell yet which of the two
     * lists to show, and showing the wrong one is worse than showing none.
     */
    fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val c = kioskConfig.value ?: return@launch
            val locked = c.isLocked(System.currentTimeMillis())
            _apps.value = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))
                    .filter { it.activityInfo.packageName != getApplication<Application>().packageName }
                    .map { LauncherApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
                    .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
                if (locked) all.filter { it.pkg in c.apps }.also { _hiddenApps.value = all.size - it.size }
                else all.also { _hiddenApps.value = 0 }
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
        if (c.on && c.pauseUntil > 0L && !c.isPaused(now)) {
            _pauseRemaining.value = 0L
            viewModelScope.launch { LockController(getApplication(), prefs, policy).reevaluate() }
            return
        }
        if (!c.isPaused(now)) { _pauseRemaining.value = 0L; return }
        pauseJob = viewModelScope.launch {
            while (isActive) {
                val left = c.pauseUntil - System.currentTimeMillis()
                if (left <= 0L) { _pauseRemaining.value = 0L; LockController(getApplication(), prefs, policy).reevaluate(); break }
                _pauseRemaining.value = left; delay(1_000L)
            }
        }
    }

    /** Opens [pkg] — via [PolicyManager.launchAllowed], which requests lock task only while locked. */
    fun open(pkg: String) {
        // lockState not loaded yet: no tiles are rendered, so this can only be a stale bounce request —
        // never ask for lock task on a guess. Reads lockState (not kioskConfig.isLocked) so this is the
        // last call site to converge on the same "locked" notion LockController itself uses — a plain
        // kiosk-mode flag has no idea a complete lock could apply with child mode off.
        val locked = lockState.value?.locked == true
        val ok = policy.launchAllowed(pkg, locked)
        if (!ok) Log.w(LOG_TAG, "Could not open $pkg")
    }

    /**
     * Verifies [pin]; on success closes the dialog and opens the parent menu.
     *
     * It deliberately does NOT release lock task. Releasing it here — which both callers used to do —
     * left the device unlocked with no pause recorded, so the next `reevaluate()` re-applied the kiosk
     * and relaunched this Activity with CLEAR_TASK, taking the ViewModel and the just-opened menu with
     * it. The menu appeared and vanished, which with an app pinned made the parent menu unreachable.
     * Lock task is released by [pauseFromMenu], after a pause deadline has actually been persisted.
     *
     * The failure counter and lockout deadline are persisted on every transition so the backoff cannot
     * be reset by killing the launcher. Neither the clear PIN nor its hash is stored or logged here.
     */
    fun tryPin(pin: String) { viewModelScope.launch {
        if (System.currentTimeMillis() < pinLockDeadline) return@launch
        if (policy.verifyPin(pin)) {
            pinFailures = 0; _pinError.value = null; pinLockDeadline = 0L; pinLockJob?.cancel(); _pinLockedRemaining.value = 0L
            prefs.setPinLock(0, 0L)
            // A correct PIN opens the parent menu rather than only pausing: the parent who just
            // proved themselves is usually here to look at something, and pausing is one tap away
            // inside it. The pause itself still happens through [pauseFromMenu], which is what
            // releases lock task.
            pinDialogOpen.value = false
            menuAuthed.value = true
            menuOpen.value = true
        } else {
            pinFailures++
            val lock = (PIN_LOCK_BASE_MS shl (pinFailures - 1).coerceAtMost(5)).coerceAtMost(PIN_LOCK_MAX_MS)
            pinLockDeadline = System.currentTimeMillis() + lock
            prefs.setPinLock(pinFailures, pinLockDeadline)
            _pinError.value = getApplication<Application>().getString(R.string.managed_pin_wrong)
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

    /**
     * Opens the device's emergency dialer from the bedtime screen.
     *
     * A completely locked phone must still be able to call for help. The power-menu route the design
     * relied on does not exist on every device — on a Samsung running Android 14, long-pressing power
     * under lock task shows no menu at all even with LOCK_TASK_FEATURE_GLOBAL_ACTIONS set — so the
     * lock allowlists the dialer and the child gets a visible button instead.
     */
    fun openEmergencyDialer() {
        if (!policy.launchEmergencyDialer()) Log.w(LOG_TAG, "Emergency dialer unavailable")
    }

    fun clearPinError() { _pinError.value = null }

    /**
     * Pauses child mode from the parent menu and closes it, letting the Activity release lock task.
     *
     * Separate from [tryPin] so the PIN proves who you are and this decides what happens next — the
     * parent may only have wanted to read the menu.
     */
    fun pauseFromMenu(onPaused: () -> Unit) {
        viewModelScope.launch {
            policy.pause()
            closeMenu()
            onPaused()
        }
    }

    /** Ends a pause now (re-locks). */
    fun relock() { viewModelScope.launch { LockController(getApplication(), prefs, policy).endPause() } }

    fun ensureLink() { if (ManagedLinkState.state.value == ManagedLinkState.LinkState.IDLE) ManagedLinkService.start(getApplication()) }
}
