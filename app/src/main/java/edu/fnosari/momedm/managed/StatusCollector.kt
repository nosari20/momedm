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
import edu.fnosari.momedm.protocol.LockState
import edu.fnosari.momedm.protocol.Message
import edu.fnosari.momedm.protocol.SchemaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Gathers the fields of [Message.Status] and the launchable app list. */
class StatusCollector(private val context: Context, private val prefs: ManagedPrefs) : StatusSource {
    companion object {
        private const val LOG_TAG = "StatusCollector"
        private const val FG_WINDOW_MS = 60_000L
        /**
         * Upper bound on the packages reported by [launchableApps]. A device with a very long launcher
         * list would otherwise produce an APPS message that `Framer.split` refuses (> 9999 frames at the
         * 23-byte fallback MTU) or that takes minutes to push over BLE. 300 apps keeps the encoded
         * message well inside the frame budget and still covers any realistic managed device.
         */
        const val MAX_APPS = 300
    }

    /**
     * Collects the current [Message.Status]. Runs on [Dispatchers.IO]: [AccountManager],
     * [UsageStatsManager] and [BatteryManager] all make blocking binder calls that must not run on the
     * main thread (the caller is a main-dispatcher service coroutine).
     */
    override suspend fun collect(): Message.Status = withContext(Dispatchers.IO) {
        val c = prefs.kioskConfig.first(); val now = System.currentTimeMillis()
        val paused = c.isPaused(now)
        val schedule = prefs.lockSchedule.first()
        val safetyConfig = prefs.safety.first()
        val lock = LockState.evaluate(schedule, prefs.manualLock.first(), c.pauseUntil, now, ZoneId.systemDefault())
        val s = Message.Status(kiosk = c.on, kioskPkg = c.pinned, account = hasGoogleAccount(), battery = batteryPercent(),
            currentApp = foregroundApp() ?: if (c.isLocked(now)) c.pinned else null,
            kioskApps = if (c.on) c.apps else emptyList(), kioskPaused = paused, pauseEndsAt = if (paused) c.pauseUntil else null,
            locked = lock.locked, lockReason = lock.reason, lockUntil = lock.until, schedule = schedule,
            // Reported, not just read: the parent keeps no copy of its own. Without these the level
            // always displayed as Off however strict the child actually was, the per-app form opened
            // blank every time, and — worst — saving one app's settings merged into a null config and
            // wiped every other app's.
            safetyLevel = safetyConfig.level, safety = safetyConfig)
        Log.d(LOG_TAG, "Status: kiosk=${s.kiosk} apps=${s.kioskApps.size} paused=${s.kioskPaused} battery=${s.battery} locked=${s.locked} safety=${s.safetyLevel}"); s
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

    /**
     * All launcher-visible apps on the device, deduped by package, sorted by label and capped at
     * [MAX_APPS] so the resulting APPS message stays well under the framing limit.
     *
     * Runs on [Dispatchers.IO]: `queryIntentActivities` plus one `loadLabel` per resolved activity is a
     * long series of blocking [PackageManager] binder calls and must not run on the main thread.
     */
    /** Reads the app's declared managed-configuration schema; see [AppSchemaReader]. */
    override suspend fun appSchema(pkg: String): List<SchemaEntry> = withContext(Dispatchers.IO) {
        AppSchemaReader.read(context, pkg)
    }

    override suspend fun launchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }.take(MAX_APPS)
    }
}
