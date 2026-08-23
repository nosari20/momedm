package edu.fnosari.momedm.managed

import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import edu.fnosari.momedm.activities.managed.ManagedHomeActivity
import edu.fnosari.momedm.persistence.KioskConfig
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.protocol.LockSchedule
import edu.fnosari.momedm.protocol.LockState
import edu.fnosari.momedm.protocol.PinHash
import edu.fnosari.momedm.ui.AppLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Thin, logged wrapper around [DevicePolicyManager] for the managed role. */
class PolicyManager(private val context: Context, private val prefs: ManagedPrefs) : PolicyActions, LockActions {
    companion object {
        private const val LOG_TAG = "PolicyManager"
        const val PLAY_PKG = "com.android.vending"
        const val GMS_PKG = "com.google.android.gms"
        /** The long-standing implicit action for the platform emergency dialer; OEMs answer it with their own component. */
        const val EMERGENCY_DIAL_ACTION = "com.android.phone.EmergencyDialer.DIAL"
    }
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    /** The [AdminReceiver] component this app is (or would be) registered as device owner with. */
    val admin = ComponentName(context, AdminReceiver::class.java)
    /** True once this app has been set as device owner (via `dpm set-device-owner` during provisioning). */
    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * Grants this app the runtime permissions it needs to reach the parent, using the device owner's
     * power to grant its own.
     *
     * Nobody can grant these by hand on a child device: the phone is handed to a child, the link
     * service starts at boot with no one looking at it, and a launcher that cannot scan just sits
     * there. Without [android.Manifest.permission.BLUETOOTH_SCAN] the client never starts scanning at
     * all — `ERROR_BLUETOOTH_SCAN_PERMISSION_NOT_GRANTED` — so the child never finds the parent and
     * every status push is dropped as unauthenticated, which looks exactly like a failed pairing.
     *
     * Idempotent and safe on every service start: re-granting an already-granted permission is a
     * no-op. Does nothing when this app is not the device owner.
     */
    fun grantOwnRuntimePermissions() {
        if (!isDeviceOwner) return
        val permissions = listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        for (p in permissions) {
            val ok = runCatching {
                dpm.setPermissionGrantState(admin, context.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.getOrElse { Log.w(LOG_TAG, "Could not grant ${p.substringAfterLast('.')}: ${it::class.simpleName}"); false }
            if (!ok) Log.w(LOG_TAG, "Grant refused for ${p.substringAfterLast('.')}")
        }
        Log.d(LOG_TAG, "Self-granted the runtime permissions needed for the parent link")
    }

    /** Makes [ManagedHomeActivity] the persistent HOME so the device boots into us. */
    fun setAsDefaultHome() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
        dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, ManagedHomeActivity::class.java))
        Log.d(LOG_TAG, "Persistent HOME set")
    }

    /** Launches our launcher in lock task (the config decides what it shows; the launcher itself drives the pinned-app bounce). */
    private fun launchHomeLocked() {
        val home = Intent(context, ManagedHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(home, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
    }

    /** Launches our launcher *without* lock task (used when leaving a lock). */
    private fun launchHomePlain() {
        val home = Intent(context, ManagedHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(home)
    }

    // Both kiosk transitions suspend (they write to DataStore), so a plain runCatching would swallow the
    // CancellationException thrown when the caller's scope is cancelled and report it as an ordinary
    // command failure, leaving the coroutine machinery to believe the work completed. Cancellation is
    // rethrown; everything else becomes a Result.failure the controller can surface.
    //
    // Neither of these public entry points touches the DPM directly: they validate, persist the
    // *intent* (child mode should be on/off), and then hand the decision to
    // [LockController.reevaluate], which is the only component that knows whether a complete lock
    // currently outranks it. A parent tapping "Choose allowed apps…" or "Stop child mode" while the
    // child is under a complete lock must not reinstate the plain kiosk allowlist (dropping
    // GLOBAL_ACTIONS) or end lock task outright — reevaluate() re-derives the correct state from
    // scratch and applies exactly that, whether that is this new config or the still-active lock.
    // Do not call [applyKiosk]/[clearKiosk] from here directly — that would apply the change
    // immediately regardless of an active lock, which is the bug this split exists to fix.
    override suspend fun kioskOn(apps: List<String>, pinned: String?): Result<List<String>> = try {
        val pm = context.packageManager
        val allowed = apps.distinct().filter { pm.getLaunchIntentForPackage(it) != null }
        if (allowed.isEmpty()) throw IllegalArgumentException("no allowed app installed")
        val pin = pinned?.takeIf { it in allowed }
        if (pinned != null && pin == null) Log.w(LOG_TAG, "Requested pinned app $pinned dropped: not installed or not allowed")
        prefs.setKioskConfig(KioskConfig(on = true, apps = allowed, pinned = pin, pauseUntil = 0L))
        LockController(context, prefs, this).reevaluate()
        Result.success(allowed)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    override suspend fun kioskOff(): Result<Unit> = try {
        prefs.setKiosk(false, null)  // also clears any pause deadline so the pause watchdog cannot fire a stale re-lock on the next KIOSK_ON
        LockController(context, prefs, this).reevaluate()
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    /**
     * Applies child mode's allowlist to the DPM and brings up the launcher in lock task. Only called
     * from inside a [LockController] decision ([restoreNormal]) that has already determined an
     * ordinary child-mode kiosk — not a complete lock — is the currently correct state; never from
     * [kioskOn] directly (see its comment).
     */
    private fun applyKiosk(allowed: List<String>, pinned: String?) {
        dpm.setLockTaskPackages(admin, (allowed + context.packageName + PLAY_PKG + GMS_PKG + listOfNotNull(emergencyDialerPackage())).toTypedArray())
        // NOTIFICATIONS requires HOME to also be enabled or AOSP throws IllegalArgumentException; SYSTEM_INFO alone is safe.
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
        launchHomeLocked()
        Log.d(LOG_TAG, "Kiosk on: ${allowed.size} apps, pinned=$pinned")
    }

    /** Releases the DPM allowlist and returns to a plain home. Same call-site restriction as [applyKiosk]. */
    private fun clearKiosk() {
        dpm.setLockTaskPackages(admin, emptyArray())   // removing the allowlist forces lock task to end
        // A launch failure here must not resurrect kiosk on the next reevaluate(); nothing else depends on it succeeding.
        runCatching { launchHomePlain() }.onFailure { Log.w(LOG_TAG, "Failed to launch ManagedHomeActivity after kiosk off", it) }
        Log.d(LOG_TAG, "Kiosk off")
    }

    /**
     * Applies a complete lock: nothing but this app is launchable, and the launcher is brought up in
     * lock task showing its bedtime state.
     *
     * `GLOBAL_ACTIONS` is requested explicitly — [kioskOn] passes `SYSTEM_INFO` alone, which disables
     * the power menu, and the power menu is the route to the system emergency dialer. A phone a child
     * carries must still be able to call for help at night.
     */
    /**
     * Package that handles emergency dialling on this device, or null if nothing does.
     *
     * Resolved at runtime rather than hardcoded: the component is OEM-specific — AOSP answers with
     * `com.android.phone`, this Samsung with `com.samsung.android.emergency` — and a wrong constant
     * would silently mean no emergency route at all.
     */
    fun emergencyDialerPackage(): String? = runCatching {
        context.packageManager
            .resolveActivity(Intent(EMERGENCY_DIAL_ACTION), PackageManager.ResolveInfoFlags.of(0))
            ?.activityInfo?.packageName
    }.getOrNull()

    /**
     * Opens the device's emergency dialer from the bedtime screen, keeping lock task on so the child
     * cannot wander off into the rest of the phone. Returns false when nothing could be launched.
     *
     * This only opens the dialer UI; placing a call is the caller's own deliberate act.
     */
    fun launchEmergencyDialer(): Boolean {
        val i = Intent(EMERGENCY_DIAL_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) == null) { Log.w(LOG_TAG, "No emergency dialer on this device"); return false }
        return runCatching {
            context.startActivity(i, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
        }.onFailure { Log.w(LOG_TAG, "Could not open the emergency dialer", it) }.isSuccess
    }

    override suspend fun lockComplete(): Result<Unit> = try {
        // Us, plus whatever handles emergency dialling on this device. LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        // is requested too, but it is not enough on its own: on a Samsung running Android 14 with the
        // flag set, long-pressing power under lock task produces no menu at all, so the power-menu
        // route to the emergency dialer simply does not exist. Allowlisting the dialer is what makes
        // the bedtime screen's Emergency button able to launch it.
        dpm.setLockTaskPackages(admin, (listOf(context.packageName) + listOfNotNull(emergencyDialerPackage())).toTypedArray())
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        launchHomeLocked()
        Log.d(LOG_TAG, "Complete lock applied")
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    /**
     * Returns the device to whatever was configured before the lock: child mode with its allowlist
     * when it is on, otherwise a free device. Idempotent — [LockController] calls it on every
     * re-evaluation that ends unlocked. Already runs *inside* a [LockController] decision, so it
     * applies directly via [applyKiosk]/[clearKiosk] rather than the public [kioskOn]/[kioskOff],
     * which would each start a new reevaluate() and recurse back into this function.
     */
    override suspend fun restoreNormal(): Result<Unit> = try {
        val c = prefs.kioskConfig.first()
        if (c.on && c.apps.isNotEmpty()) applyKiosk(c.apps, c.pinned) else clearKiosk()
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    /**
     * Launches [pkg] (only meaningful while child mode is on and [pkg] is allowed). Requests lock-task
     * only when [locked] — pass false during a PIN pause, or this would silently re-pin the device that
     * the pause is meant to free.
     */
    fun launchAllowed(pkg: String, locked: Boolean): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = if (locked) ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle() else null
        return runCatching { context.startActivity(i, opts) }.isSuccess
    }

    /**
     * Starts a parent-PIN pause: persists the deadline; the launcher Activity releases lock task itself.
     * Also runs [LockController.reevaluate], which — because the pause deadline is now in the future —
     * only arms an alarm for it and pushes status; it does not touch lock/kiosk state (see
     * [LockController]). That alarm is what re-locks the device if this process dies mid-pause:
     * [ManagedLinkService.startPauseWatchdog] cannot cover that case on its own because it only runs
     * while child mode is on, and a night lock can be active with child mode off. Returns pauseUntil,
     * or 0L (no-op) when neither child mode nor a complete lock is active — there is nothing to pause.
     */
    suspend fun pause(nowMs: Long = System.currentTimeMillis()): Long {
        val kioskOn = prefs.kioskConfig.first().on
        val lockOn = prefs.manualLock.first() || prefs.lockSchedule.first().isLockedAt(nowMs, ZoneId.systemDefault())
        if (!kioskOn && !lockOn) return 0L
        val until = nowMs + KioskConfig.PAUSE_MS
        prefs.setPauseUntil(until); Log.d(LOG_TAG, "Paused until $until")
        // The deadline is already persisted above, so a failure here must not propagate out of pause():
        // the pause is in effect either way, and throwing would hand the caller an exception where it
        // expects the pauseUntil it just set. Left for the next trigger (launcher countdown, the
        // service's pause watchdog, or another reevaluate()) to arm the alarm this one failed to.
        try {
            LockController(context, prefs, this).reevaluate()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Post-pause re-evaluation failed; re-lock alarm may be unset until the next trigger", t)
        }
        return until
    }

    /**
     * Opens the Play listing for [pkg]. Lock-task is requested **only while the device is actually
     * locked** (child mode on and not paused) — gating on raw `kioskOn` would also re-pin the device
     * during a PIN pause (kiosk stays "on" while paused), asking for lock-task outside an actual lock
     * launches Play pinned to the screen on a device the user is otherwise free to navigate, trapping
     * them in the Play listing with no way back.
     */
    override suspend fun openPlay(pkg: String): Result<Unit> {
        val locked = prefs.kioskConfig.first().isLocked(System.currentTimeMillis())
        return runCatching {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(PLAY_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(context.packageManager) == null) throw IllegalStateException("Play Store not available")
            if (locked) context.startActivity(i, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
            else context.startActivity(i)
            Log.d(LOG_TAG, "Opened Play for $pkg (locked=$locked)")
        }
    }

    /**
     * Refuses while child mode is on (the original guard: the activity is not on the allowlist) and
     * also while a complete lock is in force (manual or scheduled) — with kiosk off, the earlier guard
     * alone would report "account flow opened" while lock task silently drops the activity, since only
     * this app is launchable during a complete lock.
     */
    override suspend fun openAddAccount(): Result<Unit> {
        if (prefs.kioskOn.first()) return Result.failure(IllegalStateException("kiosk is on; turn it off first"))
        val locked = LockState.evaluate(prefs.lockSchedule.first(), prefs.manualLock.first(), prefs.kioskConfig.first().pauseUntil, System.currentTimeMillis(), ZoneId.systemDefault()).locked
        if (locked) return Result.failure(IllegalStateException("device is locked"))
        return runCatching {
            val i = Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            Log.d(LOG_TAG, "Opened add-account")
        }
    }

    override suspend fun applyPrefs(prefs: ChildPrefs): Result<Unit> = try {
        this.prefs.setChildPrefs(prefs)
        AppLocale.apply(context, prefs.language)
        Log.d(LOG_TAG, "Prefs applied (lang=${prefs.language}, theme=${prefs.theme}, pin=${prefs.pinHash != null})")
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    override suspend fun setSchedule(schedule: LockSchedule): Result<Unit> = try {
        prefs.setLockSchedule(schedule)
        Log.d(LOG_TAG, "Lock schedule set (enabled=${schedule.enabled})")
        LockController(context, prefs, this).reevaluate()
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    override suspend fun setManualLock(on: Boolean): Result<Unit> = try {
        prefs.setManualLock(on)
        Log.d(LOG_TAG, "Manual lock = $on")
        LockController(context, prefs, this).reevaluate()
        Result.success(Unit)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    /**
     * True when [pin] matches the pushed parent PIN; false when none is set.
     *
     * Never throws. A salt/hash pair persisted before [ChildPrefs.sanitized] started validating it
     * (odd length, non-hex) makes `Hex.decode` raise, and this runs straight off the child's PIN
     * dialog — a malformed stored pair must read as "wrong PIN", not take the launcher down. The
     * PBKDF2 work (20k iterations) runs on [Dispatchers.Default] so the caller's main-thread
     * coroutine never blocks on it. Cancellation is rethrown, as everywhere else in this class.
     * The clear PIN is never logged.
     */
    suspend fun verifyPin(pin: String): Boolean = runCatching {
        val p = prefs.childPrefs.first()
        val salt = p.pinSalt ?: return@runCatching false
        val hash = p.pinHash ?: return@runCatching false
        withContext(Dispatchers.Default) { PinHash.verify(pin, salt, hash) }
    }.getOrElse { t ->
        if (t is CancellationException) throw t
        Log.w(LOG_TAG, "PIN verification failed (malformed stored salt/hash?): ${t::class.simpleName}")
        false
    }
}
