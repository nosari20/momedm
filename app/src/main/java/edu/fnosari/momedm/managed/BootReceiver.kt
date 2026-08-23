package edu.fnosari.momedm.managed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** On boot of a managed device: restart the BLE link service (which also restores kiosk), and re-evaluate the lock. */
class BootReceiver : BroadcastReceiver() {
    companion object { private const val LOG_TAG = "BootReceiver" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PolicyManager(context, ManagedSetup.prefs(context)).isDeviceOwner) { Log.d(LOG_TAG, "Not device owner; ignoring boot"); return }
        Log.d(LOG_TAG, "Boot completed; starting link service")
        ManagedLinkService.start(context, fromBoot = true)

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // A parent-PIN pause deliberately does not survive a reboot (a manual lock, being a
                // persisted setting, legitimately does). This clears it explicitly rather than relying on
                // kioskOn()'s incidental reset of pauseUntil during ManagedLinkService's restoreKiosk() --
                // that path only runs when child mode is on, so with child mode off and a night lock
                // active nothing would otherwise clear a stale deadline, and reevaluate() could read it
                // before anyone clears it and arm an alarm for a pause that was supposed to be gone.
                ManagedSetup.prefs(app).setPauseUntil(0L)
                LockController.of(app).reevaluate()
            } finally { pending.finish() }
        }
    }
}
