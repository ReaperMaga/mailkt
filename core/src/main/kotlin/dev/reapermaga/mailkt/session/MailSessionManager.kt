package dev.reapermaga.mailkt.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.time.TimeSource
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

    /** Establishes an initial connection and starts managing [session]. */
    suspend fun manage(
        session: MailSession,
        connectionProvider: suspend (MailSession) -> MailConnection,
    ): ManagedMailSession {
        sessionsMutex.withLock { check(!stopped && managerJob.isActive) { "MailSessionManager is stopped" } }
        val connection = withTimeout(reconnectTimeout) { connectionProvider(session) }
        val managed = ManagedMailSession(session, connection, connectionProvider)
        val accepted =
            sessionsMutex.withLock {
                if (stopped || !managerJob.isActive) return@withLock false
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
        sessionsMutex.withLock {
            if (!stopped && managed in sessions.value) {
                managed.monitorJob = scope.launch {
                    // Independent loops: a slow mailbox cannot delay another mailbox's next check.
                    try {
                        while (isActive && managed in sessions.value) {
                            managed.wakeMonitor.tryReceive()
                            checkSession(managed)
                            if (managed !in sessions.value) break
                            withTimeoutOrNull(keepAliveInterval) { managed.wakeMonitor.receive() }
                        }
                    } finally {
                        if (managed.state.value !is ManagedMailSessionState.Stopped) {
                            managed.updateState(ManagedMailSessionState.Stopped())
                        }
                    }
                }
            }
        }
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
        managed.monitorJob?.let { job ->
            if (job !== currentCoroutineContext()[Job]) job.cancelAndJoin()
        }
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
            snapshot.forEach { it.updateState(ManagedMailSessionState.Stopped()) }
            managerJob.cancelAndJoin()
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
        val generation = managed.generation()
        val started = TimeSource.Monotonic.markNow()
        val attempt = managed.currentReconnectAttempt + 1
        var operation = "health-check"
        var reason = managed.recoveryReason()
        var replacement: MailConnection? = null
        try {
            // withTimeoutOrNull handles only THIS deadline, never a caller's timeout/cancellation.
            val completed = withTimeoutOrNull(reconnectTimeout) {
                val checkedAt = Instant.now()
                managed.lastKeepAliveCheck = checkedAt
                if (reason == null) {
                    val connected = runInterruptible(Dispatchers.IO) { managed.session.isConnected }
                    if (connected && managed.markHealthy(generation)) {
                        managed.currentReconnectAttempt = 0
                        managed.emit(ManagedMailSessionEvent.KeepAlive(checkedAt))
                        return@withTimeoutOrNull true
                    }
                }

                operation = "reconnect"
                reason = managed.recoveryReason() ?: ManagedMailSession.RecoveryReason.DISCONNECTED
                managed.currentReconnectAttempt = attempt
                managed.updateState(ManagedMailSessionState.Reconnecting(attempt))
                replacement = managed.connectionProvider(managed.session)
                true
            }
            if (completed == null) throw TimeoutException("$operation exceeded $reconnectTimeout")
            currentCoroutineContext().ensureActive()
            replacement?.let { connection ->
                // Publish only after the deadline scope successfully returns.
                managed.updateState(ManagedMailSessionState.Connected(connection, reconnected = true))
                managed.currentReconnectAttempt = 0
                managed.emit(ManagedMailSessionEvent.Connected(connection, reconnected = true))
                logger.info(
                    "Mail session {} operation={} attempt={} elapsed={} generation={} reason={} outcome=connected newGeneration={}",
                    managed.session.id, operation, attempt, started.elapsedNow(), generation.number, reason,
                    managed.generation().number,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            managed.currentReconnectAttempt = attempt
            // A provider may fail after modifying its store. Never advertise the old snapshot as
            // healthy just because the session's (possibly different) store reports connected.
            if (operation == "reconnect") {
                managed.requestRecovery(generation, reason ?: ManagedMailSession.RecoveryReason.RECONNECT_FAILED)
            }
            managed.updateState(ManagedMailSessionState.ReconnectFailed(attempt, exception))
            managed.emit(ManagedMailSessionEvent.ReconnectFailed(attempt, exception))
            logger.warn(
                "Mail session {} operation={} attempt={} elapsed={} generation={} reason={} outcome=failed failureType={}",
                managed.session.id, operation, attempt, started.elapsedNow(), generation.number, reason, exception.javaClass.name,
            )
            reportFailure(exception, managed)
            if (attempt >= maxReconnectAttempts) {
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
        logger.warn("Mail session {} lifecycle failure type={}", managed.session.id, throwable.javaClass.name)
        runCatching { exceptionHandler(throwable, managed) }
            .onFailure { logger.error("Mail session exception handler failed type={}", it.javaClass.name) }
    }
}
