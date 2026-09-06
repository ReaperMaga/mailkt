package dev.reapermaga.mailkt.session

import dev.reapermaga.mailkt.util.JakartaPropertiesFactory
import jakarta.mail.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPStore
import java.util.UUID

/** Thread-safe, coroutine-aware base implementation for provider-specific IMAP sessions. */
open class ImapMailSession(
    private val host: String,
    private val supportedAuthMethods: Set<MailAuthMethod> = setOf(MailAuthMethod.OAUTH2),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val id: String = UUID.randomUUID().toString(),
) : MailSession {

    private val lifecycleMutex = Mutex()

    @Volatile
    private var connection: MailConnection? = null

    override val currentConnection: MailConnection?
        get() = connection

    override val isConnected: Boolean
        get() = connection?.store?.isConnected == true

    override suspend fun connect(credentials: MailCredentials): MailConnection =
        lifecycleMutex.withLock {
            require(credentials.method in supportedAuthMethods) {
                "${credentials.method} is not supported by $host"
            }

            val properties =
                when (credentials.method) {
                    MailAuthMethod.OAUTH2 -> JakartaPropertiesFactory.oauth2(host)
                    MailAuthMethod.PLAIN -> JakartaPropertiesFactory.plain(host)
                }
            val candidateSession = Session.getInstance(properties)
            val candidateStore = candidateSession.getStore("imap") as IMAPStore

            try {
                runInterruptible(ioDispatcher) {
                    candidateStore.connect(host, credentials.username, credentials.secret)
                }
            } catch (exception: CancellationException) {
                closeQuietly(candidateStore)
                throw exception
            } catch (exception: Exception) {
                closeQuietly(candidateStore)
                throw MailConnectionException("Unable to connect session $id to $host", exception)
            }

            val previous = connection
            val established = MailConnection(candidateSession, candidateStore)
            connection = established
            previous?.store?.let { oldStore ->
                try {
                    runInterruptible(ioDispatcher) { oldStore.close() }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // The new connection is already valid; stale-store cleanup is best effort.
                }
            }
            established
        }

    override suspend fun disconnect() =
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                val previous = connection ?: return@withLock
                connection = null
                runInterruptible(ioDispatcher) {
                    if (previous.store.isConnected) previous.store.close()
                }
            }
        }

    private suspend fun closeQuietly(store: IMAPStore) {
        withContext(NonCancellable) {
            try {
                runInterruptible(ioDispatcher) { if (store.isConnected) store.close() }
            } catch (_: Exception) {
                // Preserve the original connection or cancellation failure.
            }
        }
    }
}
