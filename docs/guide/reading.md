# Reading mail

Reading in MailKT is a pipeline. Each stage is optional, and later stages only run for the survivors of earlier ones.

<div class="pipeline">
  <div class="stage focus"><b>Envelope</b><span>Addresses, subject, dates, flags, size, Message-ID relations. <code>MessageEnvelope</code></span></div>
  <div class="arrow">→</div>
  <div class="stage"><b>Structure</b><span>MIME tree: types, file names, sizes, part refs. No bytes. <code>MessageStructure</code></span></div>
  <div class="arrow">→</div>
  <div class="stage"><b>Part</b><span>Decoded bytes of one part, within a byte limit. <code>DownloadedPart</code></span></div>
</div>

A message rejected by sender or subject costs no structure or content fetch. A message whose attachments are rejected by name or type costs no attachment download. `MailMessage`, the fully decoded message, is available when you really want everything.

## What each stage contains

| Model | Contains | Never contains |
|---|---|---|
| `MessageEnvelope` | Location (folder, UIDVALIDITY, UID), Message-ID, In-Reply-To, References, from/to/cc/reply-to, subject, sent/received dates, flags, content type, advertised size, selected headers, thread ID. | Body, attachment bytes. |
| `MessageStructure` | A tree of `MessagePartDescriptor`s: media type, disposition, file name, content ID, transfer encoding, advertised size, stable `MessagePartRef`. Helpers `parts`, `attachments`, `textParts`. | Bytes. |
| `DownloadedPart` | The descriptor plus decoded `ByteContent`. | Anything beyond `maxBytes`. |
| `MailMessage` | Envelope, decoded `MimeContent` tree, `attachments`, and `plainText` / `html` helpers. | |

## Selecting messages

Every read takes a `MessageSelection`:

```kotlin
data class MessageSelection(
    val folder: FolderPath,
    val query: MessageQuery = MessageQuery.ALL,
    val range: MessageRange = MessageRange.All,
    val newestFirst: Boolean = false,
    val batchSize: Int = 50,            // 1..1000
    val checkpoint: ScanCheckpoint? = null,
)
```

- `MessageQuery` holds only predicates that can be evaluated from an envelope: dates, addresses, subject, seen/flagged, keywords, header equality, thread IDs and size. Filtering on body or attachment content necessarily happens after download, in your code.
- `MessageRange` fixes the membership of a scan when it starts: `All`, `Dates(from, before)` or `Positions(first, last, fromNewest)`. `Positions(1, 100, fromNewest = true)` is the 100 most recent messages regardless of folder size.

See [Models](/reference/models#messagequery) for every field.

## Paging

`page` returns one page of envelopes and a `next` checkpoint. Pages **freeze their membership**: mail arriving later never shifts a resumed scan. A sparse or heavily filtered page can be empty and still carry a continuation.

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Reading.kt#paging{kotlin}

## Streaming history

`envelopes` streams a frozen snapshot as a cold `Flow`, batching fetches behind the scenes:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Reading.kt#history{kotlin}

## Selective download

The same pipeline serves historical scans and live arrivals, and MailKT needs to know nothing about your rules:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Reading.kt#ingestion{kotlin}

`download` **requires** an explicit `maxBytes`. Exceeding it throws `MailException.LimitExceeded`; content is never silently truncated.

## Full messages

For simple cases, `messages` streams complete `MailMessage`s and still filters envelopes before fetching any content:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Reading.kt#full-messages{kotlin}

`get(location)` fetches a single complete message. Both default to a 25 MiB limit per message (`Messages.DEFAULT_MAX_BYTES`).

## Integrity

A `MessagePartRef` carries the mailbox, folder, UIDVALIDITY, UID and MIME section, and only works with the mailbox that produced it. If between selection and download the message was expunged, UIDVALIDITY changed, or the part changed, you get a typed failure. Another message or part is **never** substituted.

| Situation | Exception |
|---|---|
| Message expunged or moved | `MailException.MessageUnavailable` |
| UIDVALIDITY changed, part changed | `MailException.IntegrityViolation(kind)` |
| Larger than the limit | `MailException.LimitExceeded` |

## In a real app

An invoice importer that backfills a mailbox once and can be stopped and resumed at any time. It filters by sender on the server, skips messages it has already imported, downloads only PDF attachments, and stores its scan position after each page.

::: info Your types
Types such as `AccountRepository` or `Database` below stand for your own application code. Everything else is MailKT API.
:::

```kotlin
class InvoiceImporter(
    private val invoices: InvoiceRepository,       // your persistence
    private val checkpoints: CheckpointRepository, // your persistence
) {
    private val maxPdfBytes = 15L * 1024 * 1024

    suspend fun backfill(mailbox: Mailbox, inbox: FolderPath, vendors: Set<MailAddress>, since: Instant) {
        val query = MessageQuery(from = vendors, receivedFrom = since)
        var checkpoint: ScanCheckpoint? = checkpoints.loadScan(mailbox.id, job = "invoices")

        do {
            val page = mailbox.messages.page(MessageSelection(inbox, query, checkpoint = checkpoint), limit = 100)
            page.envelopes.forEach { importOne(mailbox, it) }

            checkpoint = page.next
            checkpoints.saveScan(mailbox.id, job = "invoices", checkpoint) // null means finished
        } while (checkpoint != null)
    }

    private suspend fun importOne(mailbox: Mailbox, envelope: MessageEnvelope) {
        val messageId = envelope.messageId ?: return
        if (invoices.exists(mailbox.id, messageId)) return // already imported in an earlier run

        val structure = try {
            mailbox.messages.structure(envelope.location)
        } catch (e: MailException.MessageUnavailable) {
            return // deleted in the meantime
        }

        structure.attachments
            .filter { it.isPdf && (it.advertisedSize ?: 0) <= maxPdfBytes }
            .forEach { part ->
                val pdf = mailbox.messages.download(part.ref, maxBytes = maxPdfBytes)
                invoices.save(
                    mailbox = mailbox.id,
                    messageId = messageId,
                    fileName = part.fileName ?: "invoice.pdf",
                    receivedAt = envelope.receivedAt,
                    bytes = pdf.content.toByteArray(),
                )
            }
    }
}
```

Messages from other senders never leave the server, and a message with a 40 MB video attachment costs one structure fetch, not a 40 MB download.
