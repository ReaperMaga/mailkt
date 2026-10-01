# Persistence boundary

MailKT ships **no** file, database, cache, encryption or secret-store implementation. Instead it defines two narrow suspending interfaces and returns checkpoints as plain values. Where and how they are stored is your decision.

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

## TokenStore

Holds each mailbox's OAuth token cache as opaque bytes owned by the provider adapter.

- `TokenKey` combines the provider, the client registration (client ID) and the `MailboxId`. Tokens of different providers, apps or mailboxes can never overwrite each other. Use `key.storageKey` as a stable string key.
- Implementations must **replace values atomically** and isolate concurrent access.
- Encryption, key management, retention, backup and deletion are the application's responsibility.

::: danger Treat the bytes as secrets
The stored value contains refresh tokens. Encrypt it at rest and never log it. `TokenKey.storageKey` contains the mailbox address, so avoid logging that too.
:::

## AuthorizationSessionStore

Holds pending hosted authorizations between `beginAuthorization` and the provider callback.

- `consume` **must be atomic and one-time**: remove and return in a single step. That is what prevents callback replay.
- Pending sessions contain the PKCE verifier and expire after ten minutes (`PendingAuthorization.TTL_MINUTES`).
- With several backend instances, use a shared store (database row with `DELETE ... RETURNING`, Redis `GETDEL`, ...) so the callback can land on any instance.

## Checkpoints

`ScanCheckpoint`, `WatchCheckpoint` and `ConversationCheckpoint` are immutable data classes made of primitives plus a `formatVersion`. Operations accept a previous checkpoint and return the next one. MailKT never saves or acknowledges them.

```kotlin
data class WatchCheckpoint(
    val mailboxKey: String,
    val folder: String,
    val uidValidity: Long,
    val lastUid: Long,
    val formatVersion: Int = CHECKPOINT_FORMAT_VERSION,
)
```

Persist a checkpoint **only after your own processing is durable**. That single rule is what gives you at-least-once processing across restarts. Serialize them however you like; a checkpoint from another mailbox, folder or unsupported version is rejected with `MailException.InvalidCheckpoint`.

## Demonstration implementations

These are fine for tests and local runs, not for production:

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Stores.kt#token-store{kotlin}

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Stores.kt#session-store{kotlin}

## A production sketch

A JDBC-backed session store that consumes atomically might look like this:

```kotlin
class JdbcAuthorizationSessionStore(private val db: DataSource) : AuthorizationSessionStore {
    override suspend fun save(session: PendingAuthorization) = withContext(Dispatchers.IO) {
        db.connection.use { c ->
            c.prepareStatement("INSERT INTO oauth_pending (state, payload, expires_at) VALUES (?, ?, ?)").use {
                it.setString(1, session.state)
                it.setBytes(2, encrypt(serialize(session)))
                it.setTimestamp(3, Timestamp.from(session.expiresAt))
                it.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun consume(state: String): PendingAuthorization? = withContext(Dispatchers.IO) {
        db.connection.use { c ->
            // One statement: the row can be consumed exactly once, even across instances.
            c.prepareStatement("DELETE FROM oauth_pending WHERE state = ? RETURNING payload").use {
                it.setString(1, state)
                it.executeQuery().use { rs -> if (rs.next()) deserialize(decrypt(rs.getBytes(1))) else null }
            }
        }
    }
}
```

`serialize`, `encrypt` and friends are yours to provide. MailKT only validates what comes back.
