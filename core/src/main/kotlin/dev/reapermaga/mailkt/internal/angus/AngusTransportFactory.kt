package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.client.AccessTokenSource
import dev.reapermaga.mailkt.client.ImapEndpoint
import dev.reapermaga.mailkt.internal.transport.*
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailboxId
import jakarta.mail.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPStore

/** Builds authenticated IMAP (and optional SMTP) connections using a silently refreshed token. */
internal class AngusTransportFactory(
    private val id: MailboxId,
    private val email: MailAddress,
    private val endpoint: ImapEndpoint,
    private val tokens: AccessTokenSource,
) : TransportFactory {

    val hasSmtp: Boolean get() = endpoint.smtpHost != null

    override suspend fun connect(): TransportConnection {
        val token = tokens.accessToken()
        val session = Session.getInstance(AngusProperties.imap(endpoint))
        val store = session.getStore("imap") as IMAPStore
        try {
            runInterruptible(Dispatchers.IO) { store.connect(endpoint.imapHost, endpoint.imapPort, email.value, token) }
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { runInterruptible(Dispatchers.IO) { store.close() } } }
            if (e is CancellationException) throw e
            throw e
        }
        val smtp = if (hasSmtp) AngusSmtp(email, endpoint, tokens) else null
        return AngusConnection(AngusImapSession(store, session, id), smtp)
    }
}

private class AngusConnection(override val imap: ImapSessionPort, override val smtp: SmtpPort?) : TransportConnection {
    override suspend fun close() {
        withContext(NonCancellable) {
            runCatching { smtp?.close() }
            imap.close()
        }
    }
}
