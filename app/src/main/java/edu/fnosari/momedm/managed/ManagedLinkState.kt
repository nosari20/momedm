package edu.fnosari.momedm.managed

import edu.fnosari.momedm.protocol.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide observable link state, written by [ManagedLinkService], read by the managed UI. */
object ManagedLinkState {
    enum class LinkState { IDLE, SCANNING, CONNECTED, AUTHENTICATED }
    val state = MutableStateFlow(LinkState.IDLE)
    val lastStatus = MutableStateFlow<Message.Status?>(null)
    val lastError = MutableStateFlow<String?>(null)
    /** Emitted by [PolicyManager] (pause/resume) to ask [ManagedLinkService] to push a fresh STATUS. */
    val statusPushRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
}
