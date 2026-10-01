package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.model.FolderPath
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MessageQuery
import dev.reapermaga.mailkt.model.WatchCheckpoint

// region watch-envelopes
/** Live arrivals (IMAP IDLE) with catch-up. Delivery is at-least-once: de-duplicate by Message-ID. */
suspend fun watchInbox(mailbox: Mailbox, inbox: FolderPath, saved: WatchCheckpoint?) {
    val query = MessageQuery(from = setOf(MailAddress("billing@example.com")))
    mailbox.messages.watchEnvelopes(inbox, query, from = saved).collect { watched ->
        println("New mail UID ${watched.envelope.location.uid}")
        // Persist watched.next only once your processing of this envelope is durable.
    }
}
// endregion

// region watch-messages
suspend fun watchFullMessages(mailbox: Mailbox, inbox: FolderPath) {
    mailbox.messages.watch(inbox).collect { watched ->
        println("Received ${watched.message.attachments.size} attachments")
    }
}
// endregion
