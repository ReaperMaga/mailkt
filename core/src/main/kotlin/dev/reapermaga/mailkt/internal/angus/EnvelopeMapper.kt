package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.model.*
import jakarta.mail.Address
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility

/** Maps a fetched IMAP message header set to a [MessageEnvelope]. Reads metadata only, never content. */
internal object EnvelopeMapper {
    /** Header names requested with every envelope fetch (also exposed via [MessageEnvelope.headers]). */
    val HEADERS = listOf(
        "Message-ID", "In-Reply-To", "References", "Content-Type", "List-Id", "Return-Path", "Auto-Submitted", "Precedence",
    )
    private val ID = Regex("<[^<>\\s]+>")

    fun map(msg: Message, location: MessageLocation): MessageEnvelope {
        val headers = HEADERS.mapNotNull { n -> (msg as? MimeMessage)?.getHeader(n)?.toList()?.takeIf { it.isNotEmpty() }?.let { n to it } }.toMap()
        fun first(name: String) = headers[name]?.firstOrNull()
        val messageId = first("Message-ID")?.let { ID.find(it)?.value ?: it.trim() }
        return MessageEnvelope(
            location = location,
            messageId = messageId,
            inReplyTo = first("In-Reply-To")?.let { ID.find(it)?.value },
            references = headers["References"].orEmpty().flatMap { h -> ID.findAll(h).map { it.value }.toList() },
            from = participants(msg.from),
            to = participants(msg.getRecipients(Message.RecipientType.TO)),
            cc = participants(msg.getRecipients(Message.RecipientType.CC)),
            replyTo = participants(msg.replyTo),
            subject = msg.subject?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) },
            sentAt = msg.sentDate?.toInstant(),
            receivedAt = msg.receivedDate?.toInstant(),
            flags = flags(msg.flags),
            contentType = first("Content-Type"),
            advertisedSize = msg.size.toLong().takeIf { it >= 0 },
            headers = headers,
        )
    }

    private fun participants(addresses: Array<out Address>?): List<MailParticipant> =
        addresses.orEmpty().mapNotNull { a ->
            val ia = a as? InternetAddress ?: return@mapNotNull null
            runCatching { MailParticipant(MailAddress(ia.address), ia.personal) }.getOrNull()
        }

    fun flags(f: Flags) = MessageFlags(
        seen = f.contains(Flags.Flag.SEEN), answered = f.contains(Flags.Flag.ANSWERED),
        flagged = f.contains(Flags.Flag.FLAGGED), deleted = f.contains(Flags.Flag.DELETED),
        draft = f.contains(Flags.Flag.DRAFT), keywords = f.userFlags.toSet(),
    )

    fun toJakarta(f: MessageFlags): Flags = Flags().apply {
        if (f.seen) add(Flags.Flag.SEEN)
        if (f.answered) add(Flags.Flag.ANSWERED)
        if (f.flagged) add(Flags.Flag.FLAGGED)
        if (f.deleted) add(Flags.Flag.DELETED)
        if (f.draft) add(Flags.Flag.DRAFT)
        f.keywords.forEach { add(it) }
    }
}
