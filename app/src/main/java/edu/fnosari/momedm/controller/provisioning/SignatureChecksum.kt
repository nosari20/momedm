package edu.fnosari.momedm.controller.provisioning

import android.content.Context
import android.content.pm.PackageManager
import edu.fnosari.momedm.protocol.Base64Url
import java.security.MessageDigest

/** base64url(SHA-256(signing certificate)) — the value ManagedProvisioning expects in the QR. */
object SignatureChecksum {
    fun compute(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signers = info.signingInfo?.apkContentsSigners ?: error("no signing info")
        val cert = signers.first().toByteArray()
        return Base64Url.encodeNoPad(MessageDigest.getInstance("SHA-256").digest(cert))
    }
}
