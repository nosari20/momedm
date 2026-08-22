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
import kotlinx.coroutines.flow.first

/** Thin, logged wrapper around [DevicePolicyManager] for the managed role. */
class PolicyManager(private val context: Context, private val prefs: ManagedPrefs) : PolicyActions {
    companion object {
        private const val LOG_TAG = "PolicyManager"
        const val PLAY_PKG = "com.android.vending"
        const val GMS_PKG = "com.google.android.gms"
    }
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, AdminReceiver::class.java)
    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /** Makes [ManagedHomeActivity] the persistent HOME so the device boots into us. */
    fun setAsDefaultHome() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
        dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, ManagedHomeActivity::class.java))
        Log.d(LOG_TAG, "Persistent HOME set")
    }

    override suspend fun kioskOn(pkg: String): Result<Unit> = runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalArgumentException("$pkg not installed or not launchable")
        dpm.setLockTaskPackages(admin, arrayOf(pkg, context.packageName, PLAY_PKG, GMS_PKG))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
        prefs.setKiosk(true, pkg)
        Log.d(LOG_TAG, "Kiosk on: $pkg")
    }

    override suspend fun kioskOff(): Result<Unit> = runCatching {
        dpm.setLockTaskPackages(admin, emptyArray())   // removing the allowlist forces lock task to end
        val home = Intent(context, ManagedHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(home)
        prefs.setKiosk(false, null)
        Log.d(LOG_TAG, "Kiosk off")
    }

    /** Re-enters kiosk after reboot when it was on. */
    suspend fun restoreKiosk() {
        if (prefs.kioskOn.first()) { val pkg = prefs.kioskPkg.first(); if (pkg.isNotEmpty()) kioskOn(pkg).onFailure { Log.w(LOG_TAG, "Kiosk restore failed", it) } }
    }

    override fun openPlay(pkg: String): Result<Unit> = runCatching {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(PLAY_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) == null) throw IllegalStateException("Play Store not available")
        val opts = ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle()
        context.startActivity(i, opts)
        Log.d(LOG_TAG, "Opened Play for $pkg")
    }

    override fun openAddAccount(): Result<Unit> = runCatching {
        val i = Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        Log.d(LOG_TAG, "Opened add-account")
    }
}
