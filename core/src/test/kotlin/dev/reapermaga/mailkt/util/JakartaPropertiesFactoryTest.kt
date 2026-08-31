package dev.reapermaga.mailkt.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JakartaPropertiesFactoryTest {
    @Test
    fun `OAuth properties verify the TLS host and do not trust every certificate`() {
        val properties = JakartaPropertiesFactory.oauth2("imap.example.com")

        assertEquals("true", properties["mail.imap.ssl.checkserveridentity"])
        assertNull(properties["mail.imap.ssl.trust"])
        assertEquals("XOAUTH2", properties["mail.imap.auth.mechanisms"])
    }
}
