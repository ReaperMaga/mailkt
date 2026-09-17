package dev.reapermaga.mailkt.outlook

import dev.reapermaga.mailkt.session.ImapMailSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.UUID

/** Coroutine-aware OAuth2 IMAP session for Outlook and Microsoft 365. */
class OutlookMailSession(
    id: String = UUID.randomUUID().toString(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImapMailSession(host = HOST, ioDispatcher = ioDispatcher, id = id,
    smtpConfig = dev.reapermaga.mailkt.message.SmtpConfig("smtp.office365.com")) {
    companion object {
        const val HOST = "outlook.office365.com"
    }
}
