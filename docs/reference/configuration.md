# Configuration

## GmailConfig

`dev.reapermaga.mailkt.gmail`

| Property | Type | Default | Description |
|---|---|---|---|
| `clientId` | `String` | | Google OAuth client ID. |
| `clientSecret` | `String` | | Google OAuth client secret. |
| `allowedRedirectUris` | `Set<URI>` | | Exact registered callback URIs. Must not be empty. |

```kotlin
Gmail(
    config: GmailConfig,
    tokenStore: TokenStore,
    authorizationSessionStore: AuthorizationSessionStore,
    connector: MailboxConnector = DefaultMailboxConnector,
)
```

## OutlookConfig

`dev.reapermaga.mailkt.outlook`

| Property | Type | Default | Description |
|---|---|---|---|
| `clientId` | `String` | | Entra application ID. |
| `clientSecret` | `String` | | Client secret. |
| `allowedRedirectUris` | `Set<URI>` | | Exact registered callback URIs. Must not be empty. |
| `authority` | `String` | `https://login.microsoftonline.com/common/` | Authority URL. Must be HTTPS. |
| `enableSending` | `Boolean` | `false` | Requests `SMTP.Send` and enables the outbox. |

```kotlin
Outlook(
    config: OutlookConfig,
    tokenStore: TokenStore,
    authorizationSessionStore: AuthorizationSessionStore,
    connector: MailboxConnector = DefaultMailboxConnector,
)
```

`connector` is an extension point for tests or custom transports; the default builds the managed IMAP/SMTP mailbox.

## Provider client functions

`Gmail` and `Outlook` expose the same three functions:

| Function | Returns | Description |
|---|---|---|
| `beginAuthorization(expectedEmail, redirectUri)` | `AuthorizationRequest` | Validates the URI and stores a pending session. |
| `completeAuthorization(callback)` | `MailboxId` | Validates and exchanges the callback, stores tokens. |
| `open(email, options = MailboxOptions())` | `Mailbox` | Connects using stored tokens and silent refresh. |

## Redirect URI rules

- Scheme `https`, or `http` on `localhost`, `127.0.0.1` or `[::1]`.
- No query string, no fragment.
- Must be in `allowedRedirectUris` exactly.

## MailboxOptions

| Property | Type | Default | Description |
|---|---|---|---|
| `connectionPolicy` | `ConnectionPolicy` | `ConnectionPolicy()` | Recovery behavior. |
| `outboxEnabled` | `Boolean` | `true` | Disable to force `outbox == null`. |
| `registry` | `MailboxRegistry?` | `null` | Enforces unique identities. |

## ConnectionPolicy

| Property | Type | Default | Description |
|---|---|---|---|
| `keepAliveInterval` | `Duration` | `30.seconds` | Health check interval. Must be positive. |
| `attemptTimeout` | `Duration` | `30.seconds` | Timeout per connection attempt. Must be positive. |
| `maxReconnectAttempts` | `Int` | `5` | Attempts before `Failed`. At least 1. |
| `initialBackoff` | `Duration` | `1.seconds` | Backoff before the first retry, doubling each attempt. |
| `maxBackoff` | `Duration` | `30.seconds` | Backoff cap. |
| `jitterFraction` | `Double` | `0.2` | Random jitter, within `0..1`. |

`backoff(attempt, random)` exposes the computed delay, which is useful in tests.

## Endpoints

| Provider | IMAP | SMTP |
|---|---|---|
| Gmail | `imap.gmail.com:993` (TLS) | `smtp.gmail.com:465` (TLS) |
| Outlook | `outlook.office365.com:993` (TLS) | `smtp.office365.com:587` (STARTTLS) |
