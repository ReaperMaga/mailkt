package dev.reapermaga.mailkt.util

import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

fun generateAESKey(size: Int = 128): String {
    val keyGen = KeyGenerator.getInstance("AES")
    keyGen.init(size)
    val secretKey = keyGen.generateKey()
    return secretKey.encoded.joinToString("") { "%02x".format(it) }
}

fun encryptAES(data: String, key: String): String {
    val cipher = Cipher.getInstance("AES")
    val secretKey = SecretKeySpec(hexStringToByteArray(key), "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(encrypted)
}

fun decryptAES(data: String, key: String): String {
    val cipher = Cipher.getInstance("AES")
    val secretKey = SecretKeySpec(hexStringToByteArray(key), "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKey)
    val decrypted = cipher.doFinal(Base64.getDecoder().decode(data))
    return String(decrypted, Charsets.UTF_8)
}

private fun hexStringToByteArray(value: String): ByteArray {
    val len = value.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] =
            ((Character.digit(value[i], 16) shl 4) + Character.digit(value[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
