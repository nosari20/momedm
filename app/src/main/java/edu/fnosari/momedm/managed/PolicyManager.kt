package edu.fnosari.momedm.managed

import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import android.util.Log
import edu.fnosari.momedm.activities.managed.ManagedHomeActivity
import edu.fnosari.momedm.persistence.KioskConfig
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.protocol.PinHash
import edu.fnosari.momedm.ui.AppLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Thin, logged wrapper around [DevicePolicyManager] for the managed role. */
class PolicyManager(private val context: Context, private val prefs: ManagedPrefs) : PolicyActions {
    companion object {
        private const val LOG_TAG = "PolicyManager"
        const val PLAY_PKG = "com.android.vending"
        const val GMS_PKG = "com.google.android.gms"
        /** Intent extra: the launcher should immediately start the pinned app (if any). */
        const val EXTRA_LAUNCH_PINNED = "launch_pinned"
    }
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    /** The [AdminReceiver] component this app is (or would be) registered as device owner with. */
    val admin = ComponentName(context, AdminReceiver::class.java)
    /** True once this app has been set as device owner (via `dpm set-device-owner` during provisioning). */
    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /** Makes [ManagedHomeActivity] the persistent HOME so the device boots into us. */
    fun setAsDefaultHome() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
        dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, ManagedHomeActivity::class.java))
        Log.d(LOG_TAG, "Persistent HOME set")
    }

    /** Launches our launcher in lock task (the config decides what it shows). */
    private fun launchHomeLocked() {
        val home = Intent(context, ManagedHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_LAUNCH_PINNED, true)
        context.startActivity(home, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
    }

    // Both kiosk transitions suspend (they write to DataStore), so a plain runCatching would swallow the
    // CancellationException thrown when the caller's scope is cancelled and report it as an ordinary
    // command failure, leaving the coroutine machinery to believe the work completed. Cancellation is
    // rethrown; everything else becomes a Result.failure the controller can surface.
    override suspend fun kioskOn(apps: List<String>, pinned: String?): Result<List<String>> = try {
        val pm = context.packageManager
        val allowed = apps.distinct().filter { pm.getLaunchIntentForPackage(it) != null }
        if (allowed.isEmpty()) throw IllegalArgumentException("no allowed app installed")
        val pin = pinned?.takeIf { it in allowed }
        if (pinned != null && pin == null) Log.w(LOG_TAG, "Requested pinned app $pinned dropped: not installed or not allowed")
        dpm.setLockTaskPackages(admin, (allowed + context.packageName + PLAY_PKG + GMS_PKG).toTypedArray())
        // NOTIFICATIONS requires HOME to also be enabled or AOSP throws IllegalArgumentException; SYSTEM_INFO alone is safe.
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
        prefs.setKioskConfig(KioskConfig(on = true, apps = allowed, pinned = pin, pauseUntil = 0L))
        launchHomeLocked()
        Log.d(LOG_TAG, "Kiosk on: ${allowed.size} apps, pinned=$pin")
        Result.success(allowed)
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { Result.failure(t) }

    override suspend fun kioskOff(): Result<Unit> = try {
        dpm.setLockTaskPackages(admin, emptyArray())   // removing the allowlist forces lock task to end
        prefs.setKiosk(false, null)
        // State is already cleared above; a launch failure here must not resurrect kiosk on the next restoreKiosk().
        runCatching {
            val home = Intent(context, ManagedHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(home)
        }.onFailure { Log.w(LOG_TAG, "Failed to launch ManagedHomeActivity after kiosk off", it) }
        Log.d(LOG_TAG, "Kiosk off")
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
     * Returns pauseUntil, or 0L (no-op) when child mode is off — there is nothing to pause.
     */
    suspend fun pause(nowMs: Long = System.currentTimeMillis()): Long {
        if (!prefs.kioskConfig.first().on) return 0L
        val until = nowMs + KioskConfig.PAUSE_MS
        prefs.setPauseUntil(until); Log.d(LOG_TAG, "Kiosk paused until $until")
        ManagedLinkState.statusPushRequests.tryEmit(Unit)
        return until
    }

    /**
     * Ends a pause (or is a no-op when child mode is off): re-applies the stored config and re-locks.
     * On failure the pause deadline is left untouched (retry stays possible); on success [kioskOn]
     * itself persists `pauseUntil = 0`, so this never clears the deadline ahead of a confirmed re-lock.
     */
    suspend fun resume(): Result<List<String>> {
        val c = prefs.kioskConfig.first()
        if (!c.on) return Result.success(emptyList())
        return kioskOn(c.apps, c.pinned)
            .onSuccess { ManagedLinkState.statusPushRequests.tryEmit(Unit) }
            .onFailure { Log.w(LOG_TAG, "Kiosk resume failed; pause deadline left as is", it) }
    }

    /** Re-enters child mode after reboot when it was on; a pause never survives a reboot. */
    suspend fun restoreKiosk() {
        val c = prefs.kioskConfig.first()
        if (c.on && c.apps.isNotEmpty()) kioskOn(c.apps, c.pinned).onFailure { Log.w(LOG_TAG, "Kiosk restore failed", it) }
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

    override suspend fun openAddAccount(): Result<Unit> {
        if (prefs.kioskOn.first()) return Result.failure(IllegalStateException("kiosk is on; turn it off first"))
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

    /** True when [pin] matches the pushed parent PIN; false when none is set. */
    suspend fun verifyPin(pin: String): Boolean {
        val p = prefs.childPrefs.first()
        val salt = p.pinSalt ?: return false; val hash = p.pinHash ?: return false
        return PinHash.verify(pin, salt, hash)
    }
}
