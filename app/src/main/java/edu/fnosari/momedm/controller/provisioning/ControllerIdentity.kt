package edu.fnosari.momedm.controller.provisioning

import edu.fnosari.momedm.protocol.Base64Std
import edu.fnosari.momedm.protocol.Crypto
import java.util.UUID

/** Controller identity shared with every device it provisions (via QR admin extras). */
data class ControllerIdentity(val controllerId: String, val secretBase64: String) {
    val secretBytes: ByteArray get() = Base64Std.decode(secretBase64)
    companion object {
        fun generate(): ControllerIdentity = ControllerIdentity(UUID.randomUUID().toString(), Base64Std.encode(Crypto.randomBytes(32)))
    }
}
