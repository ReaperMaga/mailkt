package dev.reapermaga.mailkt.internal.connection

import dev.reapermaga.mailkt.client.ConnectionPolicy
import dev.reapermaga.mailkt.internal.mime.FailureClass
import dev.reapermaga.mailkt.internal.mime.LogCategory
import dev.reapermaga.mailkt.internal.mime.MailLog
import dev.reapermaga.mailkt.internal.mime.RecoveryClassifier
import dev.reapermaga.mailkt.internal.transport.TransportConnection
import dev.reapermaga.mailkt.internal.transport.TransportFactory
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MailboxEvent
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.MailboxState
import dev.reapermaga.mailkt.model.RecoveryReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Clock

/**
 * Lifecycle owner for one mailbox: at most one published connection generation, coalesced
 * generation-safe recovery, independent health checks and idempotent close.
 * Holds no global state; every mailbox owns its own instance and scope.
 */
internal class ConnectionManager private constructor(
    id: MailboxId,
    private val factory: TransportFactory,
    private val policy: ConnectionPolicy,
    private val clock: Clock,
    private val delayer: Delayer,
    private val random: RandomSource,
    dispatcher: CoroutineDispatcher,
) {
    private val log = MailLog.forMailbox(LogCategory.LIFECYCLE, id)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val mutableState = MutableStateFlow<MailboxState>(MailboxState.Reconnecting(0, RecoveryReason.REQUESTED))
    private val mutableEvents = MutableSharedFlow<MailboxEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val closedSignal = CompletableDeferred<Unit>()

    // Guarded by lock.
    private var current: Lease? = null
    private var generation = 0L
    private var recovery: Job? = null
    private var closing = false

    val state: StateFlow<MailboxState> = mutableState.asStateFlow()
    val events: Flow<MailboxEvent> = mutableEvents.asSharedFlow()

    val currentGeneration: Long get() = synchronized(lock) { generation }

    /** Runs [block] pinned to the current generation. Failures are classified and may trigger recovery. */
    suspend fun <T> withConnection(block: suspend (Lease) -> T): T {
        val lease = synchronized(lock) {
            if (closing) throw MailException.MailboxClosed()
            current ?: throw MailException.NotConnected(mutableState.value)
        }
        try {
            return block(lease)
        } catch (e: Throwable) {
            throw onOperationFailure(lease.generation, e)
        }
    }

    /** Transport code reports a failed generation; stale or duplicate reports are ignored. */
    fun reportFailure(generation: Long, reason: RecoveryReason) {
        synchronized(lock) {
            when {
                closing -> return
                generation != this.generation -> {
                    log.debug("recovery.stale", "generation" to generation)
                    return
                }
                recovery != null -> {
                    log.debug("recovery.coalesced", "generation" to generation)
                    return
                }
                current == null -> return
            }
            startRecoveryLocked(reason)
        }
    }

    /** Resumes management after reauthorization or from a recoverable Failed state; coalesces with running recovery. */
    suspend fun reconnect() {
        val job = synchronized(lock) {
            if (closing) throw MailException.MailboxClosed()
            recovery ?: when (val s = mutableState.value) {
                is MailboxState.Failed -> {
                    if (!s.recoverable) throw s.cause
                    startRecoveryLocked(RecoveryReason.REQUESTED)
                }
                MailboxState.AuthenticationRequired -> startRecoveryLocked(RecoveryReason.REQUESTED)
                else -> null
            }
        }
        job?.join()
        when (val s = mutableState.value) {
            is MailboxState.Failed -> throw s.cause
            MailboxState.AuthenticationRequired -> throw MailException.AuthenticationRequired()
            MailboxState.Closed -> throw MailException.MailboxClosed()
            else -> Unit
        }
    }

    /** Idempotent. Rejects new operations, cancels background work, closes the connection, publishes Closed once. */
    suspend fun close() {
        val first = synchronized(lock) { if (closing) false else { closing = true; true } }
        if (!first) {
            closedSignal.await()
            return
        }
        withContext(NonCancellable) {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            val last = synchronized(lock) { current.also { current = null; recovery = null } }
            closeQuietly(last?.connection)
            synchronized(lock) { publish(MailboxState.Closed) }
            closedSignal.complete(Unit)
        }
    }

    private fun onOperationFailure(gen: Long, e: Throwable): Throwable = when (val c = RecoveryClassifier.classify(e)) {
        FailureClass.Cancelled -> e
        FailureClass.Authentication -> {
            authRequired(gen)
            e as? MailException.AuthenticationRequired ?: MailException.AuthenticationRequired(cause = e)
        }
        is FailureClass.Recoverable -> {
            reportFailure(gen, c.reason)
            MailException.ConnectionFailed(c.reason, e)
        }
        is FailureClass.Fatal -> RecoveryClassifier.toMailException(e)
    }

    private fun authRequired(gen: Long) {
        val lost = synchronized(lock) {
            if (closing || gen != generation || current == null) return
            current.also { current = null; publish(MailboxState.AuthenticationRequired) }
        }
        scope.launch { closeQuietly(lost?.connection) }
    }

    /** Must hold [lock]. Returns the started job. */
    private fun startRecoveryLocked(reason: RecoveryReason): Job {
        val old = current?.connection
        current = null
        publish(MailboxState.Reconnecting(1, reason))
        mutableEvents.tryEmit(MailboxEvent.RecoveryStarted(reason, generation, clock.instant()))
        val job = scope.launch(start = CoroutineStart.LAZY) { runRecovery(reason, old) }
        recovery = job
        job.start()
        return job
    }

    private suspend fun runRecovery(reason: RecoveryReason, old: TransportConnection?) {
        var oldClosed = false
        try {
            var attempt = 1
            while (true) {
                if (attempt > 1) synchronized(lock) { if (!closing) publish(MailboxState.Reconnecting(attempt, reason)) }
                val failure: Throwable = try {
                    install(connectOnce(), old).also { oldClosed = true }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e
                }
                log.debug("recovery.attempt.failed", "attempt" to attempt, "failureType" to failure.javaClass.simpleName)
                when (val c = RecoveryClassifier.classify(failure)) {
                    FailureClass.Authentication -> return finish(MailboxState.AuthenticationRequired)
                    is FailureClass.Recoverable -> {
                        if (attempt >= policy.maxReconnectAttempts) {
                            log.warn("recovery.exhausted", failure, "attempt" to attempt, "reason" to c.reason)
                            return finish(MailboxState.Failed(RecoveryClassifier.toMailException(failure), true))
                        }
                        val wait = policy.backoff(attempt, random.nextDouble())
                        delayer.delay(wait.inWholeMilliseconds)
                        attempt++
                    }
                    else -> return finish(MailboxState.Failed(RecoveryClassifier.toMailException(failure), false))
                }
            }
        } finally {
            if (!oldClosed) closeQuietly(old)
        }
    }

    private fun finish(terminal: MailboxState) {
        synchronized(lock) {
            if (closing) return
            recovery = null
            publish(terminal)
        }
    }

    /** Publishes the validated replacement, then closes the obsolete connection exactly once. */
    private suspend fun install(fresh: TransportConnection, old: TransportConnection?) {
        val gen = synchronized(lock) {
            if (closing) null else (++generation).also {
                current = Lease(it, fresh)
                recovery = null
                publish(MailboxState.Connected)
            }
        }
        if (gen == null) closeQuietly(fresh) else mutableEvents.tryEmit(MailboxEvent.Reconnected(gen, clock.instant()))
        closeQuietly(old)
    }

    private suspend fun connectOnce(): TransportConnection = try {
        withTimeout(policy.attemptTimeout) { factory.connect() }
    } catch (e: TimeoutCancellationException) {
        throw MailException.ConnectionFailed(RecoveryReason.TIMEOUT, e)
    }

    private suspend fun healthLoop() {
        while (currentCoroutineContext().isActive) {
            delayer.delay(policy.keepAliveInterval.inWholeMilliseconds)
            val lease = synchronized(lock) { current } ?: continue
            try {
                withTimeout(policy.attemptTimeout) { lease.connection.imap.noop() }
            } catch (e: TimeoutCancellationException) {
                reportFailure(lease.generation, RecoveryReason.HEALTH_CHECK)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (RecoveryClassifier.classify(e) == FailureClass.Authentication) authRequired(lease.generation)
                else reportFailure(lease.generation, RecoveryReason.HEALTH_CHECK)
            }
        }
    }

    private suspend fun closeQuietly(connection: TransportConnection?) {
        if (connection == null) return
        withContext(NonCancellable) {
            try {
                connection.close()
            } catch (e: Throwable) {
                log.debug("close.failed", "failureType" to e.javaClass.simpleName)
            }
        }
    }

    private fun publish(s: MailboxState) {
        mutableState.value = s
        mutableEvents.tryEmit(MailboxEvent.StateChanged(s, clock.instant()))
        log.info("state", "state" to s.javaClass.simpleName, "generation" to generation)
    }

    companion object {
        /** Connects once (no retries). Failures are typed; no partially initialized manager escapes. */
        suspend fun open(
            id: MailboxId,
            factory: TransportFactory,
            policy: ConnectionPolicy = ConnectionPolicy(),
            clock: Clock = Clock.systemUTC(),
            delayer: Delayer = Delayer.System,
            random: RandomSource = RandomSource.Default,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): ConnectionManager {
            val m = ConnectionManager(id, factory, policy, clock, delayer, random, dispatcher)
            try {
                val c = m.connectOnce()
                synchronized(m.lock) {
                    m.generation = 1
                    m.current = Lease(1, c)
                    m.publish(MailboxState.Connected)
                }
            } catch (e: CancellationException) {
                m.scope.cancel()
                throw e
            } catch (e: Throwable) {
                m.scope.cancel()
                throw RecoveryClassifier.toMailException(e)
            }
            m.scope.launch { m.healthLoop() }
            return m
        }
    }
}
