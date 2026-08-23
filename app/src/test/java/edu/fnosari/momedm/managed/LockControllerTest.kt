package edu.fnosari.momedm.managed

import edu.fnosari.momedm.persistence.InMemoryPreferencesProvider
import edu.fnosari.momedm.persistence.KioskConfig
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.LockSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * [LockController] against fakes of its two Android-bound collaborators ([LockActions],
 * [Alarms]) — the same seam [CommandExecutorTest] uses for [PolicyActions]. Pins the three pieces
 * that fixed the two live-found defects (the pause short-circuit, the stale-pause clear, and the
 * decide-then-apply flow) plus the ON_RESUME apply-dedup cache from the final review pass.
 */
class LockControllerTest {
    private class FakeActions : LockActions {
        var lockCalls = 0
        var restoreCalls = 0
        var lockResult: Result<Unit> = Result.success(Unit)
        override suspend fun lockComplete(): Result<Unit> { lockCalls++; return lockResult }
        override suspend fun restoreNormal(): Result<Unit> { restoreCalls++; return Result.success(Unit) }
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
        val actions = FakeActions(); val alarms = FakeAlarms()

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
        val actions = FakeActions(); val alarms = FakeAlarms()

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
        val actions = FakeActions(); val alarms = FakeAlarms()

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
        val actions = FakeActions(); val alarms = FakeAlarms()

        LockController(prefs, actions, alarms).reevaluate()

        // reevaluate() computes "now" internally, a few micros after `before` — nextTransition only
        // differs from this if a window boundary fell in that gap, which is not observable in a fast
        // unit test.
        assertEquals(schedule.nextTransition(before, ZoneId.systemDefault()), alarms.lastArmed)
    }

    @Test fun unlocked_callsRestoreNormal() = runTest {
        val prefs = freshPrefs()
        val actions = FakeActions(); val alarms = FakeAlarms()

        LockController(prefs, actions, alarms).reevaluate()

        assertEquals(1, actions.restoreCalls)
        assertEquals(0, actions.lockCalls)
    }

    @Test fun sameStateAppliedTwiceInARow_secondApplySkipped_alarmStillReArmed() = runTest {
        val prefs = freshPrefs()
        val actions = FakeActions(); val alarms = FakeAlarms()
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

    @Test fun aDifferentInputChangeIsNotSuppressedByTheCache() = runTest {
        val prefs = freshPrefs()
        val actions = FakeActions(); val alarms = FakeAlarms()
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
        val actions = FakeActions().apply { lockResult = Result.failure(IllegalStateException("boom")) }
        val alarms = FakeAlarms()
        val controller = LockController(prefs, actions, alarms)

        controller.reevaluate()
        controller.reevaluate()

        // Neither attempt was cached as "done" because both failed — every trigger retries.
        assertEquals(2, actions.lockCalls)
    }
}
