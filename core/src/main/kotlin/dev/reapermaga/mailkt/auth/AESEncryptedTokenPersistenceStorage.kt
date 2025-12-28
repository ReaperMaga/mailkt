package dev.reapermaga.mailkt.auth

import dev.reapermaga.mailkt.util.decryptAES
import dev.reapermaga.mailkt.util.encryptAES

/**
 * Decorates a [TokenPersistenceStorage] to transparently encrypt tokens with AES before
 * persistence.
 *
 * @property aesKey symmetric key used for encryption/decryption.
 * @property storage backing storage that ultimately stores the ciphertext.
 */
class AESEncryptedTokenPersistenceStorage(
    val aesKey: String,
    val storage: TokenPersistenceStorage,
) : TokenPersistenceStorage {

    /** Encrypts the provided token with [aesKey] and forwards it to the backing storage. */
    override fun store(token: String) {
        storage.store(encryptAES(token, aesKey))
    }

    /** Loads the encrypted token, decrypts it with [aesKey], and returns the plaintext value. */
    override fun load(): String? {
        val token = storage.load() ?: return null
        return decryptAES(token, aesKey)
    }
}
