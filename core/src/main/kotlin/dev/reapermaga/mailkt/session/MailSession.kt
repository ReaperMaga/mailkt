package dev.reapermaga.mailkt.session

import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPStore

/**
 * Abstraction over a mail session capable of exposing its current state and performing
 * connect/disconnect operations.
 */
interface MailSession {

    /** Unique identifier for this mail session instance. Its a user-defined value. */
    val id: String

    /** Whether the underlying store is currently connected. */
    val isConnected: Boolean

    /** Current connection snapshot, or null before connection/after disconnection. */
    val currentConnection: MailConnection?

    /**
     * Connects to the mail store and returns the live connection. Failures are thrown.
     */
    suspend fun connect(credentials: MailCredentials): MailConnection

    /** Disconnect the active store/session and release underlying resources. */
    suspend fun disconnect()
}

/** Immutable snapshot of a successful mail connection. */
data class MailConnection(
    val session: Session,
    val store: IMAPStore,
)

/** Credentials used to authenticate an IMAP session. */
data class MailCredentials(
    val method: MailAuthMethod,
    val username: String,
    val secret: String,
) {
    init {
        require(username.isNotBlank()) { "username must not be blank" }
        require(secret.isNotBlank()) { "secret must not be blank" }
    }

    companion object {
        fun oauth2(username: String, accessToken: String) =
            MailCredentials(MailAuthMethod.OAUTH2, username, accessToken)
    }

    override fun toString(): String =
        "MailCredentials(method=$method, username=$username, secret=<redacted>)"
}

class MailConnectionException(message: String, cause: Throwable) : RuntimeException(message, cause)
