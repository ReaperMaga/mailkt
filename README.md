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
bodies. New arrivals cannot shift it during retries. Expunged requested messages, changed
UIDVALIDITY, and missing/nonpersistent UID support fail explicitly. If initial range resolution
is interrupted, the scan fails with `operation=resolve-range`; it cannot safely reconstruct a
position range whose original membership was never established.

Downloads resume at the interrupted UID. Recoverable nested folder/store/socket errors are retried
against a usable managed connection. A known-dead store waits for a replacement. Each message has
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

The default flow has no prefetch or buffer and opens/closes a folder per snapshot/download attempt.
It retains O(selected UIDs) metadata and one message body at a time. Serialization/parsing may need
several copies of that body; the size cap limits serialized MIME bytes, including unknown-size
messages, rather than total heap usage or later decoded attachments. Adding `.buffer(n)` adds up
to `n` queued detached copies plus an in-flight message. No executors or listener jobs are created.
`HistoricalReadException` exposes `operation`, `uid`, `attempt`, and the original cause; recovery
deadline failures also preserve the last transport failure. The reader logs no message contents or
credentials.

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
