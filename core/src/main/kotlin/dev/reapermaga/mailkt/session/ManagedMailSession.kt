package dev.reapermaga.mailkt.session

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

class ManagedMailSession internal constructor(
    val session: MailSession,
    initialConnection: MailConnection,
    internal val connectionProvider: suspend (MailSession) -> MailConnection,
) {
    @Volatile
    var lastKeepAliveCheck: Instant = Instant.now()
        internal set

    @Volatile
    var lastConnection: MailConnection = initialConnection
        internal set

    internal var currentReconnectAttempt: Int = 0

    private val mutableState =
        MutableStateFlow<ManagedMailSessionState>(
            ManagedMailSessionState.Connected(initialConnection, reconnected = false)
        )
    val state: StateFlow<ManagedMailSessionState> = mutableState.asStateFlow()

    private val mutableEvents =
        MutableSharedFlow<ManagedMailSessionEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<ManagedMailSessionEvent> = mutableEvents.asSharedFlow()

    internal fun updateState(state: ManagedMailSessionState) {
        mutableState.value = state
    }

    internal fun emit(event: ManagedMailSessionEvent) {
        mutableEvents.tryEmit(event)
    }
}

sealed interface ManagedMailSessionState {
    data class Connected(val connection: MailConnection, val reconnected: Boolean) :
        ManagedMailSessionState

    data class Reconnecting(val attempt: Int) : ManagedMailSessionState

    data class ReconnectFailed(val attempts: Int, val cause: Throwable) : ManagedMailSessionState

    data class Stopped(val cause: Throwable? = null) : ManagedMailSessionState
}

sealed interface ManagedMailSessionEvent {
    data class Connected(val connection: MailConnection, val reconnected: Boolean) :
        ManagedMailSessionEvent

    data class KeepAlive(val checkedAt: Instant) : ManagedMailSessionEvent

    data class ReconnectFailed(val attempt: Int, val cause: Throwable) : ManagedMailSessionEvent
}
