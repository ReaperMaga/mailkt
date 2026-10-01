# Quick start

This walkthrough opens a real Gmail mailbox from a terminal, using a loopback redirect URI so no web server is needed.

## 1. Create Google credentials

1. In the [Google Cloud console](https://console.cloud.google.com/apis/credentials), create an **OAuth client ID** of type **Web application**.
2. Add `http://localhost:8080/oauth/gmail/callback` as an authorized redirect URI.
3. While the app is in testing, add your account as a test user on the OAuth consent screen.

## 2. Configure the environment

Export the credentials and the address you want to open:

::: code-group

```bash [macOS / Linux]
export GMAIL_CLIENT_ID="1234567890-abc.apps.googleusercontent.com"
export GMAIL_CLIENT_SECRET="your-client-secret"
export GMAIL_ADDRESS="you@gmail.com"
```

```powershell [Windows]
$env:GMAIL_CLIENT_ID = "1234567890-abc.apps.googleusercontent.com"
$env:GMAIL_CLIENT_SECRET = "your-client-secret"
$env:GMAIL_ADDRESS = "you@gmail.com"
```

:::

## 3. Implement the stores

MailKT asks you for two stores. For a local run, in-memory versions are enough:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Stores.kt#token-store{kotlin}

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Stores.kt#session-store{kotlin}

::: warning Demonstration only
These keep secrets in memory and lose them on restart. See [Persistence boundary](./persistence) for what a production store must guarantee.
:::

## 4. Authorize and open

A complete command-line program:

```kotlin
import dev.reapermaga.mailkt.client.use
import dev.reapermaga.mailkt.gmail.Gmail
import dev.reapermaga.mailkt.gmail.GmailConfig
import dev.reapermaga.mailkt.model.AuthorizationCallback
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.SpecialUse
import java.net.URI

suspend fun main() {
    val email = MailAddress(System.getenv("GMAIL_ADDRESS"))
    val redirect = URI("http://localhost:8080/oauth/gmail/callback")
    val config = GmailConfig(
        clientId = System.getenv("GMAIL_CLIENT_ID"),
        clientSecret = System.getenv("GMAIL_CLIENT_SECRET"),
        allowedRedirectUris = setOf(redirect),
    )
    val gmail = Gmail(config, InMemoryTokenStore(), InMemoryAuthorizationSessionStore())

    println("Open: ${gmail.beginAuthorization(email, redirect).authorizationUrl}")
    print("code: ")
    val code = readln()
    print("state: ")
    val state = readln()
    gmail.completeAuthorization(AuthorizationCallback(code, state))

    gmail.open(email).use { mailbox ->
        val inbox = mailbox.folders.special(SpecialUse.INBOX)
        println("Inbox found: ${inbox != null}")
    }
}
```

Run it, open the printed URL and consent. Google redirects to `localhost:8080`, which fails to load because nothing is listening. That is expected: copy the `code` and `state` query parameters from the address bar into the terminal.

## What just happened

1. `beginAuthorization` created a one-time `state` and PKCE verifier, saved them through your `AuthorizationSessionStore` and returned the Google URL.
2. `completeAuthorization` consumed that state exactly once, exchanged the code, verified the account matches `GMAIL_ADDRESS` and saved the tokens through your `TokenStore`.
3. `open` built the mailbox from the stored tokens using silent refresh only, connected, and returned a live `Mailbox`.
4. `use` closed it again, even on failure or cancellation.

In a real application, steps 1 and 2 are two HTTP endpoints of your backend. Continue with [Hosted authorization](/providers/authorization).
