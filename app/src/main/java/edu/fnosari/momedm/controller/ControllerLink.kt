package edu.fnosari.momedm.controller

import edu.fnosari.momedm.protocol.CmdType
import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Process-wide bridge between [ControllerService] and the UI. */
object ControllerLink {
    val advertising = MutableStateFlow(false)
    val online = MutableStateFlow<Set<String>>(emptySet())
    val results = MutableSharedFlow<Pair<String, Message.Result>>(extraBufferCapacity = 16)
    val apps = MutableSharedFlow<Pair<String, Message.Apps>>(extraBufferCapacity = 4)
    /** replay = 1 so a start failure emitted before the UI subscribes is not lost. */
    val errors = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    /** Installed by the running service. */
    @Volatile var commander: ((deviceId: String, cmd: Message.Cmd) -> Boolean)? = null

    /** Returns the command id, or null if no authenticated session for [deviceId]. */
    fun sendCommand(deviceId: String, type: CmdType, pkg: String? = null): String? {
        val cmd = Message.Cmd(UUID.randomUUID().toString().substring(0, 8), type, pkg)
        return if (commander?.invoke(deviceId, cmd) == true) cmd.id else null
    }
}
