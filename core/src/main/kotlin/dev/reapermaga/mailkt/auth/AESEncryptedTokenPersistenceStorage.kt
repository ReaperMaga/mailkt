package dev.reapermaga.mailkt.auth

import dev.reapermaga.mailkt.util.decryptAES
import dev.reapermaga.mailkt.util.encryptAES

class AESEncryptedTokenPersistenceStorage(val aesKey: String, val storage: TokenPersistenceStorage) :
    TokenPersistenceStorage {

    override fun store(token: String) {
        storage.store(encryptAES(token, aesKey))
    }

    override fun load(): String? {
        val token = storage.load() ?: return null
        return decryptAES(token, aesKey)
    }
}