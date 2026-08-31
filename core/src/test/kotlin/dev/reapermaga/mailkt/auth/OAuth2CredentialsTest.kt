package dev.reapermaga.mailkt.auth

import dev.reapermaga.mailkt.session.MailCredentials
import kotlin.test.Test
import kotlin.test.assertFalse

class OAuth2CredentialsTest {
    @Test
    fun `credential strings redact secrets`() {
        val oauth = OAuth2Credentials("user@example.com", "oauth-secret")
        val mail = MailCredentials.oauth2("user@example.com", "mail-secret")

        assertFalse("oauth-secret" in oauth.toString())
        assertFalse("mail-secret" in mail.toString())
    }
}
