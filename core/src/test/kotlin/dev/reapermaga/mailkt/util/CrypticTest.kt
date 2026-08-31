package dev.reapermaga.mailkt.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CrypticTest {
    @Test
    fun `AES-GCM round trip uses a random nonce`() {
        val key = generateAESKey()
        val first = encryptAES("secret token", key)
        val second = encryptAES("secret token", key)

        assertTrue(first.startsWith("gcm:v1:"))
        assertNotEquals(first, second)
        assertEquals("secret token", decryptAES(first, key))
        assertEquals("secret token", decryptAES(second, key))
    }

    @Test
    fun `encryption is safe across concurrent callers`() = runBlocking {
        val key = generateAESKey()

        val encrypted =
            (1..100)
                .map { index ->
                    async(Dispatchers.Default) {
                        val plaintext = "token-$index"
                        plaintext to encryptAES(plaintext, key)
                    }
                }
                .awaitAll()

        assertEquals(100, encrypted.map { it.second }.toSet().size)
        encrypted.forEach { (plaintext, ciphertext) ->
            assertEquals(plaintext, decryptAES(ciphertext, key))
        }
    }
}
