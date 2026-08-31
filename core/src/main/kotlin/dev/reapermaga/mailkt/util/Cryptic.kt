package dev.reapermaga.mailkt.util

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun generateAESKey(size: Int = 256): String {
    require(size in setOf(128, 192, 256)) { "AES key size must be 128, 192, or 256 bits" }
    val keyGenerator = KeyGenerator.getInstance("AES")
    keyGenerator.init(size)
    return keyGenerator.generateKey().encoded.toHexString()
}

/** Encrypts with authenticated AES-GCM and a fresh random nonce. */
fun encryptAES(data: String, key: String): String {
    val keyBytes = key.hexToByteArray().also(::requireValidAesKey)
    val nonce = ByteArray(GCM_NONCE_BYTES).also(SecureRandom()::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
    val ciphertext = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
    return GCM_PREFIX + Base64.getEncoder().encodeToString(nonce + ciphertext)
}

/** Decrypts AES-GCM values and supports legacy ECB values for migration. */
fun decryptAES(data: String, key: String): String {
    val keyBytes = key.hexToByteArray().also(::requireValidAesKey)
    if (!data.startsWith(GCM_PREFIX)) return decryptLegacyAes(data, keyBytes)

    val payload = Base64.getDecoder().decode(data.removePrefix(GCM_PREFIX))
    require(payload.size > GCM_NONCE_BYTES) { "Invalid AES-GCM payload" }
    val nonce = payload.copyOfRange(0, GCM_NONCE_BYTES)
    val ciphertext = payload.copyOfRange(GCM_NONCE_BYTES, payload.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
    return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
}

private fun decryptLegacyAes(data: String, keyBytes: ByteArray): String {
    val cipher = Cipher.getInstance("AES")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
    return String(cipher.doFinal(Base64.getDecoder().decode(data)), Charsets.UTF_8)
}

private fun requireValidAesKey(bytes: ByteArray) {
    require(bytes.size in setOf(16, 24, 32)) { "Invalid AES key length" }
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex value must contain an even number of characters" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
            ?: throw IllegalArgumentException("Invalid hexadecimal AES key")
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private const val GCM_PREFIX = "gcm:v1:"
private const val GCM_NONCE_BYTES = 12
