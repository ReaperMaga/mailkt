# Mailbox lifecycle

`Mailbox` is the single lifecycle handle for one account. It owns the connection, the state machine, the recovery loop, watchers and cursors.

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

## Opening

`open` suspends until the first authenticated connection succeeds. Failures are typed `MailException`s, and no partially initialized mailbox is ever returned.

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Lifecycle.kt#lifecycle-open{kotlin}

`MailboxOptions` is per mailbox; nothing in it is global:

| Option | Default | Description |
|---|---|---|
| `connectionPolicy` | `ConnectionPolicy()` | Keep-alive, timeouts and reconnect backoff. |
| `outboxEnabled` | `true` | Set to `false` to get `outbox == null` even if SMTP is available. |
| `registry` | `null` | Optional `MailboxRegistry` that rejects a second live mailbox with the same identity. |

## States

`state` is the authoritative snapshot. `events` is a bounded, best-effort diagnostic stream that you never need to reconstruct the state.

| State | Meaning |
|---|---|
| `Connected` | Operations run on the current connection generation. |
| `Reconnecting(attempt, reason)` | Automatic recovery after a network, socket, TLS or store failure. |
| `AuthenticationRequired` | Silent refresh failed. Run the hosted flow, then call `reconnect()`. |
| `Failed(cause, recoverable)` | Attempts exhausted. Idle until `reconnect()` or `close()`. |
| `Closed` | Terminal. Published exactly once. |

<StateDiagram />

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Lifecycle.kt#lifecycle-states{kotlin}

## Recovery

Recovery follows `ConnectionPolicy`:

| Setting | Default |
|---|---|
| `keepAliveInterval` | 30 seconds |
| `attemptTimeout` | 30 seconds |
| `maxReconnectAttempts` | 5 |
| `initialBackoff` | 1 second, doubling per attempt |
| `maxBackoff` | 30 seconds |
| `jitterFraction` | 0.2 (±20 %) |

How a reconnect behaves:

- Concurrent failures of one connection **generation** coalesce into a single reconnect.
- A replacement connection is authenticated **before** it is published, and the old one is closed exactly once.
- Operations pin the generation they started on. Reads resume or restart depending on their contract.
- **SMTP submission and your own consumer code are never retried.**

## Reconnecting after re-authorization

MailKT never navigates a browser on its own. After your application has completed a new hosted authorization for the account, resume the same mailbox:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Lifecycle.kt#lifecycle-reconnect{kotlin}

## Closing

`close()` is suspending and idempotent. It rejects new operations, cancels watchers and background work, and releases every folder, IDLE watcher and connection. `use` closes a mailbox around a block, even on failure or cancellation:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Lifecycle.kt#lifecycle-use{kotlin}

## Multiple mailboxes

Every `Mailbox` is fully independent. There is no global session manager: keep your own `Map<MailboxId, Mailbox>`, or use the optional `MailboxRegistry`, which only enforces identity uniqueness and closes all mailboxes concurrently. The same `TokenStore`, configuration and dispatchers can be shared safely.

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Lifecycle.kt#lifecycle-registry{kotlin}

| `MailboxRegistry` member | Description |
|---|---|
| `register(mailbox)` | Adds a mailbox. Throws `MailException.DuplicateMailbox` if a live mailbox has the same id. |
| `get(id)` / `ids()` | Lookup. |
| `unregister(id)` | Removes without closing. |
| `close()` | Closes all concurrently. One failure never prevents the others; returns a `RegistryCloseReport`. |

## In a real app

A backend usually keeps one long-lived `Mailbox` per connected account. This service opens them on startup, mirrors their state into your database so the UI can show "reconnect your account", resumes them after re-authorization, and closes them on shutdown.

::: info Your types
Types such as `AccountRepository` or `Database` below stand for your own application code. Everything else is MailKT API.
:::

```kotlin
class MailboxService(
    private val gmail: Gmail,
    private val accounts: AccountRepository, // your persistence
    private val scope: CoroutineScope,       // application scope, e.g. CoroutineScope(SupervisorJob())
) {
    private val registry = MailboxRegistry()

    /** On application startup: open every account that was connected before. */
    suspend fun start() {
        for (email in accounts.connectedAddresses()) {
            try {
                connect(email)
            } catch (e: MailException.AuthenticationRequired) {
                accounts.markNeedsReauthorization(email)
            } catch (e: MailException.ConnectionFailed) {
                accounts.markUnavailable(email) // try again later, e.g. from a scheduled job
            }
        }
    }

    suspend fun connect(email: MailAddress): Mailbox {
        val mailbox = gmail.open(email, MailboxOptions(registry = registry))
        mailbox.state
            .takeWhile { it != MailboxState.Closed }
            .onEach { state ->
                when (state) {
                    MailboxState.Connected -> accounts.markHealthy(email)
                    MailboxState.AuthenticationRequired -> accounts.markNeedsReauthorization(email)
                    is MailboxState.Failed -> accounts.markUnavailable(email)
                    else -> Unit // Reconnecting: MailKT is already on it
                }
            }
            .launchIn(scope)
        return mailbox
    }

    fun mailbox(id: MailboxId): Mailbox? = registry[id]

    /** Called by your OAuth callback route once completeAuthorization succeeded. */
    suspend fun authorized(id: MailboxId) {
        val existing = registry[id]
        if (existing != null && existing.state.value != MailboxState.Closed) {
            existing.reconnect() // same Mailbox: watchers and cursors keep working
        } else {
            connect(MailAddress(id.canonicalAddress))
        }
    }

    /** On application shutdown. */
    suspend fun stop() {
        val report = registry.close()
        if (!report.isClean) logger.warn("{} mailboxes failed to close", report.failures.size)
    }
}
```
