package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailboxId

/** Supplies a current access token via silent refresh only. Throws `MailException.AuthenticationRequired` if impossible. */
fun interface AccessTokenSource {
    suspend fun accessToken(): String
}

/** IMAP/SMTP server endpoints of a provider (implicit TLS unless [smtpStartTls]). */
data class ImapEndpoint(
    val imapHost: String,
    val imapPort: Int = 993,
    val smtpHost: String? = null,
    val smtpPort: Int = 465,
    val smtpStartTls: Boolean = false,
)

/** Per-mailbox options; nothing here is shared or global. */
data class MailboxOptions(
    val connectionPolicy: ConnectionPolicy = ConnectionPolicy(),
    val outboxEnabled: Boolean = true,
    val registry: MailboxRegistry? = null,
)

/**
 * Bridge from provider clients (gmail/outlook) to the managed mailbox implementation in core.
 * Providers authenticate; the connector builds the transport, [Mailbox] and its lifecycle.
 */
fun interface MailboxConnector {
    suspend fun connect(
        id: MailboxId,
        email: MailAddress,
        endpoint: ImapEndpoint,
        tokens: AccessTokenSource,
        options: MailboxOptions,
    ): Mailbox
}
