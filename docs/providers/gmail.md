# Gmail

```kotlin
implementation("dev.reapermaga.mailkt:gmail:0.1.0")
```

## Register the OAuth client

1. Open **APIs & Services → Credentials** in the [Google Cloud console](https://console.cloud.google.com/apis/credentials).
2. Create an **OAuth client ID** of type **Web application**.
3. Add every exact callback URI your backend uses, per environment, under **Authorized redirect URIs**.
4. Configure the OAuth consent screen. The full-mailbox scope `https://mail.google.com/` is a restricted scope: test users work immediately, public apps need Google's verification.

MailKT requests the scopes `https://mail.google.com/ openid email`, which cover IMAP, SMTP and identity verification.

## Configure

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Authorization.kt#gmail-setup{kotlin}

| Property | Description |
|---|---|
| `clientId` | OAuth client ID. |
| `clientSecret` | OAuth client secret. Keep it on the backend. |
| `allowedRedirectUris` | Exact callback URIs registered with Google. `beginAuthorization` rejects any other. |

## Begin

Return the URL to your frontend, which navigates the browser to it:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Authorization.kt#gmail-begin{kotlin}

## Complete and open

The route registered as redirect URI hands the query parameters to MailKT:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Authorization.kt#gmail-callback{kotlin}

`completeAuthorization` returns the `MailboxId` it stored tokens for. Open the mailbox now or later; `open` only needs stored tokens.

## Endpoints

| Protocol | Host | Port | Security |
|---|---|---|---|
| IMAP | `imap.gmail.com` | 993 | Implicit TLS, XOAUTH2 |
| SMTP | `smtp.gmail.com` | 465 | Implicit TLS, XOAUTH2 |

Gmail's SMTP service stores a copy of sent mail itself, so `send(saveToSent = true)` never appends a duplicate to Sent.

::: tip Gmail labels are folders
Over IMAP, Gmail labels appear as folders and `[Gmail]/All Mail` contains every message. The same message can therefore show up in several folders; [conversations](/guide/conversations) merge such copies.
:::
