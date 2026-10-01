package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.client.ImapEndpoint
import dev.reapermaga.mailkt.model.*
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.util.Properties
import kotlin.test.*

class AngusMimeTest {
    private fun p(a: String) = MailParticipant(MailAddress(a))
    private val loc = MessageLocation(MailboxId("test", "a@example.com"), FolderPath("INBOX"), 1, 1)
    private val envelope = MessageEnvelope(loc, "<x@example.com>", null)
    private val pdf = ByteArray(300) { (it % 251).toByte() }

    private fun draft(attach: Boolean = true) = Draft(
        from = p("a@example.com"), to = listOf(p("you@example.com")), bcc = listOf(p("hidden@example.com")),
        subject = "Grüße", text = "plain body", html = "<p>html body</p>", messageId = "<fixed@example.com>",
        inReplyTo = "<parent@example.com>", references = listOf("<root@example.com>", "<parent@example.com>"),
        attachments = if (attach) listOf(MailAttachment("Rechnung_äöü.pdf", "application/pdf", ByteContent(pdf))) else emptyList(),
    )

    private fun mime(raw: ByteArray) = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))

    @Test fun `build keeps Message-ID, threading headers and never writes Bcc`() {
        val built = AngusMimeBuilder.build(draft())
        assertEquals("<fixed@example.com>", built.messageId)
        val text = String(built.raw, Charsets.ISO_8859_1)
        assertTrue("<fixed@example.com>" in text)
        assertTrue("In-Reply-To: <parent@example.com>" in text)
        assertFalse("Bcc" in text || "hidden@example.com" in text)
        assertEquals(listOf("you@example.com", "hidden@example.com"), built.recipients.map { it.value })
    }

    @Test fun `build assigns a Message-ID when missing`() {
        val built = AngusMimeBuilder.build(draft().copy(messageId = null))
        assertTrue(Regex("<[^<>\\s]+>").matches(built.messageId))
    }

    @Test fun `round trip decodes nested multipart, body text and encoded attachment names`() {
        val built = AngusMimeBuilder.build(draft())
        val parsed = AngusMimeCodec.parse(built.raw, envelope, 1_000_000)
        assertEquals("plain body", parsed.plainText?.trim())
        assertTrue(parsed.html!!.contains("html body"))
        val a = parsed.attachments.single()
        assertEquals("Rechnung_äöü.pdf", a.fileName)
        assertEquals("application/pdf", a.mediaType)
        assertContentEquals(pdf, a.content.toByteArray())
        assertNotNull(a.ref)
    }

    @Test fun `structure sections agree with selective part access`() {
        val msg = mime(AngusMimeBuilder.build(draft()).raw)
        val root = PartWalker.describe(msg, loc, PartWalker.rootSection(msg))
        assertEquals("0", root.ref.section)
        assertEquals(listOf("1", "2"), root.children.map { it.ref.section })
        assertEquals(listOf("1.1", "1.2"), root.children[0].children.map { it.ref.section })
        val attachment = root.children[1]
        assertTrue(attachment.isAttachment && attachment.isPdf)
        assertEquals("Rechnung_äöü.pdf", attachment.fileName)
        val part = PartWalker.resolve(msg, "2")!!
        assertContentEquals(pdf, part.inputStream.readBytes())
        assertNull(PartWalker.resolve(msg, "9"))
        assertNull(PartWalker.resolve(msg, "0"))
        assertNull(PartWalker.resolve(msg, "x"))
    }

    @Test fun `single part messages use section 1`() {
        val msg = mime(AngusMimeBuilder.build(draft(attach = false).copy(html = null)).raw)
        assertEquals("1", PartWalker.rootSection(msg))
        assertNotNull(PartWalker.resolve(msg, "1"))
    }

    @Test fun `oversized messages fail with the limit and never truncate silently`() {
        val raw = AngusMimeBuilder.build(draft()).raw
        assertFailsWith<MailException.LimitExceeded> { AngusMimeCodec.parse(raw, envelope, 100) }
        assertFailsWith<MailException.LimitExceeded> { AngusMimeCodec.parse(raw, envelope, raw.size.toLong() - 1) }
    }

    @Test fun `header injection and missing recipients are rejected`() {
        assertFailsWith<MailException.MalformedMessage> { AngusMimeBuilder.build(draft().copy(subject = "Hi\r\nBcc: evil@example.com")) }
        assertFailsWith<MailException.MalformedMessage> { AngusMimeBuilder.build(draft().copy(inReplyTo = "<a>\r\nX: y")) }
        assertFailsWith<MailException.MalformedMessage> { AngusMimeBuilder.build(draft().copy(to = emptyList(), bcc = emptyList())) }
        assertFailsWith<MailException.MalformedMessage> { AngusMimeBuilder.build(draft().copy(messageId = "not-an-id")) }
        assertFailsWith<MailException.MalformedMessage> {
            AngusMimeBuilder.build(draft().copy(attachments = listOf(MailAttachment("a\r\nb.pdf", "application/pdf", ByteContent(pdf)))))
        }
    }

    @Test fun `garbage input never leaks Jakarta exceptions`() {
        val r = runCatching { AngusMimeCodec.parse("\u0000\u0001 not mime".toByteArray(), envelope, 1000) }
        r.exceptionOrNull()?.let { assertIs<MailException>(it) }
    }

    @Test fun `flags map both ways`() {
        val f = MessageFlags(seen = true, flagged = true, keywords = setOf("Invoice"))
        assertEquals(f, EnvelopeMapper.flags(EnvelopeMapper.toJakarta(f)))
    }

    @Test fun `properties enforce TLS verification, XOAUTH2 only and non-marking reads`() {
        val imap = AngusProperties.imap(ImapEndpoint("imap.example.com"))
        assertEquals("true", imap.getProperty("mail.imap.ssl.checkserveridentity"))
        assertEquals("XOAUTH2", imap.getProperty("mail.imap.auth.mechanisms"))
        assertEquals("true", imap.getProperty("mail.imap.peek"))
        assertEquals("true", imap.getProperty("mail.imap.auth.plain.disable"))
        val implicit = AngusProperties.smtp(ImapEndpoint("i", smtpHost = "smtp.example.com", smtpPort = 465))
        assertEquals("true", implicit.getProperty("mail.smtp.ssl.enable"))
        assertNull(implicit.getProperty("mail.smtp.ssl.trust"))
        val starttls = AngusProperties.smtp(ImapEndpoint("i", smtpHost = "smtp.example.com", smtpPort = 587, smtpStartTls = true))
        assertEquals("true", starttls.getProperty("mail.smtp.starttls.required"))
        assertEquals("false", starttls.getProperty("mail.smtp.sendpartial"))
    }
}
