package edu.fnosari.momedm.managed

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log

/** Device-owner admin component. Also catches provisioning extras on older flows. */
class AdminReceiver : DeviceAdminReceiver() {
    companion object { private const val LOG_TAG = "AdminReceiver" }

    override fun onEnabled(context: Context, intent: Intent) { Log.d(LOG_TAG, "Admin enabled") }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.d(LOG_TAG, "Provisioning complete")
        val bundle = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        ManagedSetup.persistExtras(context, bundle)
    }
}
