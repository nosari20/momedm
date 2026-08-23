package edu.fnosari.momedm.managed

import android.content.Context
import android.util.Log
import edu.fnosari.momedm.persistence.ManagedPrefs
import edu.fnosari.momedm.protocol.LockState
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * The single place that decides whether the device is completely locked and makes reality match.
 *
 * [reevaluate] is idempotent and cheap, and is called from every trigger that could change the
 * answer: an alarm, boot, a clock or timezone change, the launcher resuming, a PIN pause expiring,
 * and each of the three lock commands. Nothing persists "locked" — see [LockState].
 */
class LockController(private val context: Context, private val prefs: ManagedPrefs, private val policy: PolicyManager) {
    companion object {
        private const val LOG_TAG = "LockController"

        /** Convenience for the receivers, which have only a [Context]. */
        fun of(context: Context): LockController {
            val p = ManagedSetup.prefs(context)
            return LockController(context.applicationContext, p, PolicyManager(context.applicationContext, p))
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
        // call policy.restoreNormal() — which, with child mode on, re-runs kioskOn() and re-pins the
        // device the parent just entered a PIN to free. That would happen on ANY trigger during the
        // pause: the parent editing the schedule, a clock change, a boot, or a duplicate/stray alarm.
        // So a live pause is handled entirely separately from the lock/unlock decision below: it only
        // arms the wake-up for when the pause itself ends and pushes status, and does not touch
        // lock/kiosk state at all. Do not "simplify" this by folding it back into the general
        // evaluate-then-apply path below — that is exactly the bug this short-circuit exists to avoid.
        if (pauseUntil > now) {
            Log.d(LOG_TAG, "Pause active until $pauseUntil; leaving lock state untouched")
            LockAlarms.armNext(context, pauseUntil)
            ManagedLinkState.statusPushRequests.tryEmit(Unit)
            return
        }

        // A pause deadline that has *lapsed* (0 < pauseUntil <= now) must be cleared here, before
        // applying state below. kioskOn() (via restoreNormal()) incidentally clears it when the
        // outcome is "restore child mode", but lockComplete() never touches it — so without this, a
        // stale deadline would survive under an active complete lock forever. That is exactly the
        // busy loop this fixes: lockComplete() calls launchHomeLocked(), which CLEAR_TASKs the
        // launcher Activity and so recreates its ManagedViewModel; that ViewModel's trackPause() sees
        // kioskConfig with the same stale pauseUntil > 0, decides the pause has lapsed, and calls
        // reevaluate() again — forever, with no persisted state ever changing between iterations.
        // Clearing it here breaks the cycle at the one place that knows both facts at once: that the
        // pause is over, and that the outcome might be a lock lockComplete() won't clear it for.
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

        val applied = if (state.locked) policy.lockComplete() else policy.restoreNormal()
        // A failure is left for the next trigger to retry: nothing was persisted from the attempt,
        // so a retry starts from the same inputs and reaches the same decision.
        applied.onFailure { Log.w(LOG_TAG, "Could not apply lock state; will retry on the next trigger", it) }

        // Wake up at the next window boundary. pauseUntil is never in the future at this point (the
        // branch above already returned whenever it was), so it never needs to be part of this.
        LockAlarms.armNext(context, schedule.nextTransition(now, zone))
        ManagedLinkState.statusPushRequests.tryEmit(Unit)
    }
}
