package dev.reapermaga.mailkt.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Maintains and reconnects mail sessions in a lifecycle bound to [parentScope]. */
class MailSessionManager(
    private val keepAliveInterval: Duration = 30.seconds,
    private val reconnectTimeout: Duration = 30.seconds,
    private val maxReconnectAttempts: Int = 5,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val exceptionHandler: (Throwable, ManagedMailSession) -> Unit = { _, _ -> },
) {
    init {
        require(keepAliveInterval.isPositive()) { "keepAliveInterval must be positive" }
        require(reconnectTimeout.isPositive()) { "reconnectTimeout must be positive" }
        require(maxReconnectAttempts > 0) { "maxReconnectAttempts must be positive" }
    }

    private val logger = LoggerFactory.getLogger(MailSessionManager::class.java)
    private val managerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + managerJob)
    private val sessionsMutex = Mutex()
    private val mutableSessions = MutableStateFlow<List<ManagedMailSession>>(emptyList())
    private var stopped = false

    /** Immutable snapshots of sessions currently owned by this manager. */
    val sessions: StateFlow<List<ManagedMailSession>> = mutableSessions.asStateFlow()

    private val monitorJob =
        scope.launch {
            while (isActive) {
                val snapshot = sessions.value
                supervisorScope {
                    snapshot.map { managed -> async { checkSession(managed) } }.forEach { it.await() }
                }
                delay(keepAliveInterval)
            }
        }

    /** Establishes an initial connection and starts managing [session]. */
    suspend fun manage(
        session: MailSession,
        connectionProvider: suspend (MailSession) -> MailConnection,
    ): ManagedMailSession {
        sessionsMutex.withLock { check(!stopped) { "MailSessionManager is stopped" } }
        val connection = withTimeout(reconnectTimeout) { connectionProvider(session) }
        val managed = ManagedMailSession(session, connection, connectionProvider)
        val accepted =
            sessionsMutex.withLock {
                if (stopped) return@withLock false
                require(mutableSessions.value.none { it.session.id == session.id }) {
                    "A session with id ${session.id} is already managed"
                }
                mutableSessions.value = mutableSessions.value + managed
                true
            }
        if (!accepted) {
            session.disconnect()
            error("MailSessionManager was stopped while connecting session ${session.id}")
        }
        managed.emit(ManagedMailSessionEvent.Connected(connection, reconnected = false))
        return managed
    }

    /** Stops managing one session and optionally disconnects it. */
    suspend fun remove(managed: ManagedMailSession, disconnect: Boolean = true) {
        val removed =
            sessionsMutex.withLock {
                val previous = mutableSessions.value
                mutableSessions.value = previous - managed
                previous.size != mutableSessions.value.size
            }
        if (!removed) return
        managed.updateState(ManagedMailSessionState.Stopped())
        if (disconnect) managed.session.disconnect()
    }

    /** Cancels monitoring, disconnects all sessions, and optionally clears the registry. */
    suspend fun stop(clearSessions: Boolean = true) =
        withContext(NonCancellable) {
            val snapshot =
                sessionsMutex.withLock {
                    stopped = true
                    sessions.value.also {
                        if (clearSessions) mutableSessions.value = emptyList()
                    }
                }
            managerJob.cancel()
            monitorJob.cancelAndJoin()
            supervisorScope {
                snapshot
                    .map { managed ->
                        async {
                            try {
                                managed.session.disconnect()
                            } catch (exception: Exception) {
                                reportFailure(exception, managed)
                            }
                            managed.updateState(ManagedMailSessionState.Stopped())
                        }
                    }
                    .forEach { it.await() }
            }
        }

    private suspend fun checkSession(managed: ManagedMailSession) {
        if (managed !in sessions.value) return
        try {
            withTimeout(reconnectTimeout) {
                val checkedAt = Instant.now()
                managed.lastKeepAliveCheck = checkedAt
                val connected = runInterruptible(Dispatchers.IO) { managed.session.isConnected }
                if (connected) {
                    managed.currentReconnectAttempt = 0
                    managed.emit(ManagedMailSessionEvent.KeepAlive(checkedAt))
                    return@withTimeout
                }

                val attempt = ++managed.currentReconnectAttempt
                managed.updateState(ManagedMailSessionState.Reconnecting(attempt))
                val connection = managed.connectionProvider(managed.session)
                managed.lastConnection = connection
                managed.currentReconnectAttempt = 0
                managed.updateState(
                    ManagedMailSessionState.Connected(connection, reconnected = true)
                )
                managed.emit(ManagedMailSessionEvent.Connected(connection, reconnected = true))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val attempts =
                if (managed.currentReconnectAttempt == 0) {
                    ++managed.currentReconnectAttempt
                } else {
                    managed.currentReconnectAttempt
                }
            managed.updateState(ManagedMailSessionState.ReconnectFailed(attempts, exception))
            managed.emit(ManagedMailSessionEvent.ReconnectFailed(attempts, exception))
            reportFailure(exception, managed)
            if (attempts >= maxReconnectAttempts) {
                logger.warn(
                    "Removing mail session {} after {} failed reconnect attempts",
                    managed.session.id,
                    attempts,
                )
                try {
                    remove(managed)
                    managed.updateState(ManagedMailSessionState.Stopped(exception))
                } catch (removalFailure: CancellationException) {
                    throw removalFailure
                } catch (removalFailure: Exception) {
                    reportFailure(removalFailure, managed)
                }
            }
        }
    }

    private fun reportFailure(throwable: Throwable, managed: ManagedMailSession) {
        logger.warn("Mail session {} lifecycle failure", managed.session.id, throwable)
        runCatching { exceptionHandler(throwable, managed) }
            .onFailure { logger.error("Mail session exception handler failed", it) }
    }
}
