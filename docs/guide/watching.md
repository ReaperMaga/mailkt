# Watching

Watchers turn IMAP IDLE notifications into a `Flow` of new mail.

```kotlin
fun watchEnvelopes(folder: FolderPath, query: MessageQuery = MessageQuery.ALL, from: WatchCheckpoint? = null): Flow<WatchedEnvelope>
fun watch(folder: FolderPath, query: MessageQuery = MessageQuery.ALL, from: WatchCheckpoint? = null, maxBytesPerMessage: Long = DEFAULT_MAX_BYTES): Flow<WatchedMessage>
```

## How it works

- For every live notification, the watcher fetches and filters the **envelope** first, before requesting anything else.
- With a `WatchCheckpoint`, it first **catches up** on everything after that checkpoint, then goes live. It does the same after reconnects.
- Duplicates are suppressed within one collection. Across restarts, delivery is **at-least-once**.
- If UIDVALIDITY changed, the folder is rescanned rather than risking loss.

Each emitted item carries the `next` checkpoint to persist once you have durably processed it.

## Envelopes

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Watching.kt#watch-envelopes{kotlin}

Combine with `structure` and `download` exactly like in [selective download](./reading#selective-download) to fetch only the parts you care about.

## Full messages

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Watching.kt#watch-messages{kotlin}

## Making side effects safe

Because delivery is at-least-once, make your side effects idempotent:

1. De-duplicate by **account and Message-ID** (for example a unique index on `(mailbox_key, message_id)`).
2. Commit your side effect and the checkpoint `watched.next` in the same transaction where possible.
3. On restart, pass the last committed checkpoint as `from`.

::: tip Long-running watchers
Collect watchers in a scope you control, such as an application-level `SupervisorJob`. Cancelling the collecting coroutine stops the watcher; `mailbox.close()` stops all of them. A watcher restart after recovery is reported as `MailboxEvent.WatcherRestarted` on `mailbox.events`.
:::

## In a real app

A worker that turns every new mail in a shared support inbox into a ticket. The ticket and the checkpoint are written in one transaction, and a unique constraint on `(mailbox, message_id)` makes redelivery after a crash harmless. The [lifecycle service](./lifecycle#in-a-real-app) starts it after `connect`.

::: info Your types
Types such as `AccountRepository` or `Database` below stand for your own application code. Everything else is MailKT API.
:::

```kotlin
class SupportInboxWorker(
    private val db: Database,              // your persistence
    private val tickets: TicketRepository, // your persistence
    private val scope: CoroutineScope,
) {
    fun start(mailbox: Mailbox, inbox: FolderPath): Job = scope.launch {
        val from: WatchCheckpoint? = db.read { tickets.watchCheckpoint(mailbox.id) }
        try {
            mailbox.messages.watch(inbox, from = from, maxBytesPerMessage = 5L * 1024 * 1024).collect { watched ->
                val envelope = watched.message.envelope
                db.transaction {
                    tickets.createIfAbsent(
                        mailbox = mailbox.id,
                        messageId = envelope.messageId ?: "uid:${envelope.location.uidValidity}:${envelope.location.uid}",
                        customer = envelope.from.firstOrNull()?.address?.value,
                        subject = envelope.subject,
                        body = watched.message.plainText ?: watched.message.html,
                    )
                    tickets.saveWatchCheckpoint(mailbox.id, watched.next)
                }
            }
        } catch (e: MailException) {
            logger.warn("Support watcher stopped: {}", e.javaClass.simpleName) // restart from the saved checkpoint
        }
    }
}
```

Because the checkpoint only moves after the transaction commits, killing the process at any point loses nothing: on restart the watcher catches up from the last committed position.
