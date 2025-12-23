package dev.reapermaga.mailkt.provider

import com.sun.mail.imap.IMAPStore
import jakarta.mail.Session
import java.util.concurrent.CompletableFuture

interface MailSession {

    val currentSession: Session

    val currentStore: IMAPStore

    val isConnected: Boolean

    fun connect(method: MailAuthMethod, username: String, password: String): CompletableFuture<MailConnection>
}

data class MailConnection(
    val session: Session? = null,
    val store: IMAPStore? = null,
    val error: Throwable? = null
)