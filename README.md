# mailkt

Coroutine-first Kotlin/JVM library for reading, watching and sending email through a managed
`Mailbox`, with hosted OAuth2 for Gmail and Outlook (Microsoft 365). MailKT runs entirely in your
backend process; it never opens a browser, starts an HTTP listener or persists anything itself.

Highlights:

- One lifecycle handle per account: `Mailbox` with an explicit `StateFlow<MailboxState>`, automatic
  generation-safe reconnects and idempotent `close()`.
- Capability-oriented API: `mailbox.folders`, `mailbox.messages`, `mailbox.conversations`,
  `mailbox.outbox`.
- Envelope-first reading: filter on cheap metadata first, inspect MIME structure second, download
  only the parts you need.
- Immutable MailKT models. No Jakarta or Angus type appears in the public API.
- Explicit send outcomes (`ACCEPTED`, `FAILED`, `UNKNOWN`), never retried automatically.
- No built-in persistence: tokens, pending authorizations and checkpoints cross small interfaces or
  return values that you store.

## Modules

| Module      | Purpose                                                                       |
|-------------|-------------------------------------------------------------------------------|
| `:core`     | `Mailbox`, models, capabilities, lifecycle, IMAP/SMTP transport, MIME.        |
| `:gmail`    | Hosted Google authorization-code flow and `imap.gmail.com` / `smtp.gmail.com`. |
| `:outlook`  | Hosted Microsoft (MSAL4J) flow and `outlook.office365.com` IMAP/SMTP.         |
| `:examples` | Compile-tested sources of every snippet in this document.                     |

## Requirements

- JDK 25
- SLF4J 2 is used as an API only; add the logging backend of your choice.

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
    implementation("dev.reapermaga.mailkt:gmail:0.1.0")
    implementation("dev.reapermaga.mailkt:outlook:0.1.0")
}
```

Every snippet below is copied verbatim from the `examples` module, which is compiled and checked
against this document by a test, so the documentation cannot drift from the API.

## Persistence boundary

MailKT ships no file, database, cache, encryption or secret-store implementation. You implement two
narrow suspending interfaces and inject them:

```kotlin
interface TokenStore {
    suspend fun load(key: TokenKey): ByteArray?
    suspend fun save(key: TokenKey, value: ByteArray)
    suspend fun delete(key: TokenKey)
}

interface AuthorizationSessionStore {
    suspend fun save(session: PendingAuthorization)
    suspend fun consume(state: String): PendingAuthorization?
}
```

- `TokenKey` contains the provider, the client registration and the `MailboxId`, so tokens of
  different providers or mailboxes can never overwrite each other. Values are opaque bytes owned by
  the provider adapters.
- `TokenStore` implementations must replace values atomically and isolate concurrent access.
  Encryption, key management, retention, backup and deletion are the application's responsibility.
- `AuthorizationSessionStore.consume` must be atomic and one-time; pending sessions hold sensitive
  state and PKCE material and expire after ten minutes. A shared store supports several backend
  instances and prevents replay.
- Checkpoints (`ScanCheckpoint`, `WatchCheckpoint`, `ConversationCheckpoint`) are immutable values of
  primitives plus a format version. Operations accept a previous checkpoint and return the next one;
  MailKT never saves or acknowledges them. Persist a checkpoint only when your processing is durable.

Demonstration implementations (not for production):

```kotlin
/** Demonstration only. A real store persists opaque bytes durably, encrypted, with atomic replacement. */
class InMemoryTokenStore : TokenStore {
    private val values = ConcurrentHashMap<String, ByteArray>()

    override suspend fun load(key: TokenKey): ByteArray? = values[key.storageKey]?.copyOf()

    override suspend fun save(key: TokenKey, value: ByteArray) {
        values[key.storageKey] = value.copyOf()
    }

    override suspend fun delete(key: TokenKey) {
        values.remove(key.storageKey)
    }
}
```

```kotlin
/** Demonstration only. A shared backend needs a store whose `consume` is atomic across instances. */
class InMemoryAuthorizationSessionStore : AuthorizationSessionStore {
    private val pending = ConcurrentHashMap<String, PendingAuthorization>()

    override suspend fun save(session: PendingAuthorization) {
        pending[session.state] = session
    }

    override suspend fun consume(state: String): PendingAuthorization? = pending.remove(state)
}
```

## Hosted authorization for Gmail and Outlook

Both providers use the same split frontend/backend authorization-code flow:

1. The backend calls `beginAuthorization` and returns the generated URL to the frontend, which
   performs the browser redirect.
2. The provider redirects the browser to a backend callback route that you register with the
   provider (HTTPS in production, provider-supported HTTP loopback such as
   `http://localhost:8080/oauth/{provider}/callback` for local development). MailKT never registers
   the route.
3. The backend converts the query parameters into an `AuthorizationCallback` and calls
   `completeAuthorization`. MailKT validates the one-time state, expiry, redirect URI, provider
   response and PKCE verifier, verifies that the authenticated account matches the requested
   mailbox, and only then stores the tokens.
4. `open` builds the mailbox from the stored tokens using silent refresh only.

Provider configuration holds an explicit allowlist of redirect URIs; `beginAuthorization` rejects
anything else, so an arbitrary redirect URI from the frontend is never trusted. The expected email is
the mailbox's user-facing identity; MailKT derives the `MailboxId` from the provider identity and the
provider-canonical authenticated email, so the same address on different providers never collides.
To switch accounts, open a different email explicitly.

### Gmail

Create Google **Web application** OAuth credentials and register the exact callback URIs.

```kotlin
fun gmailClient(tokens: InMemoryTokenStore, sessions: InMemoryAuthorizationSessionStore): Gmail {
    val config = GmailConfig(
        clientId = System.getenv("GMAIL_CLIENT_ID"),
        clientSecret = System.getenv("GMAIL_CLIENT_SECRET"),
        // Exact backend callback URIs registered with Google for this environment.
        allowedRedirectUris = setOf(
            URI("https://app.example.com/oauth/gmail/callback"),
            URI("http://localhost:8080/oauth/gmail/callback"), // local development
        ),
    )
    return Gmail(config, tokens, sessions)
}
```

```kotlin
/** Backend endpoint: returns the URL the frontend navigates the browser to. */
suspend fun beginGmailAuthorization(gmail: Gmail, email: String): String {
    val request = gmail.beginAuthorization(
        expectedEmail = MailAddress(email),
        redirectUri = URI("https://app.example.com/oauth/gmail/callback"),
    )
    return request.authorizationUrl.toString()
}
```

```kotlin
/** Backend route registered as the redirect URI: hand the query parameters to MailKT. */
suspend fun gmailCallback(gmail: Gmail, code: String?, state: String?, error: String?): Mailbox {
    val id = gmail.completeAuthorization(AuthorizationCallback(code, state, error))
    println("Authorized mailbox of provider ${id.provider}")
    return gmail.open(MailAddress(requireNotNull(System.getenv("GMAIL_ADDRESS"))), MailboxOptions())
}
```

### Outlook

Register a web/confidential client in Microsoft Entra with a client secret held by the backend. The
IMAP delegated scope is requested together with `offline_access`; set `enableSending` to add the
SMTP scope.

```kotlin
fun outlookClient(tokens: InMemoryTokenStore, sessions: InMemoryAuthorizationSessionStore): Outlook {
    val config = OutlookConfig(
        clientId = System.getenv("OUTLOOK_CLIENT_ID"),
        clientSecret = System.getenv("OUTLOOK_CLIENT_SECRET"), // held by the backend only
        allowedRedirectUris = setOf(URI("https://app.example.com/oauth/outlook/callback")),
        enableSending = true, // adds the SMTP.Send scope
    )
    return Outlook(config, tokens, sessions)
}
```

```kotlin
suspend fun outlookAuthorizeAndOpen(outlook: Outlook, email: String, code: String, state: String): Mailbox {
    // 1. The backend creates the browser URL for the frontend.
    val request = outlook.beginAuthorization(MailAddress(email), URI("https://app.example.com/oauth/outlook/callback"))
    println("Send the browser to ${request.authorizationUrl}")
    // 2. The provider redirects to the backend callback, which completes the flow.
    outlook.completeAuthorization(AuthorizationCallback(code = code, state = state))
    // 3. Open the mailbox using the stored tokens.
    return outlook.open(MailAddress(email))
}
```

## Mailbox lifecycle

`Mailbox` is the single lifecycle handle. Opening suspends until the first authenticated connection
succeeds; failures are typed `MailException`s and no partially initialized mailbox is exposed.

```kotlin
suspend fun openWithPolicy(gmail: Gmail, registry: MailboxRegistry): Mailbox {
    val options = MailboxOptions(
        connectionPolicy = ConnectionPolicy(keepAliveInterval = 30.seconds, maxReconnectAttempts = 5),
        registry = registry, // optional: rejects a second open mailbox with the same identity
    )
    return gmail.open(MailAddress("alice@gmail.com"), options)
}
```

`state` is the authoritative snapshot; `events` is a bounded best-effort diagnostic stream.

| State                      | Meaning                                                              |
|----------------------------|----------------------------------------------------------------------|
| `Connected`                | Operations run on the current connection generation.                 |
| `Reconnecting`             | Automatic recovery after a network, socket, TLS or store failure.     |
| `AuthenticationRequired`   | Silent refresh failed; run the hosted flow, then call `reconnect()`. |
| `Failed(recoverable)`      | Attempts exhausted; idle until `reconnect()` or `close()`.            |
| `Closed`                   | Terminal; published exactly once.                                    |

```kotlin
fun observeState(mailbox: Mailbox, scope: CoroutineScope) {
    mailbox.state.onEach { state ->
        when (state) {
            MailboxState.Connected -> println("Connected")
            is MailboxState.Reconnecting -> println("Reconnecting, attempt ${state.attempt} (${state.reason})")
            MailboxState.AuthenticationRequired -> println("Run the authorization flow again, then call reconnect()")
            is MailboxState.Failed -> println("Failed (recoverable=${state.recoverable}); call reconnect() to retry")
            MailboxState.Closed -> println("Closed")
        }
    }.launchIn(scope)
}
```

Recovery follows `ConnectionPolicy` (30 second keep-alive and attempt timeout, five attempts,
exponential backoff from one to 30 seconds with 20 percent jitter). Concurrent failures of one
generation coalesce into one reconnect, a replacement connection is authenticated before it is
published, and the old one is closed once. Operations pin the generation they started on; reads
resume or restart depending on their contract, and SMTP submission and your own consumer code are
never retried. MailKT never navigates a browser on its own:

```kotlin
/** After the application completed a new hosted authorization, resume the same mailbox. */
suspend fun resumeAfterReauthorization(mailbox: Mailbox) {
    if (mailbox.state.value is MailboxState.AuthenticationRequired || mailbox.state.value is MailboxState.Failed) {
        try {
            mailbox.reconnect()
        } catch (e: MailException.AuthenticationRequired) {
            println("Still not authorized; send the user through the authorization flow again")
        }
    }
}
```

`close()` is suspending and idempotent; it rejects new operations, cancels watchers and background
work and releases every folder, IDLE watcher and connection. `use` closes a mailbox around a scope:

```kotlin
suspend fun withMailbox(gmail: Gmail) {
    gmail.open(MailAddress("alice@gmail.com")).use { mailbox ->
        println("Connected as mailbox of ${mailbox.id.provider}")
    } // always closed, even on failure or cancellation
}
```

### Multiple mailboxes

Every `Mailbox` owns its connection, state machine, recovery loop, watchers and cursors. There is no
global session manager: keep your own `Map<MailboxId, Mailbox>` or use the optional
`MailboxRegistry`, which only enforces identity uniqueness and closes all mailboxes concurrently.
The same `TokenStore`, configuration and dispatchers may be shared safely.

```kotlin
suspend fun shutdown(registry: MailboxRegistry) {
    val report = registry.close() // closes all mailboxes concurrently
    report.failures.forEach { (id, _) -> println("Failed to close mailbox of ${id.provider}") }
}
```

## Folders

```kotlin
suspend fun discoverFolders(mailbox: Mailbox): FolderPath {
    mailbox.folders.list().forEach { println("${it.name} special=${it.specialUse}") }
    val sent = mailbox.folders.special(SpecialUse.SENT)
    println("Sent folder resolved: ${sent != null}")
    return checkNotNull(mailbox.folders.special(SpecialUse.INBOX)).path
}
```

## Reading mail: envelope, structure, part

Reading is a pipeline; each stage is optional and later stages only run for survivors of earlier ones.

- `MessageEnvelope`: location, UID and UIDVALIDITY, Message-ID relations, addresses, subject, dates,
  flags, content type, advertised size. No body, no attachment bytes.
- `MessageStructure`: a tree of `MessagePartDescriptor`s (media type, disposition, file name,
  content ID, transfer encoding, advertised size, stable `MessagePartRef`). No bytes.
- `MailMessage`: the decoded immutable content tree and attachments plus the envelope.
- `MessageQuery` contains only predicates evaluable from an envelope: folder, dates, addresses,
  flags, subject, headers, thread IDs, size. Filtering on body or attachment content is necessarily
  after download.

A `MessagePartRef` carries the mailbox, folder, UIDVALIDITY, UID and MIME section and only works with
its originating mailbox. Part downloads require an explicit byte limit and return detached MailKT
content. If a message is expunged, UIDVALIDITY changes or a part changes between selection and
download, you get a typed `MessageUnavailable` or `IntegrityViolation`; another message or part is
never substituted.

### Paging

Pages freeze their membership: later arrivals never shift a resumed scan. A sparse or heavily
filtered page can be empty and still carry a continuation checkpoint.

```kotlin
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
```

### Historical streaming

```kotlin
suspend fun streamHistory(mailbox: Mailbox, inbox: FolderPath, since: Instant) {
    val selection = MessageSelection(inbox, range = MessageRange.Dates(from = since, before = null))
    mailbox.messages.envelopes(selection).collect { envelope ->
        println("UID ${envelope.location.uid} size=${envelope.advertisedSize}")
    }
}
```

### Selective download

The same pipeline serves historical scans and live arrivals, and MailKT needs to know nothing about
your rules. Sender or subject rejection performs no structure or content fetch; file name or type
rejection performs no attachment download.

```kotlin
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
```

For simple cases, full-message streams filter envelopes internally before fetching content:

```kotlin
suspend fun readFullMessages(mailbox: Mailbox, inbox: FolderPath) {
    mailbox.messages.messages(MessageSelection(inbox, MessageQuery(seen = false))).collect { message ->
        println("Unread message with ${message.attachments.size} attachments; text length ${message.plainText?.length ?: 0}")
    }
}
```

## Watching

Watchers fetch and filter the envelope of every live IDLE notification before requesting anything
else. They catch up from a `WatchCheckpoint` after restarts and reconnects, suppress duplicates within
a collection and deliver at-least-once, so de-duplicate side effects by account and Message-ID. If
UIDVALIDITY changed, the folder is rescanned rather than risking loss.

```kotlin
/** Live arrivals (IMAP IDLE) with catch-up. Delivery is at-least-once: de-duplicate by Message-ID. */
suspend fun watchInbox(mailbox: Mailbox, inbox: FolderPath, saved: WatchCheckpoint?) {
    val query = MessageQuery(from = setOf(MailAddress("billing@example.com")))
    mailbox.messages.watchEnvelopes(inbox, query, from = saved).collect { watched ->
        println("New mail UID ${watched.envelope.location.uid}")
        // Persist watched.next only once your processing of this envelope is durable.
    }
}
```

```kotlin
suspend fun watchFullMessages(mailbox: Mailbox, inbox: FolderPath) {
    mailbox.messages.watch(inbox).collect { watched ->
        println("Received ${watched.message.attachments.size} attachments")
    }
}
```

## Conversations

Conversations are grouped by explicit relationships only (Message-ID, In-Reply-To, References),
never by subject or domain. Copies of one message in several folders merge, and messages without an
ID stay separate. `Conversations` works on envelopes; fetch bodies with `messages.get` when needed.
`synchronize` is incremental per folder, reports UIDVALIDITY resets and marks truncated results, so
call it again with the returned checkpoint until nothing is truncated.

```kotlin
suspend fun syncConversations(mailbox: Mailbox, inbox: FolderPath, saved: ConversationCheckpoint?): ConversationCheckpoint {
    val sync = mailbox.conversations.synchronize(inbox, from = saved)
    if (sync.reset) println("UIDVALIDITY changed: discard cached conversations")
    sync.changed.forEach { println("Conversation with ${it.messages.size} messages, truncated=${it.truncated}") }
    return sync.next // persist after applying `changed`
}
```

```kotlin
suspend fun readConversation(mailbox: Mailbox, location: MessageLocation) {
    val conversation = mailbox.conversations.of(location, maxMessages = 100)
    conversation.messages.forEach { println("Message ${it.location.uid}") }
    // Fetch bodies only for the messages you need:
    val newest = conversation.messages.last()
    val full = mailbox.messages.get(newest.location)
    println("Newest has ${full.attachments.size} attachments")
}
```

## Composing and sending

`outbox` is null when no SMTP endpoint is configured. Drafts are immutable; the Message-ID is
assigned when the draft is created, so you can persist it before submission.

```kotlin
suspend fun composeAndSend(mailbox: Mailbox, pdf: ByteArray): SendResult {
    val outbox = mailbox.outbox ?: error("This mailbox has no SMTP configured")
    val draft = outbox.newDraft().copy(
        to = listOf(MailParticipant(MailAddress("customer@example.com"), "Customer")),
        subject = "Your invoice",
        text = "Please find the invoice attached.",
        attachments = listOf(MailAttachment("invoice.pdf", "application/pdf", ByteContent(pdf))),
    )
    // draft.messageId is assigned already: persist it BEFORE sending to reconcile an UNKNOWN outcome.
    return outbox.send(draft)
}
```

Replies derive recipients, subject and threading headers from an envelope. Reply-To is respected,
reply-all excludes your own address, and Bcc is never exposed:

```kotlin
suspend fun replyToMessage(mailbox: Mailbox, original: MessageEnvelope): SendResult {
    val outbox = checkNotNull(mailbox.outbox)
    val reply = outbox.reply(original, replyAll = false, text = "Thank you, received.")
    return outbox.send(reply, saveToSent = true)
}
```

`send` returns an explicit outcome and never retries or resubmits, also not across reconnects:

- `ACCEPTED`: the SMTP service accepted submission (not proof of delivery).
- `FAILED`: definitely not sent.
- `UNKNOWN`: the connection failed after DATA started. Look for the Message-ID in the Sent folder
  before deciding to send again.

```kotlin
suspend fun sendAndInterpret(mailbox: Mailbox, draft: Draft) {
    when (val result = checkNotNull(mailbox.outbox).send(draft)) {
        is SendResult.Accepted -> println("Accepted; Sent copy stored: ${result.sentCopy != null}")
        is SendResult.Failed -> println("Definitely not sent: ${result.cause.javaClass.simpleName}")
        is SendResult.Unknown -> {
            // Never resend blindly. Look for result.messageId in the Sent folder first.
            val sent = mailbox.folders.special(SpecialUse.SENT)
            println("Unknown outcome (${result.reason}); reconcile ${result.messageId} in ${sent?.name}")
        }
    }
}
```

### Sent folder behavior

With `saveToSent = true` MailKT appends a copy of an accepted message to the Sent folder found by
its IMAP special-use attribute, but only for providers whose SMTP service does not store a copy
itself (Gmail and Outlook do, so nothing is appended for them). The append is best effort, is not
idempotent and can never trigger another submission; keep the accepted message in your own
persistence regardless. `Folders.append` stores drafts or arbitrary messages explicitly:

```kotlin
suspend fun saveAsDraft(mailbox: Mailbox, draft: Draft): MessageLocation? {
    val drafts = mailbox.folders.special(SpecialUse.DRAFTS) ?: return null
    return mailbox.folders.append(drafts.path, draft, MessageFlags(draft = true))
}
```

## Error handling

Failures are typed subclasses of `MailException` with sanitized messages. Cancellation is never
classified as a transport failure.

```kotlin
suspend fun readSafely(mailbox: Mailbox, location: MessageLocation) {
    try {
        val message = mailbox.messages.get(location)
        println("Read message with ${message.attachments.size} attachments")
    } catch (e: MailException.MessageUnavailable) {
        println("Expunged or moved; skip it")
    } catch (e: MailException.IntegrityViolation) {
        println("UIDVALIDITY or part changed (${e.kind}); rescan the folder")
    } catch (e: MailException.LimitExceeded) {
        println("Larger than ${e.limitBytes} bytes")
    } catch (e: MailException.NotConnected) {
        println("Reconnecting (${e.state}); retry the operation later")
    } catch (e: MailException.AuthenticationRequired) {
        println("Send the user through the authorization flow again")
    } catch (e: MailException.MailboxClosed) {
        println("Mailbox was closed")
    }
}
```

```kotlin
suspend fun listOrExplain(mailbox: Mailbox, folder: FolderPath) {
    try {
        mailbox.messages.page(MessageSelection(folder))
    } catch (e: MailException.FolderNotFound) {
        println("No such folder")
    }
}
```

## Logging

MailKT logs through the SLF4J 2 API only, under dedicated categories (`dev.reapermaga.mailkt.lifecycle`,
`.auth`, `.imap`, `.watch`, `.sync`, `.mime`, `.smtp`) with structured key/value fields and a stable
one-way mailbox correlation ID. Addresses, tokens, authorization codes, headers, subjects,
recipients, bodies, attachment names and provider error text are never logged. Configure routing and
levels in your own logging backend.

## Guarantees

- Reads survive reconnects; scans keep their frozen snapshot and UIDVALIDITY integrity.
- Downloads are bounded and never silently truncated.
- Watchers are at-least-once with in-collection duplicate suppression.
- Sending is never retried; outcomes are explicit.
- Closing one mailbox never affects another.

## Build

On Windows:

- Build: `./gradlew.bat build`
- Run all checks: `./gradlew.bat check`

On macOS/Linux:

- Build: `./gradlew build`
- Run all checks: `./gradlew check`

The build includes architecture checks (no Jakarta or Angus in public packages) and JDK 25 tests.

## License

MIT, see [LICENSE](LICENSE) for details.
