package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.model.*
import java.time.Instant

// region folders
suspend fun discoverFolders(mailbox: Mailbox): FolderPath {
    mailbox.folders.list().forEach { println("${it.name} special=${it.specialUse}") }
    val sent = mailbox.folders.special(SpecialUse.SENT)
    println("Sent folder resolved: ${sent != null}")
    return checkNotNull(mailbox.folders.special(SpecialUse.INBOX)).path
}
// endregion

// region paging
suspend fun pageThroughInbox(mailbox: Mailbox, inbox: FolderPath): Int {
    var checkpoint: ScanCheckpoint? = null
    var seen = 0
    do {
        val page = mailbox.messages.page(
            MessageSelection(inbox, newestFirst = true, checkpoint = checkpoint),
            limit = 50,
        )
        seen += page.envelopes.size // a sparse page can be empty and still continue
        checkpoint = page.next // persist this only after the page is durably processed
    } while (checkpoint != null)
    return seen
}
// endregion

// region history
suspend fun streamHistory(mailbox: Mailbox, inbox: FolderPath, since: Instant) {
    val selection = MessageSelection(inbox, range = MessageRange.Dates(from = since, before = null))
    mailbox.messages.envelopes(selection).collect { envelope ->
        println("UID ${envelope.location.uid} size=${envelope.advertisedSize}")
    }
}
// endregion

// region ingestion
/** Envelope, then structure, then only the surviving parts: rejected mail costs no content download. */
suspend fun ingestInvoices(mailbox: Mailbox, inbox: FolderPath, senders: Set<String>): List<ByteArray> {
    val pdfs = mutableListOf<ByteArray>()
    val selection = MessageSelection(inbox, MessageQuery(from = senders.map { MailAddress(it) }.toSet()))
    mailbox.messages.envelopes(selection).collect { envelope ->
        val structure = mailbox.messages.structure(envelope.location)
        structure.attachments
            .filter { it.isPdf && it.fileName?.startsWith("invoice", ignoreCase = true) == true }
            .forEach { part ->
                pdfs += mailbox.messages.download(part.ref, maxBytes = 10L * 1024 * 1024).content.toByteArray()
            }
    }
    return pdfs
}
// endregion

// region full-messages
suspend fun readFullMessages(mailbox: Mailbox, inbox: FolderPath) {
    mailbox.messages.messages(MessageSelection(inbox, MessageQuery(seen = false))).collect { message ->
        println("Unread message with ${message.attachments.size} attachments; text length ${message.plainText?.length ?: 0}")
    }
}
// endregion
