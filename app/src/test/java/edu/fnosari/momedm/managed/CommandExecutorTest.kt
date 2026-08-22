package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.protocol.CmdType
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
        override suspend fun openAddAccount() = if (kiosk != null) Result.failure(IllegalStateException("kiosk is on; turn it off first")) else run { accountOpened = true; Result.success(Unit) }
        override suspend fun applyPrefs(prefs: ChildPrefs) = run { this.prefs = prefs; Result.success(Unit) }
    }
    private class FakeStatus : StatusSource {
        override suspend fun collect() = Message.Status(false, null, true, 42, "x")
        override suspend fun launchableApps() = listOf(AppInfo("a", "A"))
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
}
