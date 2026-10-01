# Composing and sending

`mailbox.outbox` composes, replies and submits mail over SMTP. It is `null` when no SMTP endpoint is configured, for example for Outlook without `enableSending`, or when `MailboxOptions.outboxEnabled` is `false`.

```kotlin
interface Outbox {
    fun newDraft(): Draft
    fun reply(original: MessageEnvelope, replyAll: Boolean = false, text: String? = null): Draft
    suspend fun send(draft: Draft, saveToSent: Boolean = true): SendResult
}
```

## Composing

Drafts are immutable data classes; build them with `copy`. The Message-ID is assigned when the draft is created, so you can persist it **before** submission.

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Sending.kt#compose{kotlin}

| `Draft` field | Description |
|---|---|
| `from` | Set from the mailbox identity by `newDraft()`. |
| `to`, `cc`, `bcc` | `List<MailParticipant>`. |
| `subject`, `text`, `html` | Content. Provide `text`, `html` or both. |
| `attachments` | `MailAttachment(fileName, mediaType, content, contentId, inline)`. Use `inline = true` with a `contentId` for embedded images. |
| `inReplyTo`, `references` | Threading headers. Set by `reply`. |
| `messageId` | Assigned on creation. |

## Replying

`reply` derives recipients, subject and threading headers from an envelope. `Reply-To` is respected, reply-all excludes your own address, and Bcc is never exposed.

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Sending.kt#reply{kotlin}

## Send outcomes

`send` returns an explicit outcome and **never retries or resubmits**, also not across reconnects.

| Outcome | Meaning |
|---|---|
| `SendResult.Accepted` | The SMTP service accepted the submission. This is not proof of delivery. |
| `SendResult.Failed` | Definitely not sent. Safe to fix and retry. |
| `SendResult.Unknown` | The connection failed after `DATA` started. The message may or may not have been sent. |

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Sending.kt#send-results{kotlin}

::: danger Never resend an UNKNOWN blindly
Look for `result.messageId` in the Sent folder first. This is why the Message-ID exists before submission: persist it with your outgoing record, then reconcile.
:::

## Sent folder behavior

With `saveToSent = true`, MailKT appends a copy of an accepted message to the Sent folder found by its special-use attribute, **but only** for providers whose SMTP service does not store a copy itself. Gmail and Outlook do, so nothing is appended for them.

The append is best effort, is not idempotent and can never trigger another submission. Keep the accepted message in your own persistence regardless. To store drafts or arbitrary messages explicitly, use [`folders.append`](./folders#appending).

## In a real app

A mailer that sends invoices without ever sending one twice. It records the Message-ID before submission, stores the explicit outcome, and a periodic job resolves `UNKNOWN` outcomes by looking for that Message-ID in the Sent folder.

::: info Your types
Types such as `AccountRepository` or `Database` below stand for your own application code. Everything else is MailKT API.
:::

```kotlin
class InvoiceMailer(private val outgoing: OutgoingMailRepository) { // your persistence

    suspend fun sendInvoice(mailbox: Mailbox, customer: Customer, invoice: Invoice) {
        val outbox = mailbox.outbox ?: throw MailException.OutboxUnavailable()
        val draft = outbox.newDraft().copy(
            to = listOf(MailParticipant(MailAddress(customer.email), customer.name)),
            subject = "Invoice ${invoice.number}",
            text = "Hello ${customer.name},\n\nplease find invoice ${invoice.number} attached.",
            attachments = listOf(
                MailAttachment("invoice-${invoice.number}.pdf", "application/pdf", ByteContent(invoice.pdf)),
            ),
        )
        val messageId = checkNotNull(draft.messageId)

        // 1. Durable BEFORE submission, so a crash mid-send can still be reconciled.
        outgoing.insertPending(messageId, invoiceId = invoice.id, createdAt = Instant.now())

        // 2. Exactly one submission; record whatever happened.
        when (val result = outbox.send(draft)) {
            is SendResult.Accepted -> outgoing.markAccepted(messageId)
            is SendResult.Failed -> outgoing.markFailed(messageId, reason = result.cause.javaClass.simpleName)
            is SendResult.Unknown -> outgoing.markUnknown(messageId)
        }
    }

    /** Scheduled job: resolve UNKNOWN outcomes instead of resending blindly. */
    suspend fun reconcile(mailbox: Mailbox) {
        val sent = mailbox.folders.special(SpecialUse.SENT)?.path ?: return
        for (pending in outgoing.unknown()) {
            val selection = MessageSelection(
                sent,
                MessageQuery(sentFrom = pending.createdAt.minus(Duration.ofMinutes(5))),
                newestFirst = true,
            )
            val found = mailbox.messages.envelopes(selection).firstOrNull { it.messageId == pending.messageId }
            when {
                found != null -> outgoing.markAccepted(pending.messageId)
                pending.createdAt.isBefore(Instant.now().minus(Duration.ofHours(1))) ->
                    outgoing.markFailed(pending.messageId, reason = "not found in Sent") // now safe to resend
            }
        }
    }
}
```

Only a definite `FAILED`, or an `UNKNOWN` you have reconciled as not sent, is ever retried, and only by your code.
