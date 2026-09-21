package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.internal.mime.Descriptors
import dev.reapermaga.mailkt.internal.mime.readBounded
import dev.reapermaga.mailkt.model.*
import jakarta.mail.MessagingException
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.Properties

/** Converts raw RFC 822 bytes into a detached, immutable [MailMessage]; nothing live escapes. */
internal class AngusMimeParser(private val envelope: MessageEnvelope, private val maxBytes: Long) {
    private val attachments = mutableListOf<MailAttachment>()
    private var total = 0L

    fun parse(raw: ByteArray): MailMessage {
        if (raw.size > maxBytes) throw MailException.LimitExceeded(maxBytes)
        try {
            val msg = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))
            val content = convert(msg, PartWalker.rootSection(msg))
            return MailMessage(envelope, content, attachments.toList())
        } catch (e: MailException) {
            throw e
        } catch (e: MessagingException) {
            throw MailException.MalformedMessage(cause = e)
        } catch (e: IOException) {
            throw MailException.MalformedMessage(cause = e)
        }
    }

    private fun convert(p: Part, section: String): MimeContent {
        val type = Descriptors.mediaTypeOf(p.contentType)
        if (PartWalker.isMultipart(p)) {
            return MimeContent.Multipart(type, PartWalker.children(p).mapIndexed { i, c -> convert(c, Descriptors.childSection(section, i)) })
        }
        val disposition = Descriptors.disposition(p.disposition)
        val fileName = PartWalker.fileName(p)
        if (type == "message/rfc822" && disposition != PartDisposition.ATTACHMENT) {
            (p.content as? Part)?.let { return MimeContent.Embedded(type, convert(it, section)) }
        }
        val bytes = p.inputStream.use { it.readBounded((maxBytes - total).coerceAtLeast(0)) }
        total += bytes.size
        val isText = type.startsWith("text/") && disposition != PartDisposition.ATTACHMENT && fileName == null
        if (isText) return MimeContent.Text(type, String(bytes, charset(p)), charset(p).name())
        val ref = MessagePartRef(envelope.location, section)
        val cid = (p as? jakarta.mail.internet.MimePart)?.contentID
        val content = ByteContent(bytes)
        if (disposition == PartDisposition.ATTACHMENT || fileName != null) {
            attachments += MailAttachment(fileName ?: "attachment", type, content, cid, disposition == PartDisposition.INLINE, ref)
        }
        return MimeContent.Binary(type, ref, fileName, cid, disposition, content)
    }

    private fun charset(p: Part): Charset = try {
        Charset.forName(ContentType(p.contentType).getParameter("charset") ?: "UTF-8")
    } catch (_: Exception) {
        Charsets.UTF_8
    }
}
