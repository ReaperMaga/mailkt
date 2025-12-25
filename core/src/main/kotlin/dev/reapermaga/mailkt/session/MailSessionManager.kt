package dev.reapermaga.mailkt.session

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class MailSessionManager(
    private val keepAliveInterval: Long = 30000L,
    private val reconnectTimeout: Long = 5000L,
    private val exceptionHandler: (throwable: Throwable, session: ManagedMailSession) -> Unit = { throwable, _ -> throwable.printStackTrace() }
) {

    private val logger = LoggerFactory.getLogger(MailSessionManager::class.java)

    private val sessions: CopyOnWriteArrayList<ManagedMailSession> = CopyOnWriteArrayList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    fun stop(clearSessions: Boolean = true) {
        job.cancel()
        for (managed in sessions) {
            managed.session.disconnect()
        }
        if (clearSessions) sessions.clear()
    }
}