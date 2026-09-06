package dev.reapermaga.mailkt.gmail

import dev.reapermaga.mailkt.auth.TokenPersistenceStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GmailOAuth2MailAuthTest {

    @Test
    fun `detects a persisted credential`() = runBlocking {
        val storage = MemoryTokenStorage()
        storage.store(
            """{"key":"gmail","accessToken":"access","refreshToken":"refresh","expirationTimeMilliseconds":4102444800000}"""
        )

        val auth = GmailOAuth2MailAuth(config(), storage)

        assertTrue(auth.hasToken())
    }

    @Test
    fun `reports no token when storage is empty`() = runBlocking {
        val auth = GmailOAuth2MailAuth(config(), MemoryTokenStorage())

        assertFalse(auth.hasToken())
    }

    @Test
    fun `requires the Gmail IMAP scope`() {
        assertFailsWith<IllegalArgumentException> {
            GmailOAuth2Config(
                clientId = "client-id",
                clientSecret = "client-secret",
                scopes = setOf(GmailOAuth2Config.EMAIL_SCOPE),
            )
        }
    }

    @Test
    fun `configuration string redacts the client secret`() {
        assertFalse("client-secret" in config().toString())
    }

    private fun config() = GmailOAuth2Config.installedApp("client-id", "client-secret")

    private class MemoryTokenStorage : TokenPersistenceStorage {
        private var value: String? = null

        override fun store(token: String) {
            value = token
        }

        override fun load(): String? = value
    }
}
