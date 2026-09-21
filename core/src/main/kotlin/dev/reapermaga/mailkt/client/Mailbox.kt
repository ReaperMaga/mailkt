package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailboxEvent
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.MailboxState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/** Single lifecycle handle for one account. */
interface Mailbox {
    val id: MailboxId
    val email: MailAddress
    val state: StateFlow<MailboxState>
    val events: Flow<MailboxEvent>

    val folders: Folders
    val messages: Messages
    val conversations: Conversations
    val outbox: Outbox?

    /** Resume connection management after reauthorization or from a recoverable Failed state. */
    suspend fun reconnect()

    /** Idempotent; publishes [MailboxState.Closed] exactly once. */
    suspend fun close()
}

/** Runs [block] and always closes the mailbox afterwards, even on cancellation. */
suspend inline fun <T> Mailbox.use(block: suspend (Mailbox) -> T): T {
    try {
        return block(this)
    } finally {
        withContext(NonCancellable) { close() }
    }
}
