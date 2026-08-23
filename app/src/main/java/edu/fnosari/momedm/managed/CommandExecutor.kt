package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.LockSchedule
import edu.fnosari.momedm.protocol.Message

/** Managed-role actions the [CommandExecutor] drives; implemented by [PolicyManager]. */
interface PolicyActions {
    /** Enters child mode with [apps] (optionally keeping [pinned] in front). Returns the apps actually allowed. */
    suspend fun kioskOn(apps: List<String>, pinned: String?): Result<List<String>>
    suspend fun kioskOff(): Result<Unit>
    suspend fun openPlay(pkg: String): Result<Unit>
    /** Opens Play's search results for a plain-language app name (no package id needed). */
    suspend fun openPlaySearch(term: String): Result<Unit>
    suspend fun openAddAccount(): Result<Unit>
    /** Stores and applies parent-pushed preferences (language, theme, accent, PIN). */
    suspend fun applyPrefs(prefs: ChildPrefs): Result<Unit>
    /** Persists the parent's nightly lock window and re-evaluates the lock immediately. */
    suspend fun setSchedule(schedule: LockSchedule): Result<Unit>
    /** Sets or clears the parent's manual lock and re-evaluates the lock immediately. */
    suspend fun setManualLock(on: Boolean): Result<Unit>
}

/** Read-only device state the [CommandExecutor] reports back; implemented by [StatusCollector]. */
interface StatusSource {
    suspend fun collect(): Message.Status
    suspend fun launchableApps(): List<AppInfo>
}

/** Maps a [Message.Cmd] to policy actions; returns the messages to send back (RESULT first). Pure Kotlin. */
class CommandExecutor(private val policy: PolicyActions, private val status: StatusSource) {
    suspend fun execute(cmd: Message.Cmd): List<Message> {
        fun res(r: Result<Unit>, okMsg: String) = Message.Result(cmd.id, r.isSuccess, if (r.isSuccess) okMsg else (r.exceptionOrNull()?.message ?: "failed"))
        return when (cmd.type) {
            CmdType.KIOSK_ON -> {
                if (cmd.apps.isEmpty()) return listOf(Message.Result(cmd.id, false, "no apps"))
                val pinned = cmd.pinned?.takeIf { it in cmd.apps }
                val note = if (cmd.pinned != null && pinned == null) ", pinned ignored: not in apps" else pinned?.let { ", pinned $it" } ?: ""
                val r = policy.kioskOn(cmd.apps, pinned)
                if (r.isSuccess) listOf(Message.Result(cmd.id, true, "kiosk on (${r.getOrThrow().size} apps$note)"), status.collect())
                else listOf(Message.Result(cmd.id, false, r.exceptionOrNull()?.message ?: "failed"))
            }
            CmdType.KIOSK_OFF -> { val r = policy.kioskOff(); if (r.isSuccess) listOf(res(r, "kiosk off"), status.collect()) else listOf(res(r, "")) }
            CmdType.INSTALL -> { val pkg = cmd.pkg ?: return listOf(Message.Result(cmd.id, false, "missing pkg")); listOf(res(policy.openPlay(pkg), "play opened for $pkg")) }
            CmdType.SEARCH_APP -> {
                val term = cmd.pkg?.trim().orEmpty()
                if (term.isEmpty()) return listOf(Message.Result(cmd.id, false, "missing search term"))
                listOf(res(policy.openPlaySearch(term), "play search opened"))
            }
            CmdType.ADD_ACCOUNT -> listOf(res(policy.openAddAccount(), "account flow opened"))
            CmdType.LIST_APPS -> listOf(Message.Result(cmd.id, true, "apps"), Message.Apps(status.launchableApps()))
            CmdType.GET_STATUS -> listOf(Message.Result(cmd.id, true, "status"), status.collect())
            CmdType.SET_PREFS -> {
                val prefs = cmd.prefs ?: return listOf(Message.Result(cmd.id, false, "missing prefs"))
                listOf(res(policy.applyPrefs(prefs.sanitized()), "prefs applied"))
            }
            CmdType.SET_SCHEDULE -> {
                val s = cmd.schedule ?: return listOf(Message.Result(cmd.id, false, "missing schedule"))
                val r = policy.setSchedule(s.sanitized())
                if (r.isSuccess) listOf(res(r, "schedule set"), status.collect()) else listOf(res(r, ""))
            }
            CmdType.LOCK_NOW -> { val r = policy.setManualLock(true); if (r.isSuccess) listOf(res(r, "locked"), status.collect()) else listOf(res(r, "")) }
            CmdType.UNLOCK -> { val r = policy.setManualLock(false); if (r.isSuccess) listOf(res(r, "unlocked"), status.collect()) else listOf(res(r, "")) }
        }
    }
}
