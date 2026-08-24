package edu.fnosari.momedm.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The controller identity a child needs in order to talk to a parent. */
data class PairingIdentity(val controllerId: String, val secretBase64: String)

/**
 * Reads the controller identity out of a scanned pairing QR.
 *
 * The same code the Setup Wizard consumes during enrolment is reused for re-pairing an
 * already-provisioned device: everything a child needs to reach a parent is already in there, so a
 * parent who reinstalled their app, rotated the secret, or moved to a new phone can re-pair without
 * factory-resetting the child. The rest of the payload (APK URL, Wi-Fi, checksum) only matters to
 * the Setup Wizard and is ignored here.
 *
 * Pure Kotlin so the parsing — the part that has to survive whatever a camera happens to decode — is
 * unit-testable without Android.
 */
object PairingPayload {
    private const val EXTRAS_KEY = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
    private val json = Json { ignoreUnknownKeys = true }

    /** 32 bytes, standard base64 with padding — what `Crypto.randomSecret` produces. */
    /** A 32-byte secret in standard base64. Public so provisioning can refuse a bad one at the door. */
    val SECRET_RE = Regex("^[A-Za-z0-9+/]{43}=$")

    /**
     * Returns the identity encoded in [scanned], or null when it is not one of our pairing codes.
     *
     * A camera decodes whatever is put in front of it — a Wi-Fi QR, a URL, a shop receipt — so
     * everything is validated rather than assumed: valid JSON, our extras bundle, a non-blank
     * controller id, and a secret of exactly the shape we generate. Anything else is rejected, so a
     * malformed code can never overwrite a working pairing with junk.
     */
    fun parse(scanned: String): PairingIdentity? = runCatching {
        val root = json.parseToJsonElement(scanned) as? JsonObject ?: return null
        val extras = root[EXTRAS_KEY]?.jsonObject ?: return null
        val id = extras[ProvisioningExtras.KEY_CONTROLLER_ID]?.jsonPrimitive?.content.orEmpty()
        val secret = extras[ProvisioningExtras.KEY_SECRET]?.jsonPrimitive?.content.orEmpty()
        if (id.isBlank() || !SECRET_RE.matches(secret)) return null
        PairingIdentity(id, secret)
    }.getOrNull()
}
