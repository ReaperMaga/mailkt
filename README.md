# mailkt

Coroutine-first Kotlin/JVM helpers for email over IMAP, with ready-to-use Outlook (Microsoft 365)
and Gmail OAuth2 implementations.

Highlights:

- Suspending authentication, connection, folder, and lifecycle APIs.
- Eclipse Angus Mail with secure TLS defaults and cancellable blocking calls.
- Structured session management through `StateFlow` and `SharedFlow`.
- Cold `Flow` mailbox notifications with automatic IMAP IDLE cleanup.
- Atomic token-file writes and optional AES-GCM encryption.

## Modules

| Module      | Purpose                                                                   |
|-------------|---------------------------------------------------------------------------|
| `:core`     | Mail abstractions, session management, folders, and token persistence.    |
| `:outlook`  | MSAL OAuth2 and IMAP access through `outlook.office365.com`.              |
| `:gmail`    | Google installed-app OAuth2 and IMAP access through `imap.gmail.com`.     |
| `:examples` | Runnable coroutine-first provider and lifecycle examples.                 |

## Requirements

- JDK 21

## Installation

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "Averix"
        url = uri("https://repo.averix.tech/repository/maven-releases/")
    }
}

dependencies {
    implementation("dev.reapermaga.mailkt:core:0.1.0")
    implementation("dev.reapermaga.mailkt:outlook:0.1.0")
    implementation("dev.reapermaga.mailkt:gmail:0.1.0")
}
```

## Outlook

The public API uses suspending functions, so a command-line entry point can itself be `suspend`:

```kotlin
suspend fun main() {
    val oauth = OutlookOAuth2MailAuth(
        OutlookOAuth2Config.consumer(clientId),
        FileTokenPersistenceStorage(username),
    )
    val credentials = if (oauth.hasToken()) {
        oauth.login()
    } else {
        oauth.deviceLogin {
            println("Open ${it.verificationUri} and enter code ${it.code}")
        }
    }

    val session = OutlookMailSession()
    try {
        session.connect(
            MailCredentials.oauth2(credentials.username, credentials.accessToken)
        )

        val inbox = readMessages(session, "INBOX")
        try {
            println("Loaded ${inbox.messages.size} recent messages")
        } finally {
            inbox.close()
        }
    } finally {
        session.disconnect()
    }
}
```

## Gmail

Create a Google OAuth client with the Desktop app application type. First-time login opens a
browser and receives Google's redirect through a temporary loopback server; later calls refresh the
persisted credential.

```kotlin
val oauth = GmailOAuth2MailAuth(
    GmailOAuth2Config.installedApp(clientId, clientSecret),
    FileTokenPersistenceStorage("gmail"),
)
val credentials = oauth.login()

val session = GmailMailSession()
session.connect(MailCredentials.oauth2(credentials.username, credentials.accessToken))
```

Gmail IMAP access requires the `https://mail.google.com/` OAuth scope. Public applications using
this scope may need to complete Google's app verification process.

## Watching a folder

Folder notifications are a cold `Flow`. Cancelling collection unregisters the listener and stops
IMAP IDLE automatically. Messages are eagerly detached before emission, so MIME bodies and
attachments remain readable while downstream processing suspends or after the source folder closes.

```kotlin
watchFolder(session, "INBOX").collect { message ->
    println("New message: ${message.subject}")
}
```

When the session is owned by `MailSessionManager`, pass the managed handle to follow reconnects:

```kotlin
watchFolder(managedSession, "INBOX").collect { message ->
    processMessage(message)
}
```

The managed overload closes each obsolete folder/IDLE generation and reopens against the latest
connection. IMAP UID catch-up provides at-least-once delivery across reconnects and suppresses
duplicates within one collector where possible. Consumers with persistent side effects should
still de-duplicate by account/folder/`Message-ID`. The source-compatible `MailSession`
overload cannot observe store replacement and instead fails with a recoverable
`FolderWatchException` when its folder or store closes. A UIDVALIDITY change triggers a full folder
catch-up, deliberately preferring duplicate notifications over message loss.

## Streaming historical scans

Use `readMessagesFlow` with a `ManagedMailSession` for invoice scans or other historical reads.
The existing `readMessages(MailSession, ...)` API still returns live folder-backed messages and
requires closing its result.

```kotlin
// Before: a list of live messages; the caller owns the folder lifetime.
val batch = readMessages(managed.session, "INBOX", 1..1000)
try {
    batch.messages.forEach { processMessage(it) }
} finally {
    batch.close()
}

// After: each body and attachment is fully downloaded before delivery.
readMessagesFlow(managed, "INBOX", 1..1000).collect { item ->
    processMessage(item.message)
    // item.uid, item.uidValidity, item.receivedAt are server metadata.
}

// Inclusive server received dates; newest mailbox position first.
readMessagesFlow(
    managed,
    "INBOX",
    LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 14),
    HistoricalReadOptions(
        downloadTimeout = 30.seconds,
        recoveryTimeout = 120.seconds,
        maxRecoveryAttempts = 5,
        maxMessageBytes = 25L * 1024 * 1024,
    ),
).collect { item -> processMessage(item.message) }
```

Each collection fixes range membership as an ordered UID/UIDVALIDITY snapshot before downloading
bodies. UIDs are prefetched with `UIDFolder.FetchProfileItem.UID` in batches of at most 500 selected
messages before per-message UID access. Selection runs once, preserving newest-first order for
both position and date ranges. New arrivals cannot shift it during retries. Expunged requested
messages, changed UIDVALIDITY, and missing/nonpersistent UID support fail explicitly. If initial range resolution
is interrupted, the scan fails with `operation=resolve-range`; it cannot safely reconstruct a
position range whose original membership was never established.

A recoverable folder/store/socket failure during range resolution requests replacement of the
failed connection generation, even if the store still reports connected. Concurrent requests
coalesce and stale generations are ignored. The current scan still fails before emitting any
messages; neither position nor date selection is automatically retried. Subsequent operations can
use the recovered connection. Locally owned snapshot deadlines also request recovery and appear
as a `java.util.concurrent.TimeoutException` cause of `HistoricalReadException`. Parent cancellation
and caller-owned deadlines propagate directly without requesting recovery.

Angus 2.0.5 can convert an I/O error into a synthetic BYE response, then construct
`FolderClosedException` with only the response text. A TLS exception can therefore be absent from
the cause chain. Recovery uses the folder/store exception types, never message-text matching.
This handles the closed connection; it does not establish or fix the underlying TLS failure.

Downloads resume at the interrupted UID. Recoverable nested folder/store/socket errors are retried
against a usable managed connection. Store/socket failures and download timeouts request replacement
of the failed connection generation, even when the store still reports connected. Folder closures
allow two reopen retries on that generation before requesting replacement. Concurrent requests
coalesce; late requests for replaced generations are ignored. Each message has
its own download timeout and recovery budget (including waiting for a connection); successfully
progressing scans have no overall deadline. `maxRecoveryAttempts` counts retries after the initial
attempt. As with other cancellable blocking mail operations, prompt interruption depends on the
provider; configure finite IMAP socket read/connect timeouts for transports that ignore interrupts.

A successful collection delivers each selected UID once. Retrying a download never retries consumer
code. Emissions happen outside recovery and state-switching jobs, so suspended consumers and caller
buffers retain readable detached messages across reconnects. Cancellation or a consumer failure
ends the scan; it is not a durable acknowledgement protocol. Starting a new collection scans anew.
For durable processing, persist `(account, folder, uidValidity, uid)` after successful processing.
The received timestamp is in `item.receivedAt`; MIME headers alone cannot preserve IMAP INTERNALDATE.

The default flow has no body prefetch or output buffer and opens/closes a folder per snapshot/download attempt.
It retains O(selected UIDs) metadata and one message body at a time. Serialization/parsing may need
several copies of that body; the size cap limits serialized MIME bytes, including unknown-size
messages, rather than total heap usage or later decoded attachments. Adding `.buffer(n)` adds up
to `n` queued detached copies plus an in-flight message. No executors or listener jobs are created.
`HistoricalReadException` exposes `operation`, `uid`, `attempt`, and the original cause; recovery
deadline failures also preserve the last transport failure. The reader logs no message contents or
credentials. Diagnostics include operation, UID, attempt, elapsed time, connection generation,
recovery reason and outcome; exception text from providers is not written to lifecycle logs.

Each managed mailbox has an independent health-check loop. A locally owned health-check/reconnect
deadline is reported as a recoverable `java.util.concurrent.TimeoutException` through
`ReconnectFailed` and the manager's exception handler. It counts toward `maxReconnectAttempts`.
Parent cancellation still propagates. A later successful health check restores `Connected`, and
failed reconnects keep requesting replacement until success or exhaustion. The connection provider
must establish and return a usable connection when called, including when the previous store still
reports connected; the built-in Outlook/Gmail `connect` implementations already replace the store.

No consumer API changes are required: continue sharing the same managed handle between
`watchFolder(managed, "INBOX")` and `readMessagesFlow(managed, "INBOX", range)`. Recovery coordination
is internal. Keep sender checks, PDF/AI processing, and other consumer work inside `collect`; those
operations remain outside the reader's download/recovery budgets. Timeout defaults are unchanged.

## Conversation APIs

These extensions stay independent of companies, invoice parsing, persistence, AI, and application
authorization. Existing reading/watching APIs are unchanged. All examples below use the same
`ManagedMailSession` returned by your session manager; no second credential store is needed.

```kotlin
import dev.reapermaga.mailkt.message.*
import dev.reapermaga.mailkt.folder.*

val draft = composeMessage(
    from = "me@example.com",
    to = listOf("contact@example.com"),
    subject = "Invoice question",
    text = "Please resend the invoice with the correct billing address.",
)
// Persist the draft, its Message-ID, and an UNKNOWN attempt state before submission.
val result = managed.sendMessage(draft)
when (result.status) {
    SendStatus.ACCEPTED -> { /* Persist outgoing history; service accepted, delivery unconfirmed. */ }
    SendStatus.FAILED -> { /* Preserve draft; show failure. */ }
    SendStatus.UNKNOWN -> { /* Reconcile by Message-ID; do not automatically retry. */ }
}

val reply = composeReply(originalMessage, "me@example.com", "Thank you", replyAll = false)
// Review recipients before calling managed.sendMessage(reply).

val filter = MessageFilter(
    addresses = setOf("contact@example.com"),
    threadMessageIds = setOf("<known-thread-message@example.com>"),
)
var page = readMessagePage(managed, "INBOX", filter)
// Persist/process page.messages. Load older pages on demand:
while (page.nextCursor != null) {
    page = readMessagePage(managed, "INBOX", filter, cursor = page.nextCursor)
}
// After processing ALL pages, checkpoint page.uidValidity and page.snapshotUpperUid.
val arrivals = readMessagePage(
    managed, "INBOX", filter,
    afterUid = page.snapshotUpperUid,
    expectedUidValidity = page.uidValidity,
)
// Also drain arrivals.nextCursor before advancing the checkpoint.
```

`composeMessage` supports To/Cc/Bcc, UTF-8 subject/text and generates a Message-ID. `composeReply`
respects Reply-To and builds In-Reply-To/References; reply-all removes the sending mailbox and Bcc.
Messages remain Jakarta `MimeMessage` objects, so MIME bodies and attachments can also be composed
through Jakarta APIs. Do not mutate a draft during submission. Sending finalizes MIME headers
without changing its existing Message-ID. Message-ID is a reconciliation key, not an SMTP
idempotency guarantee. Cancellation or a process crash can leave submission unknown; no send
operation retries automatically. Prevent concurrent duplicate submissions in your backend.

### Assembled conversation history

```kotlin
val history = readConversations(
    session = managed,
    folderNames = listOf("INBOX", sentFolderName),
    contacts = setOf("contact@example.com"),
)
// history.threads contains separate email threads with chronological messages.
// Each message exposes its MIME content, timestamp, unread state and all mailbox copies/locations.
val older = readConversations(
    managed, listOf("INBOX", sentFolderName), setOf("contact@example.com"),
    threadMessageIds = history.threads.flatMap { it.relatedMessageIds }.toSet(),
    options = ConversationReadOptions(beforeUidByFolder = history.folders.associate {
        it.folderName to it.lowerUid
    }),
)
val combinedThreads = assembleConversations(
    (history.threads + older.threads).flatMap { it.messages }.flatMap { it.copies },
)
```

`readConversations` indexes only envelopes and threading headers for up to 2,000 recent UID
positions per folder by default, then follows the transitive Message-ID/References/In-Reply-To
relationships in that window, including ancestors and replies whose participants changed.
It downloads only selected bodies, with defaults of 500 matching mailbox copies and 50 MiB total
serialized MIME. Limits fail explicitly rather than returning silently incomplete threads.
Include archive/custom folders if your application needs their history. Missing ancestors or
stripped threading headers cannot be reconstructed by subject or shared domain.

Folder snapshots expose `olderHistoryAvailable` and `lowerUid` for progressive loading with
`beforeUidByFolder`. Replies outside a requested window are not included; expand the window or
load more history. Pass known message IDs when loading older pages, and reassemble stored copies
with new reads. For incremental updates, call `readMessagePage` with contacts plus all known thread
IDs, retain folder locations, then call `assembleConversations` with stored and new copies. Older
previously excluded messages may need a new bounded conversation read when a new reply establishes
a relationship. Truncated conversation snapshots must not be treated as full-mailbox sync checkpoints.

`assembleConversations` retains mailbox location/flag information and conservatively merges copies
with the same Message-ID only if sender, To/Cc, subject, timestamp, reply headers, MIME type and body
hash agree. Missing IDs remain separate, and conflicting same-ID content is retained. Accounts
remain isolated; thread IDs are derived from known relationships and may change as older ancestors
are discovered. Repeated locations use the last supplied copy, allowing flag updates from fresh reads.
Neither function applies company ownership or renders/sanitizes HTML.

Gmail and Outlook sessions configure SMTP with mandatory STARTTLS on port 587. Submission uses
the current connected session's credentials and serializes with reconnect/disconnect. Each send
opens/closes an SMTP transport underneath: IMAP itself cannot send mail. A disconnected account
fails before submission. `MailSession.supportsSending` reports configured SMTP support, not
granted consent or provider policy. The From address must equal the authenticated username;
delegated Send As/alias sending is not supported. Custom `ImapMailSession` instances may opt in
with `smtpConfig = SmtpConfig("smtp.example.com")`.

For Outlook use `OutlookOAuth2Config.consumer(clientId, enableSending = true)` or add
`https://outlook.office.com/SMTP.Send` to custom scopes. Existing read-only grants need new consent;
refreshing an old token alone does not grant sending rights. Microsoft 365 may also require an
administrator to enable authenticated SMTP for the mailbox. See
[Microsoft OAuth protocol requirements](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth).
Gmail's existing `https://mail.google.com/` scope covers IMAP and SMTP; see
[Google XOAUTH2 documentation](https://developers.google.com/workspace/gmail/imap/xoauth2-protocol).
Connection success over IMAP does not prove SMTP authorization; surface authentication failures
and reconnect with the additional consent when needed.

`listMailFolders(managed.session)` returns names and IMAP SPECIAL-USE attributes such as `\\Sent`.
Choose the provider's Sent folder and call `readMessagePage` on it as well as INBOX. Some servers
omit SPECIAL-USE; let callers configure the folder instead of assuming an English name. SMTP
does not universally save Sent copies. Persist accepted outgoing messages in the application;
optionally use `appendSentMessage(managed, sentFolderName, draft)` for servers that do not save
them. Do not append when the provider already saves a copy, and never resend because append failed.

Each page examines at most 100 UID positions by default (configurable 1–500), newest-first for
history and oldest-first for incremental reads. Address matching checks full From/To/Cc/Bcc
addresses case-insensitively; it never matches a shared domain or subject. Explicit Message-ID,
In-Reply-To and References tokens are alternatives to address matching. Expanding thread membership
requires the caller to collect discovered IDs and perform a new filtered scan where necessary.
Filtering reads envelopes/selected headers within the bounded UID window before downloading matching
bodies. It is not a provider conversation index or a global server SEARCH. Sparse or filtered pages
can be empty with a next cursor. Keep the same filter throughout a scan; new arrivals are outside
its fixed snapshot. Cursors are scoped by session ID and folder: use a stable account ID across
restarts, and validate cursor input in your backend. UIDVALIDITY changes require resynchronization.
Expunged messages absent before selection are skipped. Reads fail explicitly on connection/provider
errors; retry the same cursor after reconnect instead of advancing the checkpoint.

Pages return detached `HistoricalMessage` MIME copies with UID, UIDVALIDITY, server received time,
and copied flags, so content remains accessible after folders close. Defaults cap each message at
25 MiB and the total serialized page at 50 MiB; decrease `uidWindowSize` if a page hits the cap.
Decoded MIME and temporary copies require additional heap. Deduplicate folder items by
`(account, folder, uidValidity, uid)` and reconcile sent copies by account/Message-ID where available.
Handle missing/colliding Message-IDs explicitly. Persist checkpoints only after downstream work
succeeds. Existing managed watchers can notify new arrivals; use page UIDs for durable catch-up.

Company contact ambiguity, access checks, safe HTML rendering, tracking-image blocking, quoted-text
collapsing, drafts and AI belong in Rechnungsradar. A contact filter is not authorization; never
expose unfiltered mailbox access to company-scoped users. These APIs log no message bodies or tokens;
avoid logging provider exception contents without redaction.

This working tree extends the existing `ReaperMaga/mailkt` repository on `main`; no remote fork or
release has been created. To consume a local development version:

```powershell
./gradlew.bat :core:publishToMavenLocal :gmail:publishToMavenLocal :outlook:publishToMavenLocal -Pbuild.version=0.1.1-conversations-SNAPSHOT
```

Add `mavenLocal()` to the consuming project's repositories and depend on
`dev.reapermaga.mailkt:core:0.1.1-conversations-SNAPSHOT` and the corresponding provider module.
Rechnungsradar is not present in this workspace; its integration and real-account send/reply
verification must be performed there. Library tests exercise protocol configuration, submission
outcomes, reply relationships, exact matching, bounded paging, cursor validity and detached content
without accessing real mailboxes.

## Build

On Windows:

- Build: `./gradlew.bat build`
- Run all checks: `./gradlew.bat check`

On macOS/Linux:

- Build: `./gradlew build`
- Run all checks: `./gradlew check`

## Notes

- Provider sessions support OAuth2 only; the reusable `ImapMailSession` base can also be configured
  for plain authentication.
- Authentication and connection failures are thrown. Use `try/catch` at application boundaries
  instead of inspecting nullable error fields.
- `ReadMessagesResult` keeps its folder open so Jakarta `Message` instances remain usable; always
  call its suspending `close` function.

## License

MIT — see [LICENSE](LICENSE) for details.
