package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.AuthorizationSessionStore
import dev.reapermaga.mailkt.client.TokenStore
import dev.reapermaga.mailkt.model.PendingAuthorization
import dev.reapermaga.mailkt.model.TokenKey
import java.util.concurrent.ConcurrentHashMap

// region token-store
/** Demonstration only. A real store persists opaque bytes durably, encrypted, with atomic replacement. */
class InMemoryTokenStore : TokenStore {
    private val values = ConcurrentHashMap<String, ByteArray>()

    override suspend fun load(key: TokenKey): ByteArray? = values[key.storageKey]?.copyOf()

    override suspend fun save(key: TokenKey, value: ByteArray) {
        values[key.storageKey] = value.copyOf()
    }

    override suspend fun delete(key: TokenKey) {
        values.remove(key.storageKey)
    }
}
// endregion

// region session-store
/** Demonstration only. A shared backend needs a store whose `consume` is atomic across instances. */
class InMemoryAuthorizationSessionStore : AuthorizationSessionStore {
    private val pending = ConcurrentHashMap<String, PendingAuthorization>()

    override suspend fun save(session: PendingAuthorization) {
        pending[session.state] = session
    }

    override suspend fun consume(state: String): PendingAuthorization? = pending.remove(state)
}
// endregion
