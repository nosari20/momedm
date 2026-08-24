package edu.fnosari.momedm.managed

import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import edu.fnosari.momedm.protocol.PairingPayload
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.persistence.preferences.DataStorePreferencesProvider
import edu.fnosari.momedm.protocol.ProvisioningExtras
import kotlinx.coroutines.runBlocking

/** One-shot helpers used by provisioning components (receiver + activities). */
object ManagedSetup {
    private const val LOG_TAG = "ManagedSetup"

    fun prefs(context: Context): ManagedPrefs = ManagedPrefs(DataStorePreferencesProvider(context))

    /** Stores controller id + secret from the QR admin-extras bundle. Returns true when both were present. */
    fun persistExtras(context: Context, bundle: PersistableBundle?): Boolean {
        val controllerId = bundle?.getString(ProvisioningExtras.KEY_CONTROLLER_ID)
        val secret = bundle?.getString(ProvisioningExtras.KEY_SECRET)
        if (controllerId.isNullOrEmpty() || secret.isNullOrEmpty()) { Log.w(LOG_TAG, "Admin extras missing controller_id/secret"); return false }
        // Shape-checked here, the same way PairingPayload checks a scanned code. Anything that is not
        // decodable base64 becomes an exception at every single service start rather than a failure
        // here — and the debug provisioning receiver writes whatever is on the adb command line.
        if (!PairingPayload.SECRET_RE.matches(secret)) { Log.w(LOG_TAG, "Admin extras carry a malformed secret"); return false }
        runBlocking { prefs(context).saveProvisioning(controllerId, secret) }
        Log.d(LOG_TAG, "Provisioning extras persisted for controller $controllerId")
        return true
    }
}
