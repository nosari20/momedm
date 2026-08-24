package edu.fnosari.momedm.managed

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Device-owner admin component. Also catches provisioning extras on older flows. */
class AdminReceiver : DeviceAdminReceiver() {
    companion object { private const val LOG_TAG = "AdminReceiver" }

    override fun onEnabled(context: Context, intent: Intent) { Log.d(LOG_TAG, "Admin enabled") }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.d(LOG_TAG, "Provisioning complete")
        val bundle = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        // goAsync so the disk write happens off the main thread without the broadcast returning
        // first: a receiver runs on the main thread and under a timeout, which is the one place this
        // write could cost an ANR. finish() is called in every path, including failure.
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { ManagedSetup.persistExtrasAsync(context, bundle) }
            catch (t: Throwable) { Log.w(LOG_TAG, "Could not persist provisioning extras", t) }
            finally { result.finish() }
        }
    }
}
