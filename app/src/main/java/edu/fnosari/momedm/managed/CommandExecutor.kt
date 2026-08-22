package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.AppInfo
import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.Message

/** Managed-role actions the [CommandExecutor] drives; implemented by [PolicyManager]. */
interface PolicyActions {
    suspend fun kioskOn(pkg: String): Result<Unit>
    suspend fun kioskOff(): Result<Unit>
    suspend fun openPlay(pkg: String): Result<Unit>
    suspend fun openAddAccount(): Result<Unit>
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
                val pkg = cmd.pkg ?: return listOf(Message.Result(cmd.id, false, "missing pkg"))
                val r = policy.kioskOn(pkg)
                if (r.isSuccess) listOf(res(r, "kiosk on $pkg"), status.collect()) else listOf(res(r, ""))
            }
            CmdType.KIOSK_OFF -> { val r = policy.kioskOff(); if (r.isSuccess) listOf(res(r, "kiosk off"), status.collect()) else listOf(res(r, "")) }
            CmdType.INSTALL -> { val pkg = cmd.pkg ?: return listOf(Message.Result(cmd.id, false, "missing pkg")); listOf(res(policy.openPlay(pkg), "play opened for $pkg")) }
            CmdType.ADD_ACCOUNT -> listOf(res(policy.openAddAccount(), "account flow opened"))
            CmdType.LIST_APPS -> listOf(Message.Result(cmd.id, true, "apps"), Message.Apps(status.launchableApps()))
            CmdType.GET_STATUS -> listOf(Message.Result(cmd.id, true, "status"), status.collect())
            // TODO(kiosk v2): wired up once PolicyActions gains a setChildPrefs action (out of scope for this task).
            CmdType.SET_PREFS -> listOf(Message.Result(cmd.id, false, "not implemented"))
        }
    }
}
