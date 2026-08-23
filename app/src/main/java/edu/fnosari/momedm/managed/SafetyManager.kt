package edu.fnosari.momedm.managed

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import edu.fnosari.momedm.protocol.SafetyConfig
import edu.fnosari.momedm.protocol.SafetyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Applies a [SafetyConfig] to the device: managed configuration for the apps that support it, and
 * the private-DNS resolver.
 *
 * The two halves are independent and report separately — one failing must not hide the other's
 * success, because they cover different ground: Chrome's own settings, and everything else on the
 * device via DNS.
 */
class SafetyManager(private val context: Context, private val dpm: DevicePolicyManager, private val admin: ComponentName) {
    companion object {
        private const val LOG_TAG = "SafetyManager"

        /**
         * Converts one app's managed configuration to a [Bundle], total and explicit: booleans, ints,
         * strings and string arrays are carried across, anything else is skipped with a warning rather
         * than crashing on a value a peer sent.
         */
        fun toBundle(config: JsonObject): Bundle {
            val b = Bundle()
            for ((key, value) in config) {
                when {
                    value is JsonPrimitive && value.booleanOrNull != null -> b.putBoolean(key, value.booleanOrNull!!)
                    value is JsonPrimitive && value.intOrNull != null -> b.putInt(key, value.intOrNull!!)
                    value is JsonPrimitive && value.isString -> b.putString(key, value.content)
                    value is JsonArray && value.all { it is JsonPrimitive && it.isString } ->
                        b.putStringArray(key, value.map { (it as JsonPrimitive).content }.toTypedArray())
                    else -> Log.w(LOG_TAG, "Skipping unsupported value for $key")
                }
            }
            return b
        }
    }

    /** Outcome of one apply, so the parent is told what actually happened rather than a bare "ok". */
    data class Outcome(val appsApplied: Int, val appsSkipped: List<String>, val dns: String)

    /**
     * Applies [config]. Safe to call repeatedly — on boot and on every push — since both halves are
     * idempotent.
     */
    suspend fun apply(config: SafetyConfig): Outcome {
        var applied = 0
        val skipped = mutableListOf<String>()
        for ((pkg, values) in config.appConfigs) {
            if (!isInstalled(pkg)) {
                // Keep the config stored anyway: the app may be installed later, and re-applying on
                // boot will pick it up then.
                skipped += pkg
                continue
            }
            val ok = runCatching { dpm.setApplicationRestrictions(admin, pkg, toBundle(values)) }
                .onFailure { Log.w(LOG_TAG, "Could not configure $pkg: ${it::class.simpleName}") }.isSuccess
            if (ok) applied++ else skipped += pkg
        }
        val dns = applyDns(config)
        Log.d(LOG_TAG, "Safety applied: level=${config.level} apps=$applied skipped=${skipped.size} dns=$dns")
        return Outcome(applied, skipped, dns)
    }

    /**
     * Points private DNS at the parent's chosen resolver, or releases it when the level is OFF.
     *
     * Runs off the main thread: the platform validates the resolver over the network before accepting
     * it. A null [SafetyConfig.dnsHost] with a non-OFF level means "leave DNS alone" — the parent
     * chose not to manage it.
     */
    private suspend fun applyDns(config: SafetyConfig): String = withContext(Dispatchers.IO) {
        runCatching {
            when {
                config.level == SafetyLevel.OFF -> {
                    dpm.setGlobalPrivateDnsModeOpportunistic(admin); "released"
                }
                config.dnsHost == null -> "unchanged"
                else -> when (dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, config.dnsHost)) {
                    DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> "set to ${config.dnsHost}"
                    DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING -> "that DNS server did not answer"
                    else -> "could not be set"
                }
            }
        }.getOrElse { Log.w(LOG_TAG, "Private DNS change failed: ${it::class.simpleName}"); "could not be set" }
    }

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}
