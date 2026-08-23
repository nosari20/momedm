package edu.fnosari.momedm.managed

import android.content.Context
import android.util.Log
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.LockState
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * The two things [LockController] drives, extracted as interfaces so it can be unit-tested with
 * fakes instead of a concrete [PolicyManager] and the [LockAlarms] object (both Android-bound). This
 * mirrors [PolicyActions]/[StatusSource], which let [CommandExecutor] be JVM-tested the same way.
 */
interface LockActions {
    suspend fun lockComplete(): Result<Unit>
    suspend fun restoreNormal(): Result<Unit>
}

/** Schedules (or cancels) the wake-up for the next re-evaluation; implemented by [SystemAlarms]. */
interface Alarms {
    fun armNext(atMs: Long?)
}

/** Thin [Alarms] wrapper over the [LockAlarms] object, which needs a [Context] on every call. */
private class SystemAlarms(private val context: Context) : Alarms {
    override fun armNext(atMs: Long?) = LockAlarms.armNext(context, atMs)
}

/**
 * The single place that decides whether the device is completely locked and makes reality match.
 *
 * [reevaluate] is idempotent and cheap, and is called from every trigger that could change the
 * answer: an alarm, boot, a clock or timezone change, the launcher resuming, a PIN pause expiring,
 * and each of the three lock commands. Nothing persists "locked" — see [LockState].
 */
class LockController(private val prefs: ManagedPrefs, private val actions: LockActions, private val alarms: Alarms) {
    companion object {
        private const val LOG_TAG = "LockController"

        /**
         * The last [LockState] this class actually applied ([LockActions.lockComplete] /
         * [LockActions.restoreNormal]), in-memory only — a plain, non-persisted process-wide field,
         * never written to disk. It exists solely to stop the relaunch loop described on
         * [reevaluate]: it is never treated as the verdict itself, only as "did we already do this
         * exact thing", and [reevaluate] always recomputes [LockState] fresh from the persisted
         * inputs before consulting it. Cleared for free by process death (a reboot or an OOM kill),
         * which is exactly when the cache *should* be empty, since the DPM state itself was never
         * asserted by this process instance.
         */
        @Volatile private var lastApplied: LockState? = null

        /**
         * Test-only: clears the in-memory apply cache between test cases so they don't leak state
         * into each other via this class's shared companion field. Never called from production code.
         */
        internal fun resetCacheForTest() { lastApplied = null }

        /** Convenience for the receivers, which have only a [Context]. */
        fun of(context: Context): LockController {
            val p = ManagedSetup.prefs(context)
            val app = context.applicationContext
            return LockController(p, PolicyManager(app, p), SystemAlarms(app))
        }
    }

    suspend fun reevaluate() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val schedule = prefs.lockSchedule.first()
        val manual = prefs.manualLock.first()
        val pauseUntil = prefs.kioskConfig.first().pauseUntil

        // A parent-PIN pause always evaluates to unlocked (LockState.evaluate short-circuits on
        // pauseUntil > now), so if we fell through to the apply step below, an unlocked state would
        // call actions.restoreNormal() — which, with child mode on, re-runs the kiosk allowlist and
        // re-pins the device the parent just entered a PIN to free. That would happen on ANY trigger
        // during the pause: the parent editing the schedule, a clock change, a boot, or a
        // duplicate/stray alarm. So a live pause is handled entirely separately from the lock/unlock
        // decision below: it only arms the wake-up for when the pause itself ends and pushes status,
        // and does not touch lock/kiosk state at all. Do not "simplify" this by folding it back into
        // the general evaluate-then-apply path below — that is exactly the bug this short-circuit
        // exists to avoid.
        if (pauseUntil > now) {
            Log.d(LOG_TAG, "Pause active until $pauseUntil; leaving lock state untouched")
            alarms.armNext(pauseUntil)
            ManagedLinkState.statusPushRequests.tryEmit(Unit)
            return
        }

        // A pause deadline that has *lapsed* (0 < pauseUntil <= now) must be cleared here, before
        // applying state below. A restored kiosk incidentally clears it when the outcome is "restore
        // child mode", but a complete lock never touches it — so without this, a stale deadline would
        // survive under an active complete lock forever. That is exactly the busy loop this fixes:
        // applying a lock launches the launcher with CLEAR_TASK, which recreates its ManagedViewModel;
        // that ViewModel's trackPause() sees kioskConfig with the same stale pauseUntil > 0, decides
        // the pause has lapsed, and calls reevaluate() again — forever, with no persisted state ever
        // changing between iterations. Clearing it here breaks the cycle at the one place that knows
        // both facts at once: that the pause is over, and that the outcome might be a lock that
        // doesn't otherwise clear it.
        //
        // This does not persist a lock *decision* — only that a specific pause deadline has passed,
        // which is a fact about elapsed time, not about which lock state was chosen. It also cannot
        // oscillate: the write only happens when pauseUntil is genuinely > 0 here, so once it lands,
        // the next kioskConfig emission carries pauseUntil = 0 and every collector gated on
        // `pauseUntil > 0` (this branch, ManagedViewModel.trackPause's lapse branch, the service's
        // pause watchdog) reads that as "no pause" and does not re-trigger reevaluate() from this
        // cause. A caller with a genuinely fresh pause instead takes the `pauseUntil > now` branch
        // above and returns before ever reaching this write.
        if (pauseUntil > 0L) {
            prefs.setPauseUntil(0L)
        }

        val state = LockState.evaluate(schedule, manual, pauseUntil, now, zone)
        Log.d(LOG_TAG, "Re-evaluated: locked=${state.locked} reason=${state.reason}")

        // Skip re-applying when the computed state is exactly what we last applied. This exists so
        // that ON_RESUME can safely call reevaluate() on every resume (including the resume caused by
        // this very call's own launchHomeLocked()/CLEAR_TASK) without relaunching forever: the second
        // and subsequent resumes recompute the *same* LockState from the same persisted inputs, find
        // it equal to lastApplied, and skip the apply (and therefore skip the relaunch that would
        // trigger yet another ON_RESUME).
        //
        // This cannot cause the device to get stuck in a wrong policy:
        //  - It is a cache of "what we last *applied*", not of "what is locked" — LockState itself is
        //    still recomputed from scratch on every call, from the persisted schedule/manual/pause
        //    inputs, exactly as before. Any input change (a new schedule, LOCK_NOW/UNLOCK, a pause
        //    starting or the lapse-clear above) changes the computed LockState and therefore misses
        //    the cache, so the apply still runs.
        //  - It is process-memory only (a plain field, not DataStore), so a reboot or an OOM kill —
        //    the two failure modes §1.10 lists as the ones re-evaluation exists to catch — always
        //    starts with lastApplied == null, guaranteeing the first reevaluate() after either one
        //    actually applies, regardless of what it computes.
        //  - A failed apply never updates the cache (see below), so a device that failed to reach its
        //    correct policy keeps retrying on every subsequent trigger instead of being cached as
        //    "done".
        //  - Every other trigger (alarm, boot, clock change, the pause-lapse path above, the three
        //    commands) still calls reevaluate() exactly as before; this only removes a redundant
        //    *apply* when nothing about the decision changed, never a chance to reconsider it.
        if (state == lastApplied) {
            Log.d(LOG_TAG, "Lock state unchanged since last apply; skipping re-apply")
        } else {
            val applied = if (state.locked) actions.lockComplete() else actions.restoreNormal()
            // A failure is left for the next trigger to retry: nothing was persisted from the
            // attempt, the cache is not updated, so a retry starts from the same inputs, reaches the
            // same decision, and tries the apply again.
            applied.onSuccess { lastApplied = state }
            applied.onFailure { Log.w(LOG_TAG, "Could not apply lock state; will retry on the next trigger", it) }
        }

        // Wake up at the next window boundary. pauseUntil is never in the future at this point (the
        // branch above already returned whenever it was), so it never needs to be part of this.
        alarms.armNext(schedule.nextTransition(now, zone))
        ManagedLinkState.statusPushRequests.tryEmit(Unit)
    }
}

/** Constructs a [LockController] for call sites that already hold a [Context] and a [PolicyManager]. */
fun LockController(context: Context, prefs: ManagedPrefs, policy: PolicyManager): LockController =
    LockController(prefs, policy, SystemAlarms(context.applicationContext))
