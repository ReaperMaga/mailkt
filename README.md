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
IMAP IDLE automatically.

```kotlin
watchFolder(session, "INBOX").collect { message ->
    println("New message: ${message.subject}")
}
```

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
