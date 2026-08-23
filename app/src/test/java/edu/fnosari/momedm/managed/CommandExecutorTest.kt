package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.LockSchedule
import edu.fnosari.momedm.protocol.SafetyConfig
import edu.fnosari.momedm.protocol.SafetyLevel
import edu.fnosari.momedm.protocol.EntryType
import edu.fnosari.momedm.protocol.SchemaEntry
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExecutorTest {
    private class FakePolicy : PolicyActions {
        var kiosk: List<String>? = null; var pinned: String? = null; var played: String? = null; var accountOpened = false; var prefs: ChildPrefs? = null
        override suspend fun kioskOn(apps: List<String>, pinned: String?): Result<List<String>> {
            val ok = apps.filter { it != "bad" }
            if (ok.isEmpty()) return Result.failure(IllegalArgumentException("no allowed app installed"))
            kiosk = ok; this.pinned = pinned; return Result.success(ok)
        }
        override suspend fun kioskOff() = run { kiosk = null; pinned = null; Result.success(Unit) }
        override suspend fun openPlay(pkg: String) = run { played = pkg; Result.success(Unit) }
        var safety: SafetyConfig? = null
        override suspend fun setSafety(config: SafetyConfig) = run { safety = config; Result.success("safety ${config.level.name.lowercase()}") }
        var searched: String? = null
        override suspend fun openPlaySearch(term: String) = run { searched = term; Result.success(Unit) }
        override suspend fun openAddAccount() = if (kiosk != null) Result.failure(IllegalStateException("kiosk is on; turn it off first")) else run { accountOpened = true; Result.success(Unit) }
        override suspend fun applyPrefs(prefs: ChildPrefs) = run { this.prefs = prefs; Result.success(Unit) }
        var schedule: LockSchedule? = null; var manual: Boolean? = null
        override suspend fun setSchedule(schedule: LockSchedule) = run { this.schedule = schedule; Result.success(Unit) }
        override suspend fun setManualLock(on: Boolean) = run { manual = on; Result.success(Unit) }
    }
    private class FakeStatus : StatusSource {
        override suspend fun collect() = Message.Status(false, null, true, 42, "x")
        override suspend fun launchableApps() = listOf(AppInfo("a", "A"))
        var schemaFor: String? = null
        var schemaToReturn: List<SchemaEntry> = emptyList()
        override suspend fun appSchema(pkg: String): List<SchemaEntry> { schemaFor = pkg; return schemaToReturn }
    }

    @Test fun kioskOnReturnsResultThenStatus() = runTest {
        val p = FakePolicy(); val out = CommandExecutor(p, FakeStatus()).execute(Message.Cmd("1", CmdType.KIOSK_ON, apps = listOf("com.k", "com.j"), pinned = "com.j"))
        assertEquals(Message.Result("1", true, "kiosk on (2 apps, pinned com.j)"), out[0]); assertTrue(out[1] is Message.Status)
        assertEquals(listOf("com.k", "com.j"), p.kiosk); assertEquals("com.j", p.pinned)
    }
    @Test fun kioskOnDropsPinnedNotInApps() = runTest {
        val p = FakePolicy(); val out = CommandExecutor(p, FakeStatus()).execute(Message.Cmd("1b", CmdType.KIOSK_ON, apps = listOf("com.k"), pinned = "com.zzz"))
        assertEquals(Message.Result("1b", true, "kiosk on (1 apps, pinned ignored: not in apps)"), out[0]); assertEquals(null, p.pinned)
    }
    @Test fun kioskOnFailure() = runTest {
        val out = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("2", CmdType.KIOSK_ON, apps = listOf("bad")))
        val result = out[0] as Message.Result
        assertFalse(result.ok); assertEquals(1, out.size); assertEquals("no allowed app installed", result.msg)
    }
    @Test fun kioskOnWithoutAppsFails() = runTest {
        val out = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("3", CmdType.KIOSK_ON))
        val r = out[0] as Message.Result; assertFalse(r.ok); assertEquals("no apps", r.msg)
    }
    @Test fun searchAppOpensPlaySearch() = runTest {
        val p = FakePolicy()
        val out = CommandExecutor(p, FakeStatus()).execute(Message.Cmd("30", CmdType.SEARCH_APP, pkg = "  Firefox  "))
        assertEquals(Message.Result("30", true, "play search opened"), out[0])
        assertEquals("Firefox", p.searched)   // trimmed before it reaches the policy
        assertEquals(null, p.played)          // and never treated as a package id
    }

    @Test fun searchAppWithoutATermFails() = runTest {
        val p = FakePolicy()
        for (cmd in listOf(Message.Cmd("31", CmdType.SEARCH_APP), Message.Cmd("32", CmdType.SEARCH_APP, pkg = "   "))) {
            val r = CommandExecutor(p, FakeStatus()).execute(cmd)[0] as Message.Result
            assertFalse(r.ok); assertEquals("missing search term", r.msg)
        }
        assertEquals(null, p.searched)
    }

    @Test fun setSafetyAppliesThePresetAndReportsIt() = runTest {
        val p = FakePolicy()
        val cfg = SafetyConfig.of(SafetyLevel.STRICT, SafetyConfig.DNS_CLEANBROWSING)
        val out = CommandExecutor(p, FakeStatus()).execute(Message.Cmd("40", CmdType.SET_SAFETY, safety = cfg))
        assertEquals(Message.Result("40", true, "safety strict"), out[0]); assertTrue(out[1] is Message.Status)
        assertEquals(SafetyLevel.STRICT, p.safety?.level)
        assertEquals(SafetyConfig.DNS_CLEANBROWSING, p.safety?.dnsHost)
    }

    @Test fun setSafetyDropsAMalformedDnsHost() = runTest {
        val p = FakePolicy()
        // Straight off the wire: a host the platform must never be handed.
        val cfg = SafetyConfig(SafetyLevel.MODERATE, "not a hostname", SafetyConfig.presetFor(SafetyLevel.MODERATE))
        CommandExecutor(p, FakeStatus()).execute(Message.Cmd("41", CmdType.SET_SAFETY, safety = cfg))
        assertEquals(null, p.safety?.dnsHost)
        assertEquals(SafetyLevel.MODERATE, p.safety?.level)   // the rest of the config still applies
    }

    @Test fun setSafetyWithoutPayloadFails() = runTest {
        val r = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("42", CmdType.SET_SAFETY))[0] as Message.Result
        assertFalse(r.ok); assertEquals("missing safety", r.msg)
    }

    @Test fun appSchemaIsReturnedForTheRequestedPackage() = runTest {
        val st = FakeStatus()
        st.schemaToReturn = listOf(
            SchemaEntry("BrowserSignin", EntryType.INTEGER, "Sign-in"),
            SchemaEntry("URLBlocklist", EntryType.MULTI_SELECT, "Blocked sites", listOf("A"), listOf("a")),
        )
        val out = CommandExecutor(FakePolicy(), st).execute(Message.Cmd("50", CmdType.GET_APP_SCHEMA, pkg = "com.android.chrome"))
        assertEquals(Message.Result("50", true, "2 setting(s)"), out[0])
        assertEquals(Message.Schema("com.android.chrome", st.schemaToReturn), out[1])
        assertEquals("com.android.chrome", st.schemaFor)
    }

    @Test fun anAppWithNoSchemaIsAnEmptyListNotAnError() = runTest {
        // "declares nothing" and "could not be read" must not look the same to the parent.
        val out = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("51", CmdType.GET_APP_SCHEMA, pkg = "com.duolingo"))
        val r = out[0] as Message.Result
        assertTrue(r.ok); assertEquals("0 setting(s)", r.msg)
        assertEquals(Message.Schema("com.duolingo", emptyList()), out[1])
    }

    @Test fun appSchemaWithoutAPackageFails() = runTest {
        val r = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("52", CmdType.GET_APP_SCHEMA))[0] as Message.Result
        assertFalse(r.ok); assertEquals("missing pkg", r.msg)
    }

    @Test fun setPrefs() = runTest {
        val p = FakePolicy(); val ex = CommandExecutor(p, FakeStatus())
        val out = ex.execute(Message.Cmd("11", CmdType.SET_PREFS, prefs = ChildPrefs(language = "fr", theme = "weird")))
        assertEquals(Message.Result("11", true, "prefs applied"), out[0]); assertEquals("fr", p.prefs?.language); assertEquals("system", p.prefs?.theme)
        assertFalse((ex.execute(Message.Cmd("12", CmdType.SET_PREFS))[0] as Message.Result).ok)
    }
    @Test fun kioskOffReturnsResultThenStatus() = runTest {
        val p = FakePolicy(); p.kiosk = listOf("com.k")
        val out = CommandExecutor(p, FakeStatus()).execute(Message.Cmd("8", CmdType.KIOSK_OFF))
        assertEquals(Message.Result("8", true, "kiosk off"), out[0]); assertTrue(out[1] is Message.Status); assertEquals(null, p.kiosk)
    }
    @Test fun listAppsAndStatus() = runTest {
        val ex = CommandExecutor(FakePolicy(), FakeStatus())
        val apps = ex.execute(Message.Cmd("4", CmdType.LIST_APPS)); assertEquals(Message.Apps(listOf(AppInfo("a", "A"))), apps[1])
        val st = ex.execute(Message.Cmd("5", CmdType.GET_STATUS)); assertEquals(42, (st[1] as Message.Status).battery)
    }
    @Test fun installAndAccount() = runTest {
        val p = FakePolicy(); val ex = CommandExecutor(p, FakeStatus())
        assertTrue((ex.execute(Message.Cmd("6", CmdType.INSTALL, "com.p"))[0] as Message.Result).ok); assertEquals("com.p", p.played)
        assertTrue((ex.execute(Message.Cmd("7", CmdType.ADD_ACCOUNT))[0] as Message.Result).ok); assertTrue(p.accountOpened)
    }
    @Test fun addAccountRefusedWhileKioskOn() = runTest {
        val p = FakePolicy(); val ex = CommandExecutor(p, FakeStatus())
        ex.execute(Message.Cmd("9", CmdType.KIOSK_ON, apps = listOf("com.k")))
        val out = ex.execute(Message.Cmd("10", CmdType.ADD_ACCOUNT))
        val result = out[0] as Message.Result
        assertFalse(result.ok); assertEquals("kiosk is on; turn it off first", result.msg); assertFalse(p.accountOpened)
    }
    @Test fun setScheduleSanitizesAndReturnsStatus() = runTest {
        val p = FakePolicy()
        val out = CommandExecutor(p, FakeStatus()).execute(
            Message.Cmd("20", CmdType.SET_SCHEDULE, schedule = LockSchedule(enabled = true, weekdayStart = 9999)))
        assertEquals(Message.Result("20", true, "schedule set"), out[0]); assertTrue(out[1] is Message.Status)
        assertEquals(21 * 60, p.schedule?.weekdayStart)   // clamped by sanitized()
        assertEquals(true, p.schedule?.enabled)
    }

    @Test fun setScheduleWithoutPayloadFails() = runTest {
        val out = CommandExecutor(FakePolicy(), FakeStatus()).execute(Message.Cmd("21", CmdType.SET_SCHEDULE))
        val r = out[0] as Message.Result; assertFalse(r.ok); assertEquals("missing schedule", r.msg)
    }

    @Test fun lockNowAndUnlockSetTheFlag() = runTest {
        val p = FakePolicy(); val ex = CommandExecutor(p, FakeStatus())
        val locked = ex.execute(Message.Cmd("22", CmdType.LOCK_NOW))
        assertEquals(Message.Result("22", true, "locked"), locked[0]); assertTrue(locked[1] is Message.Status)
        assertEquals(true, p.manual)
        val unlocked = ex.execute(Message.Cmd("23", CmdType.UNLOCK))
        assertEquals(Message.Result("23", true, "unlocked"), unlocked[0]); assertEquals(false, p.manual)
    }
}
