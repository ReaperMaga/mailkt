package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.internal.angus.AngusMimeCodec
import dev.reapermaga.mailkt.internal.angus.AngusTransportFactory
import dev.reapermaga.mailkt.internal.application.ManagedMailbox
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailboxId

/**
 * Default [MailboxConnector] used by the provider clients: builds the IMAP/SMTP transport, connects
 * once (typed failure, no partially initialized mailbox) and returns the managed [Mailbox].
 */
object DefaultMailboxConnector : MailboxConnector {
    override suspend fun connect(
        id: MailboxId,
        email: MailAddress,
        endpoint: ImapEndpoint,
        tokens: AccessTokenSource,
        options: MailboxOptions,
    ): Mailbox {
        val factory = AngusTransportFactory(id, email, endpoint, tokens)
        return ManagedMailbox.open(id, email, factory, AngusMimeCodec, options, factory.hasSmtp)
    }
}
