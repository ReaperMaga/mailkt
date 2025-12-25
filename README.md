# mailkt

Kotlin/JVM helpers for working with email over IMAP, with a ready-to-use **Outlook (Microsoft 365) OAuth2**
implementation.

**Highlights**

- Minimal IMAP abstraction layer over Jakarta Mail with Kotlin-first ergonomics.
- Pluggable OAuth2 auth layer that separates token acquisition from session usage.
- Ready-to-run Outlook sample demonstrating device-code sign-in + IMAP access.

**Project structure**

| Module     | Purpose |
| ---------- | ------- |
| `:core`    | Common mail abstractions, auth models, token persistence helpers. |
| `:outlook` | Outlook-specific OAuth2 (MSAL) + IMAP session to `outlook.office365.com`. |
| `:examples`| Runnable samples showcasing end-to-end sign-in and mailbox access. |

This repo is organized as a small multi-module Gradle build:

- `:core` — common mail abstractions and utilities (Jakarta Mail + Kotlinx)
- `:outlook` — Outlook-specific OAuth2 auth (MSAL) + IMAP session implementation
- `:examples` — small runnable examples (currently: Outlook IMAP connect)

## Requirements

- **JDK 21** (the build uses a Java toolchain set to 21)

## Build

Use the Gradle wrapper (recommended).

On Windows:

- Build: `./gradlew.bat build`
- Run all checks: `./gradlew.bat check`

On macOS/Linux:

- Build: `./gradlew build`
- Run all checks: `./gradlew check`
## Basic example

The example lives at `examples/src/main/java/dev/reapermaga/mailkt/examples/Outlook.kt` and looks like this (trimmed):

```kotlin
val oauth = OutlookOAuth2MailAuth(clientId)

oauth.deviceLogin {
  println("To sign in, open ${it.verificationUri} and enter code ${it.code}")
}.join()

val user = oauth.login().join()

val session = OutlookMailSession()
val connection = session.connect(
    method = MailAuthMethod.OAUTH2,
    username = user.username!!,
    password = user.accessToken!!
).join()

val folder = session.currentStore.getFolder("INBOX")
folder.open(jakarta.mail.Folder.READ_ONLY)
println("Connected, total messages: ${folder.messageCount}")
```
## Notes / limitations

- `OutlookMailSession` currently implements **only** `MailAuthMethod.OAUTH2` (other methods throw
  `NotImplementedError`).
- The Outlook IMAP host is currently hardcoded to `outlook.office365.com`.
- The OAuth authority is set to the Microsoft “consumers” tenant (`/consumers`). If you need organizational tenants,
  you’ll want to make this configurable.

## Contributing

Feel free to open discussions or PRs for new providers, auth flows, or utility improvements.

## License

MIT — see [LICENSE](LICENSE) for details.
