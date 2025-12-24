package dev.reapermaga.mailkt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class MailSessionManager(
    private val keepAliveInterval: Long = 30000L,
    private val reconnectTimeout: Long = 5000L,
    private val exceptionHandler: (throwable: Throwable, session: ManagedMailSession) -> Unit = { throwable, _ -> throwable.printStackTrace() }
) {

    private val sessions: CopyOnWriteArrayList<ManagedMailSession> = CopyOnWriteArrayList()

    private val thread = Thread({
        while (true) {
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch {
                for (managed in sessions) {
                    try {
                        withTimeout(reconnectTimeout) {
                            if (!managed.session.isConnected) {
                                managed.lastKeepAliveCheck = Instant.now()
                                val conn = managed.connectionCallback(managed.session).join()
                                if (!conn.success) {
                                    error("Failed to reconnect: ${conn.error?.message}")
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        exceptionHandler(ex, managed)
                    }
                }
            }

            Thread.sleep(keepAliveInterval)
        }
    }, "MailSessionOrchestrator").apply { start() }

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
        thread.interrupt()
        for (managed in sessions) {
            managed.session.disconnect()
        }
        if (clearSessions) sessions.clear()
    }
}