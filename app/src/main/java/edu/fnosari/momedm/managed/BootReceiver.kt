package edu.fnosari.momedm.managed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** On boot of a managed device: restart the BLE link service (which also restores kiosk). */
class BootReceiver : BroadcastReceiver() {
    companion object { private const val LOG_TAG = "BootReceiver" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PolicyManager(context, ManagedSetup.prefs(context)).isDeviceOwner) { Log.d(LOG_TAG, "Not device owner; ignoring boot"); return }
        Log.d(LOG_TAG, "Boot completed; starting link service")
        ManagedLinkService.start(context, fromBoot = true)
    }
}
