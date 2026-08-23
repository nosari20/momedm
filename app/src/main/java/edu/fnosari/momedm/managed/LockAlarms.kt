package edu.fnosari.momedm.managed

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Arms the single alarm that wakes the device to re-evaluate its lock state. */
object LockAlarms {
    private const val LOG_TAG = "LockAlarms"
    private const val REQUEST_CODE = 4711

    /** Schedules a wake-up at [atMs], or cancels the pending one when [atMs] is null. */
    fun armNext(context: Context, atMs: Long?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        if (atMs == null) { am.cancel(pi); Log.d(LOG_TAG, "No next transition; alarm cancelled"); return }
        // Exact where allowed. An inexact alarm only costs a few minutes of drift at the boundary,
        // never a wrong state, because resume/boot/time-change re-evaluation is the real net.
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        else { Log.w(LOG_TAG, "Exact alarms unavailable; using an inexact alarm"); am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi) }
        Log.d(LOG_TAG, "Next lock re-evaluation armed for $atMs")
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, LockAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Fired by [LockAlarms]; re-evaluates the lock and re-arms the next alarm. */
class LockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try { LockController.of(app).reevaluate() } finally { pending.finish() }
        }
    }
}
