package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.model.PendingAuthorization
import dev.reapermaga.mailkt.model.TokenKey

/**
 * User-implemented token persistence. Values are opaque. Implementations must provide atomic
 * replacement and isolation for concurrent access; encryption and retention are the application's job.
 */
interface TokenStore {
    suspend fun load(key: TokenKey): ByteArray?
    suspend fun save(key: TokenKey, value: ByteArray)
    suspend fun delete(key: TokenKey)
}

/** User-implemented store for pending authorizations (sensitive; expire after ten minutes). */
interface AuthorizationSessionStore {
    suspend fun save(session: PendingAuthorization)

    /** Must atomically remove and return the session, so it can be consumed exactly once. */
    suspend fun consume(state: String): PendingAuthorization?
}
