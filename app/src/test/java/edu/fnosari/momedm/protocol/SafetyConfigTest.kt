package edu.fnosari.momedm.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the content-restriction presets and their sanitizing. */
class SafetyConfigTest {
    private fun chrome(level: SafetyLevel) = SafetyConfig.presetFor(level)[SafetyConfig.CHROME_PKG]

    @Test fun offCarriesNoAppConfiguration() {
        assertTrue(SafetyConfig.presetFor(SafetyLevel.OFF).isEmpty())
    }

    @Test fun moderateAndStrictSetTheChromeKeysWeIntend() {
        for (level in listOf(SafetyLevel.MODERATE, SafetyLevel.STRICT)) {
            val c = chrome(level) ?: error("no Chrome config for $level")
            // Signing in disabled, so the browser is not tied to an account.
            assertEquals(0, (c["BrowserSignin"] as JsonPrimitive).intOrNull)
            // Enhanced Safe Browsing.
            assertEquals(2, (c["SafeBrowsingProtectionLevel"] as JsonPrimitive).intOrNull)
            // Incognito disabled, so history cannot be sidestepped.
            assertEquals(1, (c["IncognitoModeAvailability"] as JsonPrimitive).intOrNull)
            assertEquals(true, (c["ForceGoogleSafeSearch"] as JsonPrimitive).booleanOrNull)
            assertEquals(1, (c["SafeSitesFilterBehavior"] as JsonPrimitive).intOrNull)
        }
    }

    @Test fun strictRestrictsYouTubeHarderThanModerate() {
        assertEquals(1, (chrome(SafetyLevel.MODERATE)!!["ForceYouTubeRestrict"] as JsonPrimitive).intOrNull)
        assertEquals(2, (chrome(SafetyLevel.STRICT)!!["ForceYouTubeRestrict"] as JsonPrimitive).intOrNull)
    }

    @Test fun onlyChromeIsConfigured() {
        // The YouTube app declares no managed configuration at all, so sending it any would be a
        // silent no-op that made the parent think their child was covered. DNS is what reaches it.
        assertEquals(setOf(SafetyConfig.CHROME_PKG), SafetyConfig.presetFor(SafetyLevel.STRICT).keys)
    }

    @Test fun aMalformedDnsHostIsDroppedRatherThanSentToThePlatform() {
        // These arrive over the wire from an authenticated but untrusted peer.
        for (bad in listOf("not a hostname", "", "nodot", "-lead.example.com", "trail-.example.com", "a".repeat(300))) {
            assertNull("expected $bad to be rejected", SafetyConfig(dnsHost = bad).sanitized().dnsHost)
        }
    }

    @Test fun realResolverHostnamesSurviveSanitizing() {
        for (good in listOf(SafetyConfig.DNS_CLEANBROWSING, SafetyConfig.DNS_ADGUARD, "dns.example.co.uk")) {
            assertEquals(good, SafetyConfig(dnsHost = good).sanitized().dnsHost)
        }
    }

    @Test fun hostnameCheckAcceptsAndRejectsTheObviousCases() {
        assertTrue(SafetyConfig.isValidHostname("family.adguard-dns.com"))
        assertFalse(SafetyConfig.isValidHostname("has space.com"))
        assertFalse(SafetyConfig.isValidHostname("double..dot.com"))
    }

    @Test fun ofBuildsAPresetAndKeepsTheParentsResolver() {
        val c = SafetyConfig.of(SafetyLevel.MODERATE, SafetyConfig.DNS_ADGUARD)
        assertEquals(SafetyLevel.MODERATE, c.level)
        assertEquals(SafetyConfig.DNS_ADGUARD, c.dnsHost)
        assertEquals(SafetyConfig.presetFor(SafetyLevel.MODERATE), c.appConfigs)
    }

    @Test fun safetyConfigSurvivesTheWire() {
        val c = SafetyConfig.of(SafetyLevel.STRICT, SafetyConfig.DNS_CLEANBROWSING)
        val back = MessageCodec.decodeMessage(
            MessageCodec.encodeMessage(Message.Cmd("c", CmdType.SET_SAFETY, safety = c)),
        ) as Message.Cmd
        assertEquals(c, back.safety)
    }

    @Test fun aNestedSchemaSurvivesTheWire() {
        // The real shape of a list-of-groups setting, captured from an app that declares one: a bundle
        // array whose single template bundle carries the item's fields.
        val schema = listOf(
            SchemaEntry(
                key = "test_list", type = EntryType.BUNDLE_ARRAY, title = "Tests",
                nested = listOf(
                    SchemaEntry(
                        key = "test", type = EntryType.BUNDLE, title = "Test",
                        nested = listOf(
                            SchemaEntry("test_hostname", EntryType.STRING, "Hostname"),
                            SchemaEntry("test_port", EntryType.INTEGER, "Port"),
                            SchemaEntry("test_ssl", EntryType.BOOLEAN, "Use SSL"),
                        ),
                    ),
                ),
            ),
        )
        val back = MessageCodec.decodeMessage(
            MessageCodec.encodeMessage(Message.Schema("com.example.app", schema)),
        ) as Message.Schema
        assertEquals(schema, back.entries)
    }

    @Test fun aBundleArrayOffersTheItemsFieldsNotTheWrapper() {
        // The form has to show hostname/port/ssl when adding an entry, not one nameless "Test" group.
        val template = SchemaEntry(
            key = "test", type = EntryType.BUNDLE,
            nested = listOf(SchemaEntry("test_hostname", EntryType.STRING), SchemaEntry("test_port", EntryType.INTEGER)),
        )
        val list = SchemaEntry("test_list", EntryType.BUNDLE_ARRAY, nested = listOf(template))
        assertEquals(listOf("test_hostname", "test_port"), list.itemFields.map { it.key })
        // A plain bundle offers its own children.
        assertEquals(listOf("test_hostname", "test_port"), template.itemFields.map { it.key })
    }

    @Test fun changingLevelKeepsTheParentsOwnAppSettings() {
        // Changing the level used to rebuild from the preset alone, throwing away every per-app
        // setting the parent had entered.
        val mine = JsonObject(mapOf("test_hostname" to JsonPrimitive("test.local")))
        val before = SafetyConfig(SafetyLevel.OFF, null, mapOf("com.example.app" to mine))
        val after = before.withPreset(SafetyLevel.STRICT, SafetyConfig.DNS_ADGUARD)
        assertEquals(SafetyLevel.STRICT, after.level)
        assertEquals(SafetyConfig.DNS_ADGUARD, after.dnsHost)
        assertEquals(mine, after.appConfigs["com.example.app"])
        // ...and the new preset is applied on top.
        assertEquals(2, (after.appConfigs.getValue(SafetyConfig.CHROME_PKG)["ForceYouTubeRestrict"] as JsonPrimitive).intOrNull)
    }

    @Test fun turningRestrictionsOffTakesThePresetKeysBackOut() {
        // Otherwise "off" would leave the last preset's values in force and relax nothing.
        val strict = SafetyConfig().withPreset(SafetyLevel.STRICT, null)
        assertTrue(strict.appConfigs.containsKey(SafetyConfig.CHROME_PKG))
        assertTrue(strict.withPreset(SafetyLevel.OFF, null).appConfigs.isEmpty())
    }

    @Test fun aParentsOwnKeyOnAPresetAppSurvivesALevelChange() {
        val custom = JsonObject(mapOf("HomepageLocation" to JsonPrimitive("https://example.org"), "ForceYouTubeRestrict" to JsonPrimitive(0)))
        val after = SafetyConfig(SafetyLevel.OFF, null, mapOf(SafetyConfig.CHROME_PKG to custom))
            .withPreset(SafetyLevel.MODERATE, null)
        val chrome = after.appConfigs.getValue(SafetyConfig.CHROME_PKG)
        // Their own key is kept; the key the preset owns is the preset's.
        assertEquals("https://example.org", (chrome["HomepageLocation"] as JsonPrimitive).content)
        assertEquals(1, (chrome["ForceYouTubeRestrict"] as JsonPrimitive).intOrNull)
    }

    @Test fun statusCarriesTheWholeConfigBackToTheParent() {
        // The parent keeps no copy: it merges each edit into what the child reports. A status that
        // omitted this would make every save overwrite the other apps' settings with nothing.
        val nested = JsonObject(
            mapOf(
                "test_list" to JsonArray(
                    listOf(JsonObject(mapOf("test_hostname" to JsonPrimitive("example.org"), "test_port" to JsonPrimitive(8443)))),
                ),
            ),
        )
        val config = SafetyConfig(SafetyLevel.MODERATE, SafetyConfig.DNS_ADGUARD, mapOf("com.example.app" to nested))
        val back = MessageCodec.decodeMessage(
            MessageCodec.encodeMessage(
                Message.Status(
                    kiosk = true, kioskPkg = null, account = false, battery = 50, currentApp = null,
                    safetyLevel = config.level, safety = config,
                ),
            ),
        ) as Message.Status
        assertEquals(SafetyLevel.MODERATE, back.safetyLevel)
        assertEquals(config, back.safety)
        assertEquals(nested, back.safety?.appConfigs?.get("com.example.app"))
    }

    @Test fun statusDefaultsToOffWhenAPeerSendsNoLevel() {
        val json = """{"t":"STATUS","kiosk":false,"kioskPkg":null,"account":false,"battery":10,"currentApp":null}"""
        assertEquals(SafetyLevel.OFF, (MessageCodec.decodeMessage(json) as Message.Status).safetyLevel)
    }
}
