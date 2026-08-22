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
import edu.fnosari.momedm.persistence.ManagedPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Thin, logged wrapper around [DevicePolicyManager] for the managed role. */
class PolicyManager(private val context: Context, private val prefs: ManagedPrefs) : PolicyActions {
    companion object {
        private const val LOG_TAG = "PolicyManager"
        const val PLAY_PKG = "com.android.vending"
        const val GMS_PKG = "com.google.android.gms"
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

    // Both kiosk transitions suspend (they write to DataStore), so a plain runCatching would swallow the
    // CancellationException thrown when the caller's scope is cancelled and report it as an ordinary
    // command failure, leaving the coroutine machinery to believe the work completed. Cancellation is
    // rethrown; everything else becomes a Result.failure the controller can surface.
    override suspend fun kioskOn(pkg: String): Result<Unit> = try {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalArgumentException("$pkg not installed or not launchable")
        dpm.setLockTaskPackages(admin, arrayOf(pkg, context.packageName, PLAY_PKG, GMS_PKG))
        // NOTIFICATIONS requires HOME to also be enabled or AOSP throws IllegalArgumentException; SYSTEM_INFO alone is safe.
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
        prefs.setKiosk(true, pkg)
        Log.d(LOG_TAG, "Kiosk on: $pkg")
        Result.success(Unit)
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

    /** Re-enters kiosk after reboot when it was on. */
    suspend fun restoreKiosk() {
        if (prefs.kioskOn.first()) { val pkg = prefs.kioskPkg.first(); if (pkg.isNotEmpty()) kioskOn(pkg).onFailure { Log.w(LOG_TAG, "Kiosk restore failed", it) } }
    }

    /**
     * Opens the Play listing for [pkg]. Lock-task is requested **only while kiosk is on** — asking for it
     * outside lock task launches Play pinned to the screen on a device the user is otherwise free to
     * navigate, trapping them in the Play listing with no way back.
     */
    override suspend fun openPlay(pkg: String): Result<Unit> {
        val kiosk = prefs.kioskOn.first()
        return runCatching {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(PLAY_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(context.packageManager) == null) throw IllegalStateException("Play Store not available")
            if (kiosk) context.startActivity(i, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
            else context.startActivity(i)
            Log.d(LOG_TAG, "Opened Play for $pkg (kiosk=$kiosk)")
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
}
