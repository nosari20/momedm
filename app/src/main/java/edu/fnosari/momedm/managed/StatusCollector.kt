package edu.fnosari.momedm.managed

import android.accounts.AccountManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Process
import android.util.Log
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.flow.first

/** Gathers the fields of [Message.Status] and the launchable app list. */
class StatusCollector(private val context: Context, private val prefs: ManagedPrefs) : StatusSource {
    companion object { private const val LOG_TAG = "StatusCollector"; private const val FG_WINDOW_MS = 60_000L }

    override suspend fun collect(): Message.Status {
        val kioskOn = prefs.kioskOn.first(); val kioskPkg = prefs.kioskPkg.first().ifEmpty { null }
        val s = Message.Status(kiosk = kioskOn, kioskPkg = kioskPkg, account = hasGoogleAccount(), battery = batteryPercent(),
            currentApp = foregroundApp() ?: if (kioskOn) kioskPkg else null)
        Log.d(LOG_TAG, "Status: $s"); return s
    }

    /** Current battery charge as a 0-100 percentage. */
    fun batteryPercent(): Int = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /** True when a Google account is present, or false (without throwing) if account visibility is denied. */
    fun hasGoogleAccount(): Boolean = try { AccountManager.get(context).getAccountsByType("com.google").isNotEmpty() } catch (e: SecurityException) { Log.w(LOG_TAG, "No account visibility", e); false }

    /** True when this app currently holds the PACKAGE_USAGE_STATS app-op, needed by [foregroundApp]. */
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Last ACTIVITY_RESUMED package in the past minute, or null when usage access is missing. */
    fun foregroundApp(): String? {
        if (!hasUsageAccess()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis(); val events = usm.queryEvents(now - FG_WINDOW_MS, now)
        var last: String? = null; val e = UsageEvents.Event()
        while (events.hasNextEvent()) { events.getNextEvent(e); if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName }
        return last
    }

    /** All launcher-visible apps on the device, deduped by package and sorted by label. */
    override fun launchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }
}
