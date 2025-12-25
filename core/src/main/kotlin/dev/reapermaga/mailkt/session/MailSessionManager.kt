package dev.reapermaga.mailkt.session

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages MailSession instances by periodically performing keep-alive checks and attempting reconnections.
 *
 * The manager runs a background coroutine that iterates over managed sessions at a fixed interval
 * and invokes lifecycle events. If a session is not connected, the provided connectionProvider is used
 * to attempt a reconnect. Exceptions during management are delegated to [exceptionHandler].
 *
 * @param keepAliveInterval interval between keep-alive checks in milliseconds (default 30_000 ms).
 * @param reconnectTimeout timeout for per-session reconnection/check operations in milliseconds (default 5_000 ms).
 * @param exceptionHandler callback invoked when an exception occurs while managing a session. Receives the throwable
 *                         and the associated ManagedMailSession.
 */
class MailSessionManager(
    private val keepAliveInterval: Long = 30000L,
    private val reconnectTimeout: Long = 5000L,
    private val exceptionHandler: (throwable: Throwable, session: ManagedMailSession) -> Unit = { throwable, _ -> throwable.printStackTrace() }
) {

    private val logger = LoggerFactory.getLogger(MailSessionManager::class.java)

    /**
     * Thread-safe list of currently managed sessions.
     */
    private val sessions: CopyOnWriteArrayList<ManagedMailSession> = CopyOnWriteArrayList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Background job that periodically:
     *  - checks each managed session's connection status,
     *  - attempts reconnection via the session's connectionProvider if disconnected,
     *  - updates timestamps and emits lifecycle events (connection and keepAlive).
     *
     * The per-session work is performed with a per-session [reconnectTimeout] and exceptions are handled
     * using [exceptionHandler].
     */
    private val job = scope.launch {
        while (isActive) {
            val snapshot = sessions.toList()
            logger.info("Checking ${snapshot.size} managed mail sessions for keep-alive...")
            supervisorScope {
                snapshot.map { managed ->
                    async {
                        try {
                            withTimeout(reconnectTimeout) {
                                managed.lastKeepAliveCheck = Instant.now()
                                if (!managed.session.isConnected) {
                                    logger.info("Session is not connected, attempting to reconnect...")
                                    val conn = managed.connectionProvider(managed.session).await()
                                    managed.lastConnection = conn
                                    managed.lifecycle.connection.forEach {
                                        it.onEvent(conn)
                                    }
                                    if (!conn.success) {
                                        error("Failed to reconnect: ${conn.error?.message}")
                                    }
                                } else {
                                    logger.info("Session is connected")
                                }
                                managed.lifecycle.keepAlive.forEach {
                                    it.onEvent(Unit)
                                }
                            }
                        } catch (ex: Exception) {
                            exceptionHandler(ex, managed)
                        }
                    }
                }.awaitAll()
            }
            delay(keepAliveInterval)
        }
    }

    /**
     * Begin managing a MailSession by obtaining an initial connection and registering a ManagedMailSession.
     *
     * The returned CompletableFuture completes when the initial connection is acquired. The created
     * ManagedMailSession is added to the internal registry and will be monitored by the manager's background job.
     *
     * @param session the MailSession to manage
     * @param connectionProvider a function that returns a CompletableFuture of MailConnection for the given session
     * @return CompletableFuture that completes with the created ManagedMailSession after obtaining the initial connection
     */
    fun manage(
        session: MailSession,
        connectionProvider: (session: MailSession) -> CompletableFuture<MailConnection>
    ): CompletableFuture<ManagedMailSession> {
        return connectionProvider(session).thenApply { conn ->
            ManagedMailSession(session, Instant.now(), conn, connectionProvider).also {
                sessions.add(it)
            }
        }
    }

    /**
     * Stop the manager: cancels the background job and disconnects all managed sessions.
     *
     * @param clearSessions when true (default) the internal registry of managed sessions is cleared after disconnecting.
     */
    fun stop(clearSessions: Boolean = true) {
        job.cancel()
        for (managed in sessions) {
            managed.session.disconnect()
        }
        if (clearSessions) sessions.clear()
    }
}