package edu.fnosari.momedm.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** How strict the content settings pushed to a child device are. */
enum class SafetyLevel { OFF, MODERATE, STRICT }

/**
 * Content restrictions a parent pushes to a child device. Pure Kotlin.
 *
 * [appConfigs] is deliberately generic — package name to managed-configuration key/values, applied
 * verbatim — rather than a fixed set of Chrome fields. A preset is only a *generator* of that map, so
 * adding a key later, or configuring a different app entirely, needs no protocol change.
 */
@Serializable
data class SafetyConfig(
    val level: SafetyLevel = SafetyLevel.OFF,
    /** DNS-over-TLS hostname; null leaves private DNS alone. */
    val dnsHost: String? = null,
    val appConfigs: Map<String, JsonObject> = emptyMap(),
) {
    /** Drops a malformed [dnsHost] rather than handing it to the platform. */
    /**
     * This config with anything a peer could have malformed removed.
     *
     * The link is authenticated but the peer on the other end is still untrusted input, and this is
     * the one choke point every inbound SET_SAFETY passes through before [appConfigs] reaches
     * `setApplicationRestrictions`. Package names are checked, and both the number of apps and the
     * number of keys per app are bounded — an unbounded map here is written to disk by the platform
     * and re-applied on every boot, so a single hostile message would persist.
     */
    fun sanitized(): SafetyConfig = copy(
        dnsHost = dnsHost?.takeIf { isValidHostname(it) },
        appConfigs = appConfigs
            .filterKeys { PKG_RE.matches(it) }
            .filterValues { it.size <= MAX_KEYS_PER_APP }
            .entries.take(MAX_APPS).associate { it.toPair() },
    )

    /**
     * This config with [level]'s preset applied, keeping whatever the parent set by hand.
     *
     * Rebuilding from the preset alone — which is what [of] does — throws away every per-app setting
     * the parent entered, so merely switching level silently wiped their work. Only keys a preset owns
     * are replaced; a parent's own keys, and any app no preset covers, are carried across untouched.
     */
    fun withPreset(level: SafetyLevel, dnsHost: String?): SafetyConfig {
        val preset = presetFor(level)
        val merged = (appConfigs.keys + preset.keys).associateWith { pkg ->
            val own = appConfigs[pkg].orEmpty().filterKeys { it !in presetKeys[pkg].orEmpty() }
            JsonObject(own + preset[pkg].orEmpty())
        }.filterValues { it.isNotEmpty() }
        return copy(level = level, dnsHost = dnsHost, appConfigs = merged).sanitized()
    }

    companion object {
        const val CHROME_PKG = "com.android.chrome"

        /** A conservative Android package name. */
        private val PKG_RE = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$""")
        /** Generous next to a real preset (one app, six keys) and far below anything that could hurt. */
        private const val MAX_APPS = 32
        private const val MAX_KEYS_PER_APP = 128

        /**
         * The preset for [level], as managed configuration for the apps that support it.
         *
         * Only Chrome is covered, because it is the only common app that declares managed
         * configurations: the YouTube app declares none at all, so nothing an MDM sends can restrict
         * it — that is what [dnsHost] is for, since a family-filter resolver forces YouTube's own
         * Restricted Mode network-wide, inside the app included.
         *
         * Keys are Chrome enterprise policies, all present in the Chrome build shipping on Android:
         *  - `BrowserSignin = 0` — signing in is disabled, so the browser is not tied to an account;
         *  - `SafeBrowsingProtectionLevel = 2` — enhanced protection (1 = standard, 0 = off);
         *  - `IncognitoModeAvailability = 1` — incognito disabled, so history cannot be sidestepped;
         *  - `ForceGoogleSafeSearch = true`;
         *  - `SafeSitesFilterBehavior = 1` — filter adult content;
         *  - `ForceYouTubeRestrict` — 1 moderate, 2 strict (applies to YouTube *in Chrome*).
         */
        fun presetFor(level: SafetyLevel): Map<String, JsonObject> = when (level) {
            SafetyLevel.OFF -> emptyMap()
            SafetyLevel.MODERATE -> mapOf(CHROME_PKG to chrome(youTubeRestrict = 1))
            SafetyLevel.STRICT -> mapOf(CHROME_PKG to chrome(youTubeRestrict = 2))
        }

        private fun chrome(youTubeRestrict: Int): JsonObject = buildJsonObject {
            put("BrowserSignin", JsonPrimitive(0))
            put("SafeBrowsingProtectionLevel", JsonPrimitive(2))
            put("IncognitoModeAvailability", JsonPrimitive(1))
            put("ForceGoogleSafeSearch", JsonPrimitive(true))
            put("SafeSitesFilterBehavior", JsonPrimitive(1))
            put("ForceYouTubeRestrict", JsonPrimitive(youTubeRestrict))
        }

        /** A preset built from [level], keeping the parent's [dnsHost] choice. */
        fun of(level: SafetyLevel, dnsHost: String?): SafetyConfig =
            SafetyConfig(level, dnsHost, presetFor(level)).sanitized()

        /**
         * Every key any preset may set, per package.
         *
         * Swapping presets has to take the previous one's keys back out, or turning restrictions off
         * would leave the last preset's values in force — "off" that does not actually relax anything.
         */
        private val presetKeys: Map<String, Set<String>> =
            SafetyLevel.entries.flatMap { presetFor(it).entries }
                .groupBy({ it.key }, { it.value.keys })
                .mapValues { (_, keySets) -> keySets.flatten().toSet() }

        /**
         * Resolvers offered to the parent. Both force YouTube Restricted Mode and Google SafeSearch —
         * not every family resolver does, and one that does not would silently leave the YouTube app
         * unfiltered, which is most of the point.
         */
        const val DNS_CLEANBROWSING = "family-filter-dns.cleanbrowsing.org"
        const val DNS_ADGUARD = "family.adguard-dns.com"

        /** Conservative hostname check: labels of letters/digits/hyphens, at least one dot, ≤253 chars. */
        fun isValidHostname(h: String): Boolean {
            if (h.isBlank() || h.length > 253 || '.' !in h) return false
            return h.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.all { it.isLetterOrDigit() || it == '-' } &&
                    !label.startsWith('-') && !label.endsWith('-')
            }
        }
    }
}
