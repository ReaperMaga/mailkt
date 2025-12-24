package dev.reapermaga.mailkt.auth

interface TokenPersistenceStorage {

    fun store(token: String)

    fun load(): String?

}