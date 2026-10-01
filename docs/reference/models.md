# Models

All models live in `dev.reapermaga.mailkt.model` and are immutable. `toString()` is redacted for anything that could contain personal data.

## Identity

### MailAddress

```kotlin
@JvmInline value class MailAddress(val value: String) {
    val normalized: String // trimmed, lower-case
}
```

Validated on construction (non-blank, contains `@`, no whitespace). Compare via `normalized`. `toString()` prints `MailAddress(***)`.

### MailParticipant

```kotlin
data class MailParticipant(val address: MailAddress, val displayName: String? = null)
```

### MailboxId

```kotlin
data class MailboxId(val provider: String, val canonicalAddress: String) {
    val storageKey: String // "provider:address", contains the address: never log it
    companion object { fun of(provider: String, email: MailAddress): MailboxId }
}
```

### TokenKey

```kotlin
data class TokenKey(val provider: String, val clientRegistration: String, val mailboxId: MailboxId) {
    val storageKey: String // "provider/client/provider:address"
}
```

## Locations

### FolderPath, FolderInfo, SpecialUse

```kotlin
@JvmInline value class FolderPath(val value: String)

enum class SpecialUse { INBOX, SENT, DRAFTS, TRASH, JUNK, ARCHIVE, ALL, FLAGGED }

data class FolderInfo(
    val path: FolderPath,
    val name: String,
    val specialUse: SpecialUse? = null,
    val selectable: Boolean = true,
    val messageCount: Int? = null,
    val unseenCount: Int? = null,
)
```

### MessageLocation

```kotlin
data class MessageLocation(
    val mailboxId: MailboxId,
    val folder: FolderPath,
    val uidValidity: Long,
    val uid: Long,
)
```

Identity of one message inside a mailbox. Only valid for the mailbox that produced it.

### MessageFlags

```kotlin
data class MessageFlags(
    val seen: Boolean = false,
    val answered: Boolean = false,
    val flagged: Boolean = false,
    val deleted: Boolean = false,
    val draft: Boolean = false,
    val keywords: Set<String> = emptySet(),
)
```

## Envelopes and selection

### MessageEnvelope

| Field | Type | Description |
|---|---|---|
| `location` | `MessageLocation` | Folder, UIDVALIDITY, UID. |
| `messageId` | `String?` | `Message-ID` header. |
| `inReplyTo` | `String?` | `In-Reply-To` header. |
| `references` | `List<String>` | `References` header. |
| `from`, `to`, `cc`, `replyTo` | `List<MailParticipant>` | Addresses. |
| `subject` | `String?` | Decoded subject. |
| `sentAt`, `receivedAt` | `Instant?` | `Date` header and internal date. |
| `flags` | `MessageFlags` | IMAP flags. |
| `contentType` | `String?` | Top-level media type. |
| `advertisedSize` | `Long?` | RFC822 size reported by the server. |
| `headers` | `Map<String, List<String>>` | Selected headers. |
| `threadId` | `String?` | Provider thread ID where available. |

### MessageQuery

Envelope-only predicates. All set fields must match.

| Field | Type | Matches |
|---|---|---|
| `folder` | `FolderPath?` | Folder. |
| `receivedFrom`, `receivedBefore` | `Instant?` | Internal date range. |
| `sentFrom`, `sentBefore` | `Instant?` | `Date` header range. |
| `from`, `to` | `Set<MailAddress>` | Any of the addresses. |
| `subjectContains` | `String?` | Subject substring. |
| `seen`, `flagged` | `Boolean?` | Flag state. |
| `requiredFlags` | `Set<String>` | Keywords that must be present. |
| `headerEquals` | `Map<String, String>` | Header values. |
| `threadIds` | `Set<String>` | Provider thread IDs. |
| `minSize`, `maxSize` | `Long?` | Advertised size bounds. |

`MessageQuery.ALL` matches everything.

### MessageRange

```kotlin
sealed interface MessageRange {
    data object All : MessageRange
    data class Positions(val first: Int, val last: Int, val fromNewest: Boolean = false) : MessageRange
    data class Dates(val from: Instant?, val before: Instant?) : MessageRange
}
```

`Positions` is 1-based and inclusive. By default 1 is the oldest message; with `fromNewest = true` it counts from the newest.

### MessageSelection

```kotlin
data class MessageSelection(
    val folder: FolderPath,
    val query: MessageQuery = MessageQuery.ALL,
    val range: MessageRange = MessageRange.All,
    val newestFirst: Boolean = false,
    val batchSize: Int = 50,             // 1..1000
    val checkpoint: ScanCheckpoint? = null,
)
```

## Structure and content

### MessageStructure and MessagePartDescriptor

```kotlin
data class MessageStructure(val location: MessageLocation, val root: MessagePartDescriptor) {
    val parts: List<MessagePartDescriptor>       // depth-first
    val attachments: List<MessagePartDescriptor>
    val textParts: List<MessagePartDescriptor>
}

data class MessagePartDescriptor(
    val ref: MessagePartRef,
    val mediaType: String,
    val disposition: PartDisposition = PartDisposition.NONE, // INLINE, ATTACHMENT, NONE
    val fileName: String? = null,
    val contentId: String? = null,
    val transferEncoding: String? = null,
    val advertisedSize: Long? = null,
    val children: List<MessagePartDescriptor> = emptyList(),
) {
    val isMultipart: Boolean
    val isPdf: Boolean
    val isAttachment: Boolean
}

data class MessagePartRef(val location: MessageLocation, val section: String)
```

### Content

```kotlin
class ByteContent(bytes: ByteArray) { val size: Int; fun toByteArray(): ByteArray }

data class DownloadedPart(val descriptor: MessagePartDescriptor, val content: ByteContent)

sealed interface MimeContent {
    data class Text(val mediaType: String, val text: String, val charset: String = "UTF-8")
    data class Multipart(val mediaType: String, val parts: List<MimeContent>)
    data class Binary(val mediaType: String, val ref: MessagePartRef?, val fileName: String?,
                      val contentId: String?, val disposition: PartDisposition, val content: ByteContent)
    data class Embedded(val mediaType: String, val message: MimeContent)
}

data class MailMessage(val envelope: MessageEnvelope, val content: MimeContent, val attachments: List<MailAttachment>) {
    val location: MessageLocation
    val plainText: String?
    val html: String?
}
```

`ByteContent` copies defensively on the way in and out, so it is truly immutable.

## Checkpoints

| Type | Used by | Extra fields |
|---|---|---|
| `ScanCheckpoint` | `page`, `envelopes`, `messages` | `uidValidity`, `lastUid`, `snapshotMaxUid`, `newestFirst`, `processedCount` |
| `WatchCheckpoint` | `watchEnvelopes`, `watch` | `uidValidity`, `lastUid` |
| `ConversationCheckpoint` | `conversations.synchronize` | `uidValidity`, `lastUid` |

All implement `Checkpoint` with `formatVersion`, `mailboxKey` and `folder`. The current format is `CHECKPOINT_FORMAT_VERSION = 1`.

```kotlin
data class EnvelopePage(val envelopes: List<MessageEnvelope>, val next: ScanCheckpoint?, val scanned: Int)
```

## Conversations

```kotlin
data class Conversation(
    val id: String,
    val subject: String?,
    val messages: List<MessageEnvelope>,
    val participants: Set<MailAddress>,
    val lastActivity: Instant?,
    val truncated: Boolean = false,
)

data class ConversationSync(val changed: List<Conversation>, val next: ConversationCheckpoint, val reset: Boolean = false)
```

## Outbound

```kotlin
data class Draft(
    val from: MailParticipant,
    val to: List<MailParticipant> = emptyList(),
    val cc: List<MailParticipant> = emptyList(),
    val bcc: List<MailParticipant> = emptyList(),
    val subject: String = "",
    val text: String? = null,
    val html: String? = null,
    val attachments: List<MailAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val messageId: String? = null,
)

data class MailAttachment(
    val fileName: String,
    val mediaType: String,
    val content: ByteContent,
    val contentId: String? = null,
    val inline: Boolean = false,
    val ref: MessagePartRef? = null,
)

sealed interface SendResult {
    val messageId: String
    val status: SendStatus // ACCEPTED, FAILED, UNKNOWN
    data class Accepted(val messageId: String, val sentCopy: MessageLocation? = null)
    data class Failed(val messageId: String, val cause: MailException)
    data class Unknown(val messageId: String, val reason: RecoveryReason)
}
```

## State and events

```kotlin
sealed interface MailboxState {
    data object Connected
    data class Reconnecting(val attempt: Int, val reason: RecoveryReason)
    data object AuthenticationRequired
    data class Failed(val cause: MailException, val recoverable: Boolean)
    data object Closed
}

enum class RecoveryReason { NETWORK, SOCKET, TLS, TIMEOUT, STORE_CLOSED, FOLDER_CLOSED, HEALTH_CHECK, REQUESTED }

sealed interface MailboxEvent {
    val at: Instant
    data class StateChanged(val state: MailboxState, ...)
    data class Reconnected(val generation: Long, ...)
    data class RecoveryStarted(val reason: RecoveryReason, val generation: Long, ...)
    data class WatcherRestarted(val folder: FolderPath, ...)
    data class EventsDropped(val count: Long, ...)
}
```

## Authorization

```kotlin
data class AuthorizationRequest(val authorizationUrl: URI, val state: String, val expiresAt: Instant)
data class AuthorizationCallback(val code: String?, val state: String?, val error: String? = null)
data class PendingAuthorization(
    val state: String, val provider: String, val clientRegistration: String,
    val expectedEmail: MailAddress, val redirectUri: URI, val pkceVerifier: String,
    val createdAt: Instant, val expiresAt: Instant,
)
```
