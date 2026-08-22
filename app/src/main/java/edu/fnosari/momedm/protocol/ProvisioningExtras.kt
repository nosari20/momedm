package edu.fnosari.momedm.protocol

/** Keys inside `PROVISIONING_ADMIN_EXTRAS_BUNDLE` (QR) consumed by the managed device. */
object ProvisioningExtras {
    const val KEY_CONTROLLER_ID = "controller_id"
    /** Standard base64 of 32 random bytes. */
    const val KEY_SECRET = "secret"
}
