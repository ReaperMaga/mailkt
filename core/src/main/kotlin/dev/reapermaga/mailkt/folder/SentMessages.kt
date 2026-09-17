package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailSession
import dev.reapermaga.mailkt.session.ManagedMailSession
import jakarta.mail.Flags
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Optionally saves an accepted outgoing message to a caller-selected Sent folder over IMAP.
 * This is independent of SMTP submission: an append failure must NEVER trigger another send.
 * Only use when the provider does not already save Sent copies. APPEND is not idempotent;
 * after an uncertain failure, reconcile by Message-ID before attempting another append.
 * Retain the accepted outgoing message in application persistence regardless of append outcome.
 */
suspend fun appendSentMessage(session: MailSession, folderName: String, message: MimeMessage) {
    require(folderName.isNotBlank())
    require(message.messageID != null) { "Prepare the message before appending" }
    runInterruptible(Dispatchers.IO) {
        val connection = checkNotNull(session.currentConnection) { "Mail session is not connected" }
        val copy = MimeMessage(message)
        copy.setFlags(Flags(Flags.Flag.SEEN), true)
        connection.store.getFolder(folderName).appendMessages(arrayOf(copy))
    }
}

suspend fun appendSentMessage(session: ManagedMailSession, folderName: String, message: MimeMessage) =
    appendSentMessage(session.session, folderName, message)
