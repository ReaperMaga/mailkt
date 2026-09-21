package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.internal.transport.BuiltMessage
import dev.reapermaga.mailkt.model.*
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.*
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Properties

/** Builds RFC 822 bytes from a [Draft]. Header injection is rejected; Bcc never appears in the bytes. */
internal object AngusMimeBuilder {
    private val ID = Regex("<[^<>\\s]+>")

    fun build(draft: Draft): BuiltMessage {
        val recipients = (draft.to + draft.cc + draft.bcc).map { it.address }.distinctBy { it.normalized }
        if (recipients.isEmpty()) throw MailException.MalformedMessage("At least one recipient is required")
        single(draft.subject)
        draft.messageId?.let { if (!ID.matches(it)) throw MailException.MalformedMessage("Invalid Message-ID") }
        val msg = MimeMessage(Session.getInstance(Properties()))
        msg.setFrom(address(draft.from))
        msg.setRecipients(Message.RecipientType.TO, draft.to.map { address(it) }.toTypedArray())
        msg.setRecipients(Message.RecipientType.CC, draft.cc.map { address(it) }.toTypedArray())
        msg.setRecipients(Message.RecipientType.BCC, draft.bcc.map { address(it) }.toTypedArray())
        msg.setSubject(draft.subject, "UTF-8")
        msg.sentDate = Date()
        setBody(msg, draft)
        msg.saveChanges()
        val id = draft.messageId ?: msg.messageID ?: ("<" + java.util.UUID.randomUUID() + "@" + draft.from.address.value.substringAfter('@') + ">")
        msg.setHeader("Message-ID", id)
        draft.inReplyTo?.let { single(it); msg.setHeader("In-Reply-To", it) }
        if (draft.references.isNotEmpty()) msg.setHeader("References", draft.references.onEach { single(it) }.joinToString(" "))
        val out = ByteArrayOutputStream()
        msg.writeTo(out, arrayOf("Bcc", "Content-Length"))
        return BuiltMessage(out.toByteArray(), id, recipients)
    }

    private fun setBody(part: MimePart, d: Draft) {
        val text = d.text
        val html = d.html
        val body: MimeMultipart? = when {
            text != null && html != null -> MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
                addBodyPart(MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") })
            }
            else -> null
        }
        if (d.attachments.isEmpty()) {
            when {
                body != null -> part.setContent(body)
                html != null -> part.setContent(html, "text/html; charset=UTF-8")
                else -> part.setText(text.orEmpty(), "UTF-8")
            }
            return
        }
        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(MimeBodyPart().also { b ->
            when {
                body != null -> b.setContent(body)
                html != null -> b.setContent(html, "text/html; charset=UTF-8")
                else -> b.setText(text.orEmpty(), "UTF-8")
            }
        })
        d.attachments.forEach { a -> mixed.addBodyPart(attachment(a)) }
        part.setContent(mixed)
    }

    private fun attachment(a: MailAttachment) = MimeBodyPart().apply {
        val name = single(a.fileName)
        dataHandler = DataHandler(ByteArrayDataSource(a.content.toByteArray(), a.mediaType))
        fileName = name
        disposition = if (a.inline) Part.INLINE else Part.ATTACHMENT
        a.contentId?.let { setContentID(single(it)) }
    }

    private fun address(p: MailParticipant) = InternetAddress(single(p.address.value), p.displayName?.let { single(it) }, "UTF-8")

    private fun single(v: String): String {
        if ('\r' in v || '\n' in v) throw MailException.MalformedMessage("Header values must be single line")
        return v
    }
}
