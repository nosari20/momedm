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
    /** Emitted when language/theme/accent/PIN change; the service re-pushes SET_PREFS to every online child. */
    val prefsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * One thing that happened on the BLE link, for the parent's connection screen.
     *
     * The distinction that matters when a child will not pair is *connected but never authenticated*
     * — the two sides disagree about the shared secret, typically because the child still holds an
     * older one — versus *nothing ever connected*: out of range, not advertising, or the child is not
     * scanning. Neither is visible from the device list, which only shows the end state.
     */
    data class LinkEvent(val atMs: Long, val kind: Kind, val detail: String) {
        enum class Kind { CONNECTED, AUTHENTICATED, REJECTED, DISCONNECTED, ADVERTISING, ERROR }
    }

    /** The most recent link events, newest first. In memory only — a diagnostic, not a record. */
    val events = MutableStateFlow<List<LinkEvent>>(emptyList())

    private const val MAX_EVENTS = 40

    /** Records a link event, dropping the oldest once [MAX_EVENTS] is reached. */
    fun logEvent(kind: LinkEvent.Kind, detail: String) {
        val e = LinkEvent(System.currentTimeMillis(), kind, detail)
        events.value = (listOf(e) + events.value).take(MAX_EVENTS)
    }

    /** Sends the command produced by [build] (given a fresh id). Returns the id, or null when [deviceId] is offline. */
    fun sendCmd(deviceId: String, build: (id: String) -> Message.Cmd): String? {
        val cmd = build(UUID.randomUUID().toString().substring(0, 8))
        return if (commander?.invoke(deviceId, cmd) == true) cmd.id else null
    }
    /** Returns the command id, or null if no authenticated session for [deviceId]. */
    fun sendCommand(deviceId: String, type: CmdType, pkg: String? = null): String? = sendCmd(deviceId) { Message.Cmd(it, type, pkg) }
}
