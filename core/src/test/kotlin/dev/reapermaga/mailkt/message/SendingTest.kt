package dev.reapermaga.mailkt.message

import dev.reapermaga.mailkt.folder.MessageFilter
import dev.reapermaga.mailkt.session.MailCredentials
import jakarta.mail.*
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.*

class SendingTest {
    @Test fun `reply preserves relationships and excludes self and Bcc`() {
        val parent = composeMessage("contact@example.com", listOf("me@example.com", "other@example.com"),
            "Invoice", "normal email", listOf("copy@example.com"), listOf("hidden@example.com"))
        parent.setHeader("Reply-To", "reply@example.com")
        parent.setHeader("References", "<earlier@example.com>")
        val reply = composeReply(parent, "me@example.com", "Thank you", replyAll = true)
        assertEquals("Re: Invoice", reply.subject)
        assertEquals(parent.messageID, reply.getHeader("In-Reply-To", null))
        assertEquals("<earlier@example.com> ${parent.messageID}", reply.getHeader("References", null))
        assertEquals(listOf("reply@example.com", "other@example.com"), reply.getRecipients(Message.RecipientType.TO).map { it.toString() })
        assertEquals(listOf("copy@example.com"), reply.getRecipients(Message.RecipientType.CC).map { it.toString() })
        assertNull(reply.getRecipients(Message.RecipientType.BCC))
        assertEquals(listOf("reply@example.com"), composeReply(parent, "me@example.com", "Hi").allRecipients.map { it.toString() })
        val outgoing = composeMessage("me@example.com", listOf("contact@example.com"), "Question", "Hello")
        assertEquals(listOf("contact@example.com"), composeReply(outgoing, "me@example.com", "Follow-up").allRecipients.map { it.toString() })
    }

    @Test fun `filter matches full addresses and explicit thread tokens only`() {
        val incoming = composeMessage("contact@example.com", listOf("me@example.com"), "Invoice", "no attachment")
        val outgoing = composeMessage("me@example.com", listOf("contact@example.com"), "Invoice", "Reply")
        val unrelated = composeMessage("other@example.com", listOf("me@example.com"), "Invoice", "same domain")
        val filter = MessageFilter(setOf("CONTACT@example.com"))
        assertTrue(filter.matches(incoming))
        assertTrue(filter.matches(outgoing))
        assertFalse(filter.matches(unrelated))
        unrelated.setHeader("References", "<root@example.com> <second@example.com>")
        assertTrue(MessageFilter(threadMessageIds = setOf("<root@example.com>")).matches(unrelated))
        assertFalse(MessageFilter(threadMessageIds = setOf("<oot@example.com>")).matches(unrelated))
    }

    @Test fun `reject header injection and missing recipients`() {
        assertFailsWith<IllegalArgumentException> { composeMessage("me@example.com", emptyList(), "Hi", "Hi") }
        assertFailsWith<IllegalArgumentException> { composeMessage("me@example.com", listOf("you@example.com"), "Hi\r\nBcc: bad@example.com", "Hi") }
        assertFailsWith<IllegalArgumentException> { MessageFilter(setOf("bad\r\n@example.com")) }
    }

    @Test fun `SMTP requires TLS and XOAUTH2 without password fallback`() {
        val properties = SmtpConfig("smtp.example.com").properties(MailCredentials.oauth2("me@example.com", "token"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertEquals("XOAUTH2", properties.getProperty("mail.smtp.auth.mechanisms"))
        assertNull(properties.getProperty("mail.smtp.ssl.trust"))
    }

    @Test fun `submission preserves ID distinguishes failures and never retries`() = runBlocking<Unit> {
        val draft = composeMessage("me@example.com", listOf("you@example.com"), "Hello", "Hello")
        val id = draft.messageID
        suspend fun send(connectFailure: Boolean = false, submissionFailure: Boolean = false, closeFailure: Boolean = false): Pair<SendResult, Int> {
            var submissions = 0
            val result = submitMessage(SmtpConfig("smtp.example.com"), MailCredentials.oauth2("me@example.com", "token"),
                draft, Dispatchers.IO) { session ->
                object : Transport(session, null) {
                    override fun protocolConnect(host: String?, port: Int, user: String?, password: String?): Boolean {
                        if (connectFailure) throw AuthenticationFailedException("Rejected")
                        return true
                    }
                    override fun sendMessage(message: Message, addresses: Array<out Address>) {
                        submissions++
                        if (submissionFailure) throw MessagingException("Lost acknowledgement", IOException())
                    }
                    override fun close() { if (closeFailure) throw MessagingException("QUIT failed") }
                }
            }
            assertEquals(id, result.messageId)
            assertEquals(id, draft.messageID)
            return result to submissions
        }
        assertEquals(SendStatus.ACCEPTED, send(closeFailure = true).first.status)
        assertEquals(SendStatus.FAILED, send(connectFailure = true).first.status)
        val unknown = send(submissionFailure = true)
        assertEquals(SendStatus.UNKNOWN, unknown.first.status)
        assertEquals(1, unknown.second)
        assertFailsWith<IllegalArgumentException> {
            submitMessage(SmtpConfig("smtp.example.com"), MailCredentials.oauth2("wrong@example.com", "token"), draft, Dispatchers.IO)
        }
    }
}
