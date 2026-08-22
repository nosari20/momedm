package edu.fnosari.momedm.activities.managed.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import edu.fnosari.momedm.managed.ManagedSetup

/** Answers the Setup Wizard: we only support fully managed device. Also persists admin extras early. */
class GetProvisioningModeActivity : Activity() {
    companion object { private const val LOG_TAG = "GetProvisioningMode" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowed = intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES) ?: arrayListOf()
        Log.d(LOG_TAG, "Allowed modes: $allowed")
        val extras = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        ManagedSetup.persistExtras(this, extras)
        if (allowed.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)) {
            setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
        } else {
            Log.w(LOG_TAG, "Fully managed mode not offered; refusing (work profile unsupported)")
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
