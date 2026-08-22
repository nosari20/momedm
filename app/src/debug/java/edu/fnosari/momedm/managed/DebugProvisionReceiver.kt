package edu.fnosari.momedm.managed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import edu.fnosari.momedm.protocol.ProvisioningExtras

/**
 * DEBUG BUILDS ONLY (declared in `src/debug/AndroidManifest.xml`).
 *
 * Lets a test harness provision the managed role without the Setup Wizard QR flow,
 * e.g. on an emulator made device owner via `adb shell dpm set-device-owner`:
 *
 * ```
 * adb shell am broadcast -a edu.fnosari.momedm.DEBUG_PROVISION \
 *   --es controller_id <uuid> --es secret <base64> -n edu.fnosari.momedm/.managed.DebugProvisionReceiver
 * ```
 *
 * Persists the extras exactly like the real provisioning path and starts [ManagedLinkService].
 */
class DebugProvisionReceiver : BroadcastReceiver() {
    companion object {
        private const val LOG_TAG = "DebugProvisionReceiver"
        const val ACTION = "edu.fnosari.momedm.DEBUG_PROVISION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val bundle = PersistableBundle().apply {
            putString(ProvisioningExtras.KEY_CONTROLLER_ID, intent.getStringExtra(ProvisioningExtras.KEY_CONTROLLER_ID))
            putString(ProvisioningExtras.KEY_SECRET, intent.getStringExtra(ProvisioningExtras.KEY_SECRET))
        }
        val ok = ManagedSetup.persistExtras(context, bundle)
        Log.w(LOG_TAG, "Debug provisioning applied=$ok; starting link service")
        if (ok) ManagedLinkService.start(context)
    }
}
