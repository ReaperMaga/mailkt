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
                                    val conn = managed.connectionCallback(managed.session).await()
                                    if (!conn.success) {
                                        error("Failed to reconnect: ${conn.error?.message}")
                                    }
                                } else {
                                    logger.info("Session is connected")
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
        connectionCallback: (session: MailSession) -> CompletableFuture<MailConnection>
    ): CompletableFuture<MailConnection> {
        return connectionCallback(session).thenApply { conn ->
            sessions.add(ManagedMailSession(session, Instant.now(), connectionCallback))
            conn
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