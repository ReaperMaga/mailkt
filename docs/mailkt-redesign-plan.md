# MailKT Domain API Redesign

## Summary

Replace the current Jakarta-facing helper API with a managed `Mailbox` built from immutable MailKT models. Keep the existing working behavior—recovery, UID integrity, watching, conversations, OAuth refresh, and SMTP outcome handling—but redesign the public surface and internals freely.

Use capability-oriented names: `mailbox.folders`, `mailbox.messages`, `mailbox.conversations`, and `mailbox.outbox`.

## Public API and Provider Authentication

```kotlin
interface Mailbox {
    val id: MailboxId
    val email: MailAddress
    val state: StateFlow<MailboxState>
    val events: Flow<MailboxEvent>

    val folders: Folders
    val messages: Messages
    val conversations: Conversations
    val outbox: Outbox?

    suspend fun reconnect()
    suspend fun close()
}
```

- `Folders` lists folders, resolves special-use folders, and appends messages.
- `Messages` pages, streams, and watches mailbox content using an envelope-first pipeline.
- `Conversations` reads, synchronizes, and assembles conversations.
- `Outbox` creates drafts/replies and submits them with explicit `ACCEPTED`, `FAILED`, or `UNKNOWN` results.
- Public models include `MailMessage`, MIME content/attachments, addresses, flags, locations, cursors, drafts, and conversations. Jakarta/Angus types are never exposed.

Use backend-oriented provider clients:

```kotlin
val gmail = Gmail(gmailConfig, tokenStore, authorizationSessionStore)
val request = gmail.beginAuthorization(
    expectedEmail = MailAddress("alice@gmail.com"),
    redirectUri = URI("http://localhost:8080/oauth/gmail/callback"), // local development
)
// The backend returns request.authorizationUrl to the frontend.
// The provider redirects the browser to the backend callback endpoint.
gmail.completeAuthorization(AuthorizationCallback(code, state, error))
val mailbox = gmail.open(MailAddress("alice@gmail.com"), options)
```

The expected email is the mailbox's user-facing identity. MailKT constructs `MailboxId` from the provider identity plus the provider-canonical authenticated email, so the same address on different provider configurations cannot collide. OAuth must verify that the returned account matches the requested mailbox before publishing a connection or storing refreshed tokens. The caller must explicitly open a different email to switch accounts.

Both Gmail and Outlook use hosted authorization-code flows designed for a browser frontend and remote backend:

- MailKT generates the provider authorization URL; the backend returns it to the frontend, which performs the browser redirect.
- Register exact backend callback URIs with Google and Microsoft: HTTPS application URLs in production and provider-supported HTTP loopback URLs such as `http://localhost:8080/oauth/{provider}/callback` for local development. MailKT never registers the HTTP route, opens a browser, or starts its own listener.
- Provider configuration contains an explicit allowlist of redirect URIs for the current environment. `beginAuthorization` rejects any URI outside that allowlist; never trust an arbitrary redirect URI supplied by the frontend.
- The backend callback converts query parameters into `AuthorizationCallback` and passes them to MailKT. MailKT validates the one-time state, expiry, redirect URI, provider response, and PKCE verifier before exchanging the code.
- `beginAuthorization` stores a short-lived opaque pending authorization through the injected `AuthorizationSessionStore`; `completeAuthorization` atomically consumes it. This supports multiple backend instances and prevents replay.
- Configure Google with Web application OAuth credentials. Configure Microsoft as a web/confidential client using a backend-held client secret or certificate and MSAL4J authorization-code exchange.
- Request IMAP/SMTP scopes plus `offline_access` for refresh-token persistence.
- On reconnect, only attempt silent refresh. If it fails, move the mailbox to `AuthenticationRequired`; do not unexpectedly open a browser.
- To recover `AuthenticationRequired`, the application runs the same begin/callback/complete authorization flow and then calls `mailbox.reconnect()`.
- Remove Gmail's installed-app `LocalServerReceiver`, Outlook device login, `OutlookOAuth2Verification`, and all desktop/localhost authentication configuration.

The hosted flow follows the providers' web-server authorization-code guidance:

- [Google OAuth for web-server applications](https://developers.google.com/identity/protocols/oauth2/web-server)
- [MSAL4J authorization URL builder](https://learn.microsoft.com/en-us/entra/msal/java/advanced/authorization-code-url-builder)
- [Microsoft web application configuration](https://learn.microsoft.com/en-us/entra/identity-platform/scenario-web-app-call-api-app-configuration)
- [Exchange OAuth documentation](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth)

Exchange IMAP and SMTP delegated scopes remain unchanged.

## Envelope-First Reading and Watching

Represent lightweight metadata, MIME structure, and downloaded content separately:

- `MessageEnvelope` contains location, UID/UIDVALIDITY, Message-ID relationships, addresses, subject, dates, flags, content type, and advertised size, but no body or attachment bytes.
- `MessageStructure` is a tree of `MessagePartDescriptor` values containing stable part references, media type, disposition, filename, content ID, transfer encoding, and advertised size, but no part bytes.
- `MailMessage` contains the immutable decoded body/content tree and attachments in addition to its envelope.
- `MessageQuery` contains only predicates that can be evaluated from envelope data, such as folder, date, addresses, flags, subject, headers, thread IDs, and advertised size. Body- or attachment-content filtering is explicitly post-download.

All primary read paths use two phases:

1. Freeze the requested UID/date/position membership where snapshot semantics require it.
2. Fetch envelope fields in bounded batches.
3. Apply `MessageQuery` or caller application logic before requesting MIME structure, bodies, or attachment bytes.
4. When needed, fetch MIME structure metadata and filter by attachment filename, disposition, media type, or advertised size without downloading part contents.
5. Download only selected body or attachment parts, or request a complete `MailMessage` when the whole message is genuinely needed.

`Messages` exposes envelope-only operations, structure lookup, selective part download, and full-message convenience operations. `MessagePartRef` includes the mailbox/folder/UIDVALIDITY/UID identity plus the MIME section identifier, and can only be used with its originating mailbox. Part downloads require an explicit byte limit and return detached MailKT content, never a live Jakarta stream.

Callers may list/watch envelopes, apply arbitrary application-specific rules, inspect structure for matching envelopes, and download only the required parts. Full-message streams perform envelope filtering internally for simpler use cases. Live IDLE notifications must fetch and filter the envelope before requesting structure or content. Historical scans must preserve their fixed snapshot and ordering even when most envelopes are filtered out.

The Rechnungsradar-style ingestion path should therefore be expressible without MailKT knowing application rules:

```kotlin
mailbox.messages.envelopes(selection).collect { envelope ->
    val candidates = ingestionRules.findCandidates(envelope.from, envelope.subject)
    if (candidates.isEmpty()) return@collect

    val structure = mailbox.messages.structure(envelope.location)
    structure.attachments
        .filter { it.isPdf && ingestionRules.matchesFilename(candidates, it.fileName) }
        .forEach { part ->
            val pdf = mailbox.messages.download(part.ref, maxBytes = MAX_PDF_BYTES)
            processPdf(pdf)
        }
}
```

The same pipeline applies to watched arrivals and historical date/position selections. Sender/subject rejection performs no MIME structure or content fetch; filename/type rejection performs no attachment-content fetch; only PDFs that survive both stages are downloaded.

If a matching message is expunged, UIDVALIDITY changes, or a MIME part changes between envelope/structure selection and content download, return a typed unavailable/integrity failure according to the operation's snapshot contract; never silently substitute another message or part. Sparse pages and scans may produce no messages while still returning a continuation checkpoint.

## Persistence Boundary

MailKT performs no application persistence and ships no file, database, cache, encryption, or secret-store implementation. It only defines narrow suspending ports that library users implement and inject.

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

- `TokenKey` contains provider/client-registration identity and `MailboxId`; tokens from different providers or mailboxes can never overwrite one another.
- `AuthorizationSessionStore.consume` must be atomic and one-time. Pending sessions contain sensitive state/PKCE material and expire after ten minutes.
- Provider adapters own serialization of their native OAuth cache payloads, while the user-provided store treats values as opaque bytes.
- Remove `FileTokenPersistenceStorage`, AES persistence wrappers, and all filesystem/token-encryption utilities from the library.
- Message, page, watcher, and conversation checkpoints are immutable public value objects containing primitives plus a format version. Operations accept a previous checkpoint and return the next one, but never save it.
- The caller decides when processing is durable and when to persist a returned checkpoint. MailKT must not advance or acknowledge a checkpoint on the caller's behalf.
- Document that a `TokenStore` implementation must provide atomic replacement and isolation for concurrent mailbox access; encryption, key management, retention, backup, and deletion policies belong to the application.

## Lifecycle and Recovery Contract

`Mailbox` is the single lifecycle handle. Opening a provider suspends until the initial authenticated connection succeeds; initial authentication or connection failures are returned as typed exceptions and no partially initialized mailbox is exposed.

Expose an explicit state machine:

```kotlin
sealed interface MailboxState {
    data object Connected : MailboxState
    data class Reconnecting(val attempt: Int, val reason: RecoveryReason) : MailboxState
    data object AuthenticationRequired : MailboxState
    data class Failed(val cause: MailException, val recoverable: Boolean) : MailboxState
    data object Closed : MailboxState
}
```

The lifecycle must follow these rules:

- Every mailbox owns one supervised internal coroutine scope and at most one published connection generation.
- Health checks run independently per mailbox. Transport operations can report a failed generation and request recovery immediately instead of waiting for the next health check.
- Concurrent recovery requests for the same generation coalesce into one reconnect. Reports from stale generations cannot replace or invalidate a newer connection.
- A replacement connection is fully authenticated and validated before it is published; the obsolete connection is then closed exactly once.
- Network, socket, TLS, store-closure, and repeated folder-closure failures can trigger automatic reconnect. Authentication failures transition to `AuthenticationRequired` and never initiate frontend navigation automatically.
- After the application completes a new hosted authorization flow, `reconnect()` resumes connection management. It also provides an explicit caller-requested retry from a recoverable `Failed` state.
- Configure lifecycle behavior through `ConnectionPolicy`: 30-second keep-alive interval, 30-second attempt timeout, five reconnect attempts, exponential backoff beginning at one second and capped at 30 seconds, with 20% jitter. Tests use an injected delay/random source.
- After reconnect attempts are exhausted, transition to recoverable `Failed`; remain idle until `reconnect()` or `close()` is called.
- Operations pin the current connection generation. Feature-specific logic decides whether an interrupted operation resumes, restarts, or fails; reconnecting must never blindly retry SMTP submission or downstream consumer code.
- `close()` is suspending and idempotent. It atomically rejects new operations, cancels health/recovery/watcher work, closes folders, IDLE managers, transports, and the active store, then publishes `Closed` exactly once.
- Cancellation from the mailbox owner propagates without being classified as a transport failure. Closing one mailbox never affects another mailbox.
- `state` is the authoritative durable snapshot. `events` is a bounded best-effort diagnostic stream and must never be required to reconstruct current state.
- Credentials, access tokens, message contents, and provider exception text that may contain sensitive data are never included in states, events, or lifecycle logs.

Provide a suspending `use` helper so callers can reliably close a mailbox around an application scope.

## Multiple Mailboxes and Account Isolation

Support any practical number of concurrently open user mailboxes in the same process. Each `Mailbox` instance represents one account and owns its own authenticated connection, lifecycle state machine, connection generation, recovery loop, health-check job, folders, watchers, cursors, and outbox synchronization.

- Do not use global mutable connection, credential, recovery, or active-account state.
- Opening, authorizing, reconnecting, failing, or closing one mailbox must not change another mailbox's state or cancel its work.
- The same user-provided `TokenStore`, dispatchers, immutable provider configuration, and transport factories may be shared safely between mailbox instances.
- Namespace every token by provider, application/client registration, and `MailboxId`. Remove Gmail's fixed credential key and never select Outlook accounts with `firstOrNull`; load the exact cached identity assigned to the mailbox.
- Reject two simultaneously open mailboxes with the same `MailboxId` only when they share an explicit optional registry. Independent callers without a registry remain responsible for avoiding duplicate ownership of the same mailbox.
- Keep a mailbox-level resource boundary: folder and watcher concurrency limits, connection policy, byte limits, and close behavior apply per mailbox.
- Do not add a mandatory global session manager. Applications that manage many users may keep their own `Map<MailboxId, Mailbox>` or use an optional lightweight `MailboxRegistry` that only enforces identity uniqueness and performs bulk close; recovery stays inside each mailbox.
- `MailboxRegistry.close()` closes all registered mailboxes concurrently under supervision, reports individual close failures, and never prevents the remaining mailboxes from being closed.

## Internal Structure

Organize `core` into:

- `model` — public immutable domain types.
- `client` — `Mailbox` and capability contracts.
- `internal.application` — reading, watching, conversations, and outbox orchestration.
- `internal.connection` — lifecycle, health checks, connection generations, and recovery.
- `internal.transport` — narrow protocol ports.
- `internal.angus` — Jakarta/Angus adapter implementations.
- `internal.mime` — MIME parsing, bounded serialization, and model conversion.

Extract shared infrastructure for failure-chain inspection, recovery classification, generation-aware recovery, scoped folder access, UID/UIDVALIDITY validation, detached MIME conversion, and structured diagnostics. Keep retry policy owned by each feature rather than creating one generic retry engine.

## Logging and Diagnostics

Use SLF4J 2 as an API-only dependency and never bundle or configure a logging backend. Applications retain full control over routing, formatting, retention, and log levels.

- Use a dedicated logger category for lifecycle, authentication, IMAP operations, watching, synchronization, MIME processing, and SMTP submission.
- Emit structured key/value fields for an opaque mailbox correlation ID, operation, folder hash when needed, UID, connection generation, attempt, elapsed time, recovery reason, and outcome.
- `INFO` records significant lifecycle transitions and final SMTP outcomes; `WARN` records exhausted or actionable recoveries; `DEBUG` records bounded operation and retry details. Avoid per-message `INFO` logs.
- Never log raw email addresses, access/refresh tokens, authorization codes, MIME headers, subjects, recipients, bodies, attachment names/content, OAuth cache payloads, or provider exception messages. Use a stable one-way mailbox correlation value rather than the email address.
- Log classified failure type and exception class. Do not attach an unsanitized provider throwable whose message may contain server responses or credentials.
- Cancellation and expected shutdown are not warnings. Reconnect coalescing and stale-generation rejection are debug diagnostics.
- Keep `MailboxState` authoritative and `MailboxEvent` suitable for programmatic monitoring; logging is observational and must never drive behavior.
- Test logging with an in-memory backend to prove secrets, email addresses, message content, and provider error text cannot appear at any enabled level.

Move Kotlin sources currently under `src/main/java` to `src/main/kotlin`. Make Angus an `implementation` dependency and add checks preventing public APIs from importing Jakarta/Angus.

## README and Legacy Removal

Rewrite `README.md` completely around the redesigned API instead of incrementally editing the existing document. The new README must describe only supported concepts and contain complete, compiling examples for installation, split frontend/backend Gmail and Outlook authorization, backend callback handling, mailbox lifecycle, folder discovery, paging, historical streaming, watching, conversations, composing, replies, attachments, sending, Sent-folder behavior, reconnect states, and error handling.

Remove every legacy API and usage once its replacement is available, including:

- `MailSession`, `ManagedMailSession`, `MailSessionManager`, and direct `MailConnection` access.
- `GmailMailSession`, `OutlookMailSession`, Gmail installed-app/local-receiver authentication, and the old standalone OAuth login workflow.
- Outlook device-code login and `OutlookOAuth2Verification`.
- Public Jakarta `Message`/`MimeMessage` values and the old top-level read, watch, conversation, compose, send, and append helpers.
- Legacy imports, examples, comments, KDoc links, README snippets, tests, and terminology that teach the removed workflow.

Do not retain deprecated wrappers or compatibility aliases. Historical behavior may remain in characterization tests while a subsystem is being ported, but those tests must be rewritten against the new public API or removed before completion.

## Delivery

1. Add domain models, `Mailbox` capabilities, transport ports, architecture rules, and characterization fixtures.
2. Extract shared connection, IMAP, MIME, and failure-handling primitives.
3. Implement the new managed `Mailbox` lifecycle.
4. Implement hosted Gmail and Outlook authorization-code flows, pending-session storage ports, callback completion, silent refresh, and explicit reauthorization.
5. Port folders, messages, streaming, watching, and outbox behavior.
6. Port conversations and synchronization.
7. Rewrite every example against the new API, including sending, attachments, replies, and reauthentication.
8. Rewrite `README.md` from the ground up and remove all descriptions or snippets for the old API.
9. Delete legacy sessions, managers, device-code Outlook APIs, raw Jakarta models, top-level helpers, compatibility wrappers, and obsolete tests.
10. Search the entire repository for removed symbols and legacy terminology; resolve every remaining production, test, example, KDoc, and Markdown reference.
11. Record the redesigned API as the new compatibility baseline.

Every slice must compile and pass the complete test suite before the next slice begins. Temporary adapters may bridge old and new internals during migration, but no deprecated compatibility layer remains at completion.

## Test Plan

- Preserve current behavior with characterization tests before replacing each subsystem.
- Add transport contract tests using fake connections, folders, messages, and submissions.
- Test MIME conversion, structure-only inspection, nested multipart content, attachments, encoded filenames, size limits, selective part downloads, and malformed messages.
- Verify that live watchers, historical date/position scans, pages, and conversation synchronization fetch envelope batches first and never request MIME structure, body, or attachment data for envelope-filtered messages.
- Verify that attachment filename/type filtering uses structure metadata and never downloads rejected part bytes.
- Test envelope-only consumption, structure-only consumption, selective PDF download, full-message convenience streams, sparse filtered pages, and expunge/UIDVALIDITY/part-change races between selection and content download.
- Preserve recovery behavior for closed stores/folders, socket and TLS failures, timeouts, stale generations, concurrent recovery, and cancellation.
- Preserve watcher catch-up, cleanup, duplicate suppression, and at-least-once delivery.
- Preserve fixed historical snapshots, UIDVALIDITY integrity, bounded downloads, and non-retried consumer code.
- Preserve conversation grouping, copy merging, incremental cursors, truncation, and recovery.
- Preserve SMTP uncertainty and Message-ID reconciliation.
- Test Gmail and Outlook hosted authorization URL generation, production HTTPS callbacks, local HTTP loopback callbacks, redirect allowlist rejection, state/PKCE validation, callback errors, expiry, replay rejection, atomic session consumption, token-cache persistence, and wrong-account rejection.
- Test silent renewal and transition to `AuthenticationRequired`; after hosted reauthorization completes, verify explicit reconnect resumes the existing mailbox.
- Test the full mailbox state machine, explicit reconnect, reconnect exhaustion, coalesced recovery, stale-generation rejection, idempotent close, close during active operations, and absence of leaked jobs/resources.
- Test multiple Gmail, Outlook, and mixed-provider mailboxes operating concurrently with isolated token keys, authorization sessions, states, generations, cursors, watchers, reconnects, and shutdown. Include one mailbox being reauthorized while the others remain connected.
- Test optional registry duplicate detection and supervised bulk close without introducing shared recovery state.
- Add contract tests for user-provided `TokenStore` implementations: opaque round trips, deletion, atomic replacement expectations, mailbox isolation, and concurrent access. MailKT itself has no persistence implementation tests.
- Test checkpoint continuity by passing returned page/watcher/conversation checkpoints into new operations, without performing storage inside the library.
- Test structured logging levels, correlation fields, and redaction using deliberately sensitive credentials, messages, addresses, attachment names, and provider failures.
- Add public API smoke tests for Gmail, Outlook, history, watching, conversations, and sending.
- Compile all README and example snippets, or mirror each snippet in a compile-tested example source, so documentation cannot drift from the API.
- Add architecture checks preventing public packages from importing Jakarta/Angus and application code from importing the Angus adapter directly.
- Add a repository-level legacy-symbol check covering Kotlin and Markdown sources for the deleted session types, device-code types, old top-level helpers, and public Jakarta imports.
- Run all checks on JDK 21.

## Acceptance Criteria

- Angus is an `implementation` dependency rather than an `api` dependency.
- No Jakarta or Angus type appears in the public API.
- Consumer operations read naturally through `mailbox.folders`, `mailbox.messages`, `mailbox.conversations`, and `mailbox.outbox`.
- Every reading and watching API can filter on envelope metadata before fetching MIME structure, then filter on part metadata before downloading bodies or attachments. Callers can consume envelopes or structures without downloading content.
- Consumers never manage raw connections, session managers, or reconnect loops.
- `Mailbox` is a complete lifecycle handle with deterministic state transitions, automatic generation-safe reconnection, explicit recovery after exhaustion, and idempotent resource cleanup.
- Multiple user mailboxes can coexist concurrently; credentials, tokens, state, connections, recovery, cursors, and shutdown are isolated by `MailboxId`.
- MailKT contains no persistence implementation; tokens and checkpoints cross explicit user-implemented ports or API boundaries.
- Lifecycle and operation logs are structured, useful for diagnosis, backend-neutral, and verified not to disclose sensitive mailbox data.
- Features do not duplicate recovery classification, MIME conversion, folder cleanup, or connection-generation handling.
- Gmail and Outlook share the same split frontend/backend authorization shape: generated browser URL, registered backend callback, validated code exchange, and silent refresh. Production uses HTTPS; local testing may use registered HTTP loopback callbacks. Neither provider contains a MailKT-owned listener or device-code API.
- `README.md` is a complete description of the new architecture and contains no legacy setup, imports, calls, or migration-era wording.
- Production code, tests, examples, KDoc, and Markdown contain no references to removed public APIs, except an intentionally maintained changelog or migration document if one is added later.
- Every README workflow has a corresponding compiling example or snippet test.
- Existing behavioral guarantees remain documented and tested.
- Production files have one clear responsibility and generally remain below roughly 250 lines.
- Tests and architecture/API checks pass on JDK 21.

## Assumptions

- Breaking existing source and binary compatibility is acceptable.
- Existing consuming projects will be migrated manually.
- MailKT runs entirely in the backend process; browser navigation belongs to the frontend and OAuth callbacks belong to backend HTTP routes supplied by the consuming application. The backend may be hosted remotely or run locally for development.
- MailKT-owned callback listeners, embedded browsers, SPA-owned refresh tokens, and device-code authentication are intentionally out of scope.
- Existing behavior is retained unless characterization exposes an actual bug.
- The four current Gradle modules remain unchanged for now.
- No raw Jakarta escape hatch is provided.
