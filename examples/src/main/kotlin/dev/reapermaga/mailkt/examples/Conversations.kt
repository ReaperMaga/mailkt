package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.model.ConversationCheckpoint
import dev.reapermaga.mailkt.model.FolderPath
import dev.reapermaga.mailkt.model.MessageLocation

// region conversations-sync
suspend fun syncConversations(mailbox: Mailbox, inbox: FolderPath, saved: ConversationCheckpoint?): ConversationCheckpoint {
    val sync = mailbox.conversations.synchronize(inbox, from = saved)
    if (sync.reset) println("UIDVALIDITY changed: discard cached conversations")
    sync.changed.forEach { println("Conversation with ${it.messages.size} messages, truncated=${it.truncated}") }
    return sync.next // persist after applying `changed`
}
// endregion

// region conversation-of
suspend fun readConversation(mailbox: Mailbox, location: MessageLocation) {
    val conversation = mailbox.conversations.of(location, maxMessages = 100)
    conversation.messages.forEach { println("Message ${it.location.uid}") }
    // Fetch bodies only for the messages you need:
    val newest = conversation.messages.last()
    val full = mailbox.messages.get(newest.location)
    println("Newest has ${full.attachments.size} attachments")
}
// endregion
