# mailkt

Kotlin/JVM helpers for working with email over IMAP, with a ready-to-use **Outlook (Microsoft 365) OAuth2** implementation.

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
val oauth = OutlookOAuth2MailAuth(clientId, null) {
    println("To sign in, open ${it.verificationUri} and enter code ${it.code}")
}
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

## API overview

### `:core`

- `MailSession` — a minimal abstraction around a `jakarta.mail.Session` and IMAP `Store`
- `MailAuthMethod` — currently `PLAIN` and `OAUTH2` (not all sessions implement both)
- `JakartaPropertiesFactory` — helper to build IMAP properties (XOAUTH2)
- `OAuth2MailAuth` / `OAuth2MailUser` — OAuth2 login interface + result model
- `OAuth2TokenPersistenceStorage` — optional interface for caching tokens
- `FileOAuth2TokenPersistenceStorage` — basic JSON file cache (`oauth2_tokens.json`)

### `:outlook`

- `OutlookOAuth2MailAuth` — device-code OAuth2 login using **MSAL4J**
- `OutlookMailSession` — IMAP connection to `outlook.office365.com` (XOAUTH2)

## Notes / limitations

- `OutlookMailSession` currently implements **only** `MailAuthMethod.OAUTH2` (other methods throw `NotImplementedError`).
- The Outlook IMAP host is currently hardcoded to `outlook.office365.com`.
- The OAuth authority is set to the Microsoft “consumers” tenant (`/consumers`). If you need organizational tenants, you’ll want to make this configurable.

## License

MIT — see [LICENSE](LICENSE) for details.
