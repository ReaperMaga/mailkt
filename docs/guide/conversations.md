# Conversations

`mailbox.conversations` groups messages into threads.

```kotlin
interface Conversations {
    suspend fun of(location: MessageLocation, maxMessages: Int = 200): Conversation
    suspend fun synchronize(
        folder: FolderPath,
        from: ConversationCheckpoint? = null,
        maxMessages: Int = 500,
        query: MessageQuery = MessageQuery.ALL,
    ): ConversationSync
    fun assemble(envelopes: List<MessageEnvelope>): List<Conversation>
}
```

## Grouping rules

- Conversations are grouped by **explicit relationships only**: `Message-ID`, `In-Reply-To` and `References`. Never by subject or domain.
- Copies of one message in several folders are merged.
- Messages without a Message-ID stay separate.
- `Conversations` works on envelopes. Fetch bodies with `messages.get` only for the messages you need.

## One conversation

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Conversations.kt#conversation-of{kotlin}

## Incremental synchronization

`synchronize` is incremental per folder. It returns the conversations that changed since the checkpoint, the next checkpoint, and whether the caller must discard cached state because UIDVALIDITY changed. Results that hit `maxMessages` are marked `truncated`: call again with the returned checkpoint until nothing is truncated.

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Conversations.kt#conversations-sync{kotlin}

::: tip Narrow server-side
If you only care about one correspondent, pass a `query` such as `MessageQuery(from = setOf(address))`. It narrows membership on the server before any envelope is fetched, whereas filtering the result afterwards still requires fetching every envelope in the window.
:::

## Offline assembly

`assemble` groups envelopes you already have, without contacting the server. Useful when you cache envelopes yourself.

## The Conversation model

| Field | Description |
|---|---|
| `id` | Stable conversation identifier. |
| `subject` | Subject of the conversation, if any. |
| `messages` | Envelopes in the conversation. |
| `participants` | All addresses involved. |
| `lastActivity` | Most recent message date. |
| `truncated` | `true` if the size limit was reached. |

## In a real app

A helpdesk that shows threads instead of single mails keeps a local thread table up to date and fetches bodies only when a thread is opened.

::: info Your types
Types such as `AccountRepository` or `Database` below stand for your own application code. Everything else is MailKT API.
:::

```kotlin
class ThreadSync(private val threads: ThreadRepository) { // your persistence

    /** Run periodically, or after the watcher reported new mail. */
    suspend fun refresh(mailbox: Mailbox, inbox: FolderPath) {
        var checkpoint = threads.checkpoint(mailbox.id)
        do {
            val sync = mailbox.conversations.synchronize(inbox, from = checkpoint)
            threads.transaction {
                if (sync.reset) deleteAll(mailbox.id) // UIDVALIDITY changed: cached locations are invalid
                sync.changed.forEach { c ->
                    upsert(
                        mailbox = mailbox.id,
                        threadId = c.id,
                        subject = c.subject,
                        participants = c.participants.map { it.value },
                        lastActivity = c.lastActivity,
                        newest = c.messages.last().location,
                    )
                }
                saveCheckpoint(mailbox.id, sync.next)
            }
            checkpoint = sync.next
        } while (sync.changed.any { it.truncated })
    }

    /** API endpoint: the full thread when a user opens it. */
    suspend fun open(mailbox: Mailbox, anyMessage: MessageLocation): List<ThreadMessageDto> {
        val conversation = mailbox.conversations.of(anyMessage, maxMessages = 100)
        return conversation.messages.map { envelope ->
            val message = mailbox.messages.get(envelope.location, maxBytes = 2L * 1024 * 1024)
            ThreadMessageDto(
                from = envelope.from.firstOrNull()?.displayName,
                sentAt = envelope.sentAt,
                text = message.plainText,
                attachments = message.attachments.map { it.fileName },
            )
        }
    }
}
```
