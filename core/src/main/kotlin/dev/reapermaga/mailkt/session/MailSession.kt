package dev.reapermaga.mailkt.session

import com.sun.mail.imap.IMAPStore
import dev.reapermaga.mailkt.auth.MailAuthMethod
import jakarta.mail.Session
import java.util.concurrent.CompletableFuture

interface MailSession {

    val currentSession: Session

    val currentStore: IMAPStore

    val isConnected: Boolean

    fun connect(method: MailAuthMethod, username: String, password: String): CompletableFuture<MailConnection>

    fun disconnect()
}

data class MailConnection(
    val session: Session? = null,
    val store: IMAPStore? = null,
    val error: Throwable? = null
) {
    val success get() = error == null && session != null && store != null
}