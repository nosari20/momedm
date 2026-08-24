package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.ChildPrefs
import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.LockSchedule
import edu.fnosari.momedm.protocol.SafetyConfig
import edu.fnosari.momedm.protocol.SchemaEntry
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
    /** Stores and applies the parent's content restrictions (app managed configuration + private DNS). */
    suspend fun setSafety(config: SafetyConfig): Result<String>
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
    /** Managed-configuration settings [pkg] declares; empty when it declares none. */
    suspend fun appSchema(pkg: String): List<SchemaEntry>
}

/** Maps a [Message.Cmd] to policy actions; returns the messages to send back (RESULT first). Pure Kotlin. */
class CommandExecutor(private val policy: PolicyActions, private val status: StatusSource) {
    suspend fun execute(cmd: Message.Cmd): List<Message> {
        // Every result carries a stable [code]; the parent renders that in its own language. The text
        // alongside it is for a log, never for a person — it is written here, on the child, and the
        // parent may be reading in another language entirely.
        fun ok(code: String, msg: String, arg: Int? = null) = Message.Result(cmd.id, true, msg, code, arg)
        fun bad(msg: String) = listOf(Message.Result(cmd.id, false, msg, Message.Result.BAD_REQUEST))
        fun res(r: Result<Unit>, code: String, okMsg: String) = Message.Result(
            cmd.id, r.isSuccess,
            if (r.isSuccess) okMsg else (r.exceptionOrNull()?.message ?: "failed"),
            if (r.isSuccess) code else Message.Result.FAILED,
        )
        return when (cmd.type) {
            CmdType.KIOSK_ON -> {
                if (cmd.apps.isEmpty()) return bad("no apps")
                val pinned = cmd.pinned?.takeIf { it in cmd.apps }
                val note = if (cmd.pinned != null && pinned == null) ", pinned ignored: not in apps" else pinned?.let { ", pinned $it" } ?: ""
                val r = policy.kioskOn(cmd.apps, pinned)
                if (r.isSuccess) listOf(ok(Message.Result.KIOSK_ON, "kiosk on (${r.getOrThrow().size} apps$note)", r.getOrThrow().size), status.collect())
                else listOf(Message.Result(cmd.id, false, r.exceptionOrNull()?.message ?: "failed", Message.Result.FAILED))
            }
            CmdType.KIOSK_OFF -> { val r = policy.kioskOff(); if (r.isSuccess) listOf(res(r, Message.Result.KIOSK_OFF, "kiosk off"), status.collect()) else listOf(res(r, Message.Result.KIOSK_OFF, "")) }
            CmdType.INSTALL -> { val pkg = cmd.pkg ?: return bad("missing pkg"); listOf(res(policy.openPlay(pkg), Message.Result.PLAY_OPENED, "play opened for $pkg")) }
            CmdType.SEARCH_APP -> {
                val term = cmd.pkg?.trim().orEmpty()
                if (term.isEmpty()) return bad("missing search term")
                listOf(res(policy.openPlaySearch(term), Message.Result.PLAY_OPENED, "play search opened"))
            }
            CmdType.ADD_ACCOUNT -> listOf(res(policy.openAddAccount(), Message.Result.ACCOUNT, "account flow opened"))
            CmdType.GET_APP_SCHEMA -> {
                val pkg = cmd.pkg ?: return bad("missing pkg")
                val entries = status.appSchema(pkg)
                // An app with no schema is reported as an empty list, not an error: "declares nothing"
                // and "could not be read" are different answers and the parent is shown which.
                listOf(ok(Message.Result.SCHEMA, "${entries.size} setting(s)", entries.size), Message.Schema(pkg, entries))
            }
            CmdType.LIST_APPS -> listOf(ok(Message.Result.APPS, "apps"), Message.Apps(status.launchableApps()))
            CmdType.GET_STATUS -> listOf(ok(Message.Result.STATUS, "status"), status.collect())
            CmdType.SET_PREFS -> {
                val prefs = cmd.prefs ?: return bad("missing prefs")
                listOf(res(policy.applyPrefs(prefs.sanitized()), Message.Result.PREFS, "prefs applied"))
            }
            CmdType.SET_SAFETY -> {
                val cfg = cmd.safety ?: return bad("missing safety")
                val r = policy.setSafety(cfg.sanitized())
                if (r.isSuccess) listOf(ok(Message.Result.SAFETY, r.getOrThrow()), status.collect())
                else listOf(Message.Result(cmd.id, false, r.exceptionOrNull()?.message ?: "failed", Message.Result.FAILED))
            }
            CmdType.SET_SCHEDULE -> {
                val s = cmd.schedule ?: return bad("missing schedule")
                val r = policy.setSchedule(s.sanitized())
                if (r.isSuccess) listOf(res(r, Message.Result.SCHEDULE, "schedule set"), status.collect()) else listOf(res(r, Message.Result.SCHEDULE, ""))
            }
            CmdType.LOCK_NOW -> { val r = policy.setManualLock(true); if (r.isSuccess) listOf(res(r, Message.Result.LOCKED, "locked"), status.collect()) else listOf(res(r, Message.Result.LOCKED, "")) }
            CmdType.UNLOCK -> { val r = policy.setManualLock(false); if (r.isSuccess) listOf(res(r, Message.Result.UNLOCKED, "unlocked"), status.collect()) else listOf(res(r, Message.Result.UNLOCKED, "")) }
        }
    }
}
