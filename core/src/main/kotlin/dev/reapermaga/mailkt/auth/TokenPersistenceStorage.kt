package dev.reapermaga.mailkt.auth

/** Abstraction responsible for persisting and retrieving OAuth tokens between mail sessions. */
interface TokenPersistenceStorage {

    /** Persist the latest OAuth token payload for future reuse. */
    fun store(token: String)

    /** Load the previously stored token or return null when unavailable. */
    fun load(): String?
}
