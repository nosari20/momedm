package edu.fnosari.momedm.managed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-evaluates the lock when the clock or the timezone moves. Without this, a child who changes the
 * device clock would shift the bedtime window, and every armed alarm would be pointing at the wrong
 * instant.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    companion object { private const val LOG_TAG = "TimeChangeReceiver" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        Log.d(LOG_TAG, "Clock changed (${intent.action}); re-evaluating lock")
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try { LockController.of(app).reevaluate() } finally { pending.finish() }
        }
    }
}
