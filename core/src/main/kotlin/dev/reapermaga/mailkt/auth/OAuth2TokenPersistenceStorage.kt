package dev.reapermaga.mailkt.auth

interface OAuth2TokenPersistenceStorage {

    fun store(token: String)

    fun load(): String?

}