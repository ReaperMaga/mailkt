package dev.reapermaga.mailkt.session

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
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

    @Volatile
    internal var currentReconnectAttempt: Int = 0

    // Identity, rather than Store.isConnected, determines whether a failed generation is obsolete.
    internal class Generation(val number: Long, val connection: MailConnection)
    internal enum class RecoveryReason {
        STORE_CLOSED, SOCKET_FAILURE, DOWNLOAD_TIMEOUT, FOLDER_CLOSED, DISCONNECTED, RECONNECT_FAILED
    }
    private val recoveryLock = Any()
    private var generation = Generation(1, initialConnection)
    private var recoveryReason: RecoveryReason? = null
    internal val wakeMonitor = Channel<Unit>(Channel.CONFLATED)
    internal var monitorJob: Job? = null

    internal fun generation(): Generation = synchronized(recoveryLock) { generation }
    internal fun recoveryReason(): RecoveryReason? = synchronized(recoveryLock) { recoveryReason }

    /** Coalesces requests for the current generation; a late failure cannot replace a newer store. */
    internal fun requestRecovery(failed: Generation, reason: RecoveryReason): Boolean = synchronized(recoveryLock) {
        if (generation !== failed || mutableState.value is ManagedMailSessionState.Stopped || recoveryReason != null) {
            return@synchronized false
        }
        recoveryReason = reason
        mutableState.value = ManagedMailSessionState.Reconnecting(currentReconnectAttempt + 1)
        wakeMonitor.trySend(Unit)
        true
    }

    internal fun markHealthy(checked: Generation): Boolean = synchronized(recoveryLock) {
        if (generation !== checked || recoveryReason != null || mutableState.value is ManagedMailSessionState.Stopped) {
            return@synchronized false
        }
        if (mutableState.value !is ManagedMailSessionState.Connected) {
            mutableState.value = ManagedMailSessionState.Connected(checked.connection, reconnected = false)
        }
        true
    }

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

    internal fun updateState(state: ManagedMailSessionState) = synchronized(recoveryLock) {
        if (mutableState.value is ManagedMailSessionState.Stopped && state !is ManagedMailSessionState.Stopped) return@synchronized
        if (state is ManagedMailSessionState.Connected) {
            generation = Generation(generation.number + 1, state.connection)
            lastConnection = state.connection
            recoveryReason = null
        }
        mutableState.value = state
    }

    internal fun emit(event: ManagedMailSessionEvent) = synchronized(recoveryLock) {
        if (mutableState.value !is ManagedMailSessionState.Stopped) mutableEvents.tryEmit(event)
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
