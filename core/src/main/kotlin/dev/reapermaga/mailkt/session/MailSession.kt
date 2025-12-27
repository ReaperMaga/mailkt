package dev.reapermaga.mailkt.session

import com.sun.mail.imap.IMAPStore
import dev.reapermaga.mailkt.auth.MailAuthMethod
import jakarta.mail.Session
import java.util.concurrent.CompletableFuture

/**
 * Abstraction over a mail session capable of exposing its current state and performing connect/disconnect operations.
 */
interface MailSession {

    /**
     * Unique identifier for this mail session instance. Its a user-defined value.
     */
    val id: String

    /**
     * Jakarta Mail [Session] currently associated with this mail session instance.
     */
    val currentSession: Session

    /**
     * Underlying [IMAPStore] used for mailbox interactions.
     */
    val currentStore: IMAPStore

    /**
     * Whether the underlying store is currently connected.
     */
    val isConnected: Boolean

    /**
     * Connect to the mail store using the provided authentication method and credentials.
     *
     * @return a [CompletableFuture] that completes with the resulting [MailConnection]
     */
    fun connect(method: MailAuthMethod, username: String, password: String): CompletableFuture<MailConnection>

    /**
     * Disconnect the active store/session and release underlying resources.
     */
    fun disconnect()
}

/**
 * Result of a mail connection attempt, carrying the created session/store or the encountered error.
 */
data class MailConnection(
    val session: Session? = null,
    val store: IMAPStore? = null,
    val error: Throwable? = null
) {
    /**
     * Convenience flag indicating whether both [session] and [store] are present and no [error] occurred.
     */
    val success get() = error == null && session != null && store != null
}