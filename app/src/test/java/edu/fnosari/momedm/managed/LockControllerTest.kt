package edu.fnosari.momedm.managed

import edu.fnosari.momedm.persistence.InMemoryPreferencesProvider
import edu.fnosari.momedm.persistence.KioskConfig
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.LockSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * [LockController] against fakes of its two Android-bound collaborators ([LockActions],
 * [Alarms]) — the same seam [CommandExecutorTest] uses for [PolicyActions]. Pins the three pieces
 * that fixed the two live-found defects (the pause short-circuit, the stale-pause clear, and the
 * decide-then-apply flow), the ON_RESUME apply-dedup cache from the final review pass, and the two
 * regressions that cache's [LockState]-only key introduced (KIOSK_ON/KIOSK_OFF becoming DPM no-ops,
 * and a lapsed PIN pause failing to re-lock).
 */
class LockControllerTest {
    /**
     * [restoreNormal] takes no arguments — the real [PolicyManager.restoreNormal] instead reads
     * [ManagedPrefs.kioskConfig] itself to decide between the child-mode allowlist and a fully free
     * device. A fake that only counted calls could not tell two different applied [KioskConfig]s
     * apart, which is exactly the seam that hid regression 1. So this fake mirrors that real read:
     * every [restoreNormal] call records the [KioskConfig] it observed at that moment, from the same
     * [prefs] the controller under test uses.
     */
    private class FakeActions(private val prefs: ManagedPrefs) : LockActions {
        var lockCalls = 0
        var restoreCalls = 0
        var lockResult: Result<Unit> = Result.success(Unit)
        val restoredConfigs = mutableListOf<KioskConfig>()
        override suspend fun lockComplete(): Result<Unit> { lockCalls++; return lockResult }
        override suspend fun restoreNormal(): Result<Unit> {
            restoreCalls++
            restoredConfigs += prefs.kioskConfig.first()
            return Result.success(Unit)
        }
    }

    private class FakeAlarms : Alarms {
        var armCount = 0
        /** Sentinel distinct from any real epoch-ms value or null, so "never called" is observable. */
        var lastArmed: Long? = Long.MIN_VALUE
        override fun armNext(atMs: Long?) { armCount++; lastArmed = atMs }
    }

    private fun freshPrefs() = ManagedPrefs(InMemoryPreferencesProvider())

    // The apply-dedup cache (edu.fnosari.momedm.managed.LockController item 2) is a process-wide,
    // in-memory companion field by design (it must survive across the many short-lived LockController
    // instances each call site constructs) — so it has to be reset between test cases here, or one
    // test's "already applied" state would leak into the next and make it order-dependent.
    @Before fun resetApplyCache() { LockController.resetCacheForTest() }

    @Test fun pauseStillActive_noApply_alarmArmedAtPauseDeadline() = runTest {
        val prefs = freshPrefs()
        val deadline = System.currentTimeMillis() + 60_000L
        prefs.setKioskConfig(KioskConfig(on = true, apps = listOf("a"), pauseUntil = deadline))
        val actions = FakeActions(prefs); val alarms = FakeAlarms()

        LockController(prefs, actions, alarms).reevaluate()

        assertEquals(0, actions.lockCalls)
        assertEquals(0, actions.restoreCalls)
        assertEquals(1, alarms.armCount)
        assertEquals(deadline, alarms.lastArmed)
    }

    @Test fun pauseLapsed_deadlineZeroedExactlyOnce_thenNormalApplyHappens() = runTest {
        val prefs = freshPrefs()
        val lapsed = System.currentTimeMillis() - 5_000L
        prefs.setKioskConfig(KioskConfig(on = true, apps = listOf("a"), pauseUntil = lapsed))
        val actions = FakeActions(prefs); val alarms = FakeAlarms()

        LockController(prefs, actions, alarms).reevaluate()

        // The lapsed deadline was cleared (the fix for the busy-loop defect)...
        assertEquals(0L, prefs.kioskConfig.first().pauseUntil)
        // ...and, because manualLock/schedule are both off by default, that clears the way to a
        // normal "unlocked" apply in the same reevaluate() call — not a second, separate one.
        assertEquals(1, actions.restoreCalls)
        assertEquals(0, actions.lockCalls)
    }

    @Test fun locked_callsLockComplete_armsAlarmAtNextTransition() = runTest {
        val prefs = freshPrefs()
        prefs.setManualLock(true)
        val actions = FakeActions(prefs); val alarms = FakeAlarms()

        LockController(prefs, actions, alarms).reevaluate()

        assertEquals(1, actions.lockCalls)
        assertEquals(0, actions.restoreCalls)
        // The default LockSchedule is disabled, so nextTransition() is deterministically null
        // regardless of wall-clock time; armNext(null) cancels any pending alarm, which is still
        // "armed at nextTransition" for a schedule that has no next transition.
        assertEquals(1, alarms.armCount)
        assertNull(alarms.lastArmed)
    }

    @Test fun locked_withEnabledSchedule_armsAlarmAtComputedNextTransition() = runTest {
        val prefs = freshPrefs()
        prefs.setManualLock(true)
        val schedule = LockSchedule(enabled = true)
        prefs.setLockSchedule(schedule)
        val before = System.currentTimeMillis()
        val actions = FakeActions(prefs); val alarms = FakeAlarms()

        LockController(prefs, actions, alarms).reevaluate()

        // reevaluate() computes "now" internally, a few micros after `before` — nextTransition only
        // differs from this if a window boundary fell in that gap, which is not observable in a fast
        // unit test.
        assertEquals(schedule.nextTransition(before, ZoneId.systemDefault()), alarms.lastArmed)
    }

    @Test fun unlocked_callsRestoreNormal() = runTest {
        val prefs = freshPrefs()
        val actions = FakeActions(prefs); val alarms = FakeAlarms()

        LockController(prefs, actions, alarms).reevaluate()

        assertEquals(1, actions.restoreCalls)
        assertEquals(0, actions.lockCalls)
    }

    @Test fun sameStateAppliedTwiceInARow_secondApplySkipped_alarmStillReArmed() = runTest {
        val prefs = freshPrefs()
        val actions = FakeActions(prefs); val alarms = FakeAlarms()
        val controller = LockController(prefs, actions, alarms)

        controller.reevaluate()
        controller.reevaluate()

        // This is the ON_RESUME relaunch-loop guard (item 2): identical inputs across two calls must
        // not re-apply the (already correct) state a second time...
        assertEquals(1, actions.restoreCalls)
        // ...but every trigger still re-arms the alarm and pushes status, so the cache never turns
        // reevaluate() itself into a no-op.
        assertEquals(2, alarms.armCount)
    }

    /**
     * Regression 1, part A. Reproduces the exact live scenario from the report: the steady state on
     * an ordinary unlocked device is `lastApplied == <unlocked, kiosk off>`. The parent then picks
     * allowed apps (KIOSK_ON), which persists a new [KioskConfig] but keeps [LockState] unlocked. A
     * cache keyed on [LockState] alone treats that as "already applied" and skips the apply outright
     * — `setLockTaskPackages` never runs, child mode never engages, yet the caller sees success. This
     * pins that the second reevaluate() genuinely re-applies with the new config.
     *
     * Fails before the fix: `restoreCalls` stays 1 (the second apply is wrongly skipped).
     */
    @Test fun childModeTurnedOn_whileUnlocked_kioskApplyActuallyHappens() = runTest {
        val prefs = freshPrefs()
        val actions = FakeActions(prefs); val alarms = FakeAlarms()
        val controller = LockController(prefs, actions, alarms)

        controller.reevaluate() // steady state: unlocked, kiosk off

        val apps = listOf("com.example.a", "com.example.b")
        prefs.setKioskConfig(KioskConfig(on = true, apps = apps, pauseUntil = 0L)) // KIOSK_ON
        controller.reevaluate()

        assertEquals(2, actions.restoreCalls)
        assertEquals(0, actions.lockCalls)
        val lastSeen = actions.restoredConfigs.last()
        assertTrue(lastSeen.on)
        assertEquals(apps, lastSeen.apps)
    }

    /**
     * Regression 1, part B — the worse half. Child mode is already on (steady state
     * `lastApplied == <unlocked, kiosk on>`). The parent taps "Stop child mode" (KIOSK_OFF), which
     * persists `on = false` but keeps [LockState] unlocked. A cache keyed on [LockState] alone skips
     * the apply, so `clearKiosk()` never runs: the stale allowlist and lock task stay in force and
     * the child stays pinned even though the parent was told child mode stopped.
     *
     * Fails before the fix: `restoreCalls` stays 1 (the clearing apply is wrongly skipped).
     */
    @Test fun childModeTurnedOff_whileUnlocked_kioskClearActuallyHappens() = runTest {
        val prefs = freshPrefs()
        prefs.setKioskConfig(KioskConfig(on = true, apps = listOf("com.example.a"), pauseUntil = 0L))
        val actions = FakeActions(prefs); val alarms = FakeAlarms()
        val controller = LockController(prefs, actions, alarms)

        controller.reevaluate() // steady state: unlocked, kiosk on

        prefs.setKiosk(false, null) // KIOSK_OFF, as PolicyManager.kioskOff persists it
        controller.reevaluate()

        assertEquals(2, actions.restoreCalls)
        assertEquals(0, actions.lockCalls)
        assertFalse(actions.restoredConfigs.last().on)
    }

    /**
     * Insurance alongside [sameStateAppliedTwiceInARow_secondApplySkipped_alarmStillReArmed]: the
     * ON_RESUME dedup must still hold once the cache key also carries [KioskConfig], not just for the
     * default (kiosk-off) config. Two reevaluate() calls with an *unchanged* kiosk-on config — exactly
     * what the CLEAR_TASK relaunch inside [LockActions.restoreNormal] triggers via its own ON_RESUME —
     * must still dedup to a single apply.
     */
    @Test fun sameAppliedPolicyWithKioskOn_appliedTwiceInARow_secondApplySkipped() = runTest {
        val prefs = freshPrefs()
        prefs.setKioskConfig(KioskConfig(on = true, apps = listOf("com.example.a"), pauseUntil = 0L))
        val actions = FakeActions(prefs); val alarms = FakeAlarms()
        val controller = LockController(prefs, actions, alarms)

        controller.reevaluate() // KIOSK_ON while unlocked -> applies once
        controller.reevaluate() // simulates the CLEAR_TASK relaunch's own ON_RESUME -> must dedup

        assertEquals(1, actions.restoreCalls)
        assertEquals(2, alarms.armCount)
    }

    @Test fun aDifferentInputChangeIsNotSuppressedByTheCache() = runTest {
        val prefs = freshPrefs()
        val actions = FakeActions(prefs); val alarms = FakeAlarms()
        val controller = LockController(prefs, actions, alarms)

        controller.reevaluate() // unlocked -> restoreNormal()
        prefs.setManualLock(true)
        controller.reevaluate() // input changed -> must apply again, this time locked

        assertEquals(1, actions.restoreCalls)
        assertEquals(1, actions.lockCalls)
    }

    @Test fun aFailedApplyIsNotCached_retriesOnTheNextTrigger() = runTest {
        val prefs = freshPrefs()
        prefs.setManualLock(true)
        val actions = FakeActions(prefs).apply { lockResult = Result.failure(IllegalStateException("boom")) }
        val alarms = FakeAlarms()
        val controller = LockController(prefs, actions, alarms)

        controller.reevaluate()
        controller.reevaluate()

        // Neither attempt was cached as "done" because both failed — every trigger retries.
        assertEquals(2, actions.lockCalls)
    }

    /**
     * Regression 2. A complete lock is applied, then the parent PIN starts a pause (still in the
     * future) — the hosting Activity releases lock task itself, so reevaluate() must not re-apply
     * during the pause, but must clear `lastApplied` because the device is no longer in the policy it
     * records. When the pause deadline lapses, manualLock/schedule are unchanged, so
     * [edu.fnosari.momedm.protocol.LockState.evaluate] recomputes the IDENTICAL locked state as
     * before the pause. Before the fix this collided with the stale cache entry and `lockComplete()`
     * was skipped forever — the device had physically left lock task via `stopLockTask()` but was
     * never told to re-enter it, while the bedtime screen (a separate state flow) kept claiming
     * locked.
     *
     * Fails before the fix: `lockCalls` stays 1 after the lapse (the re-lock is wrongly skipped).
     */
    @Test fun pauseLapse_underCompleteLock_reLocksEvenThoughLockStateIsUnchanged() = runTest {
        val prefs = freshPrefs()
        prefs.setManualLock(true)
        val actions = FakeActions(prefs); val alarms = FakeAlarms()
        val controller = LockController(prefs, actions, alarms)

        controller.reevaluate() // applies the complete lock
        assertEquals(1, actions.lockCalls)

        // Parent PIN starts a pause; still in the future.
        val deadline = System.currentTimeMillis() + 60_000L
        prefs.setPauseUntil(deadline)
        controller.reevaluate()
        assertEquals(1, actions.lockCalls) // pause branch never applies

        // The deadline lapses; manualLock/schedule never changed, so the recomputed LockState is
        // identical to the one applied before the pause.
        val lapsed = System.currentTimeMillis() - 5_000L
        prefs.setPauseUntil(lapsed)
        controller.reevaluate()

        assertEquals(2, actions.lockCalls)
    }
}
