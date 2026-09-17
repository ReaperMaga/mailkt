package dev.reapermaga.mailkt.gmail

import dev.reapermaga.mailkt.session.ImapMailSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.UUID

/** Coroutine-aware OAuth2 IMAP session for Gmail. */
class GmailMailSession(
    id: String = UUID.randomUUID().toString(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImapMailSession(host = HOST, ioDispatcher = ioDispatcher, id = id,
    smtpConfig = dev.reapermaga.mailkt.message.SmtpConfig("smtp.gmail.com")) {
    companion object {
        const val HOST = "imap.gmail.com"
    }
}
