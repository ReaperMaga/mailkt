package dev.reapermaga.mailkt.model

/** Detached, immutable bytes. Defensive copies keep the value immutable. */
class ByteContent(bytes: ByteArray) {
    private val data: ByteArray = bytes.copyOf()
    val size: Int get() = data.size
    fun toByteArray(): ByteArray = data.copyOf()
    override fun equals(other: Any?): Boolean = other is ByteContent && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "ByteContent(size=$size)"
}

/** Downloaded part bytes, decoded from the transfer encoding. */
data class DownloadedPart(
    val descriptor: MessagePartDescriptor,
    val content: ByteContent,
)

/** Decoded MIME content tree. */
sealed interface MimeContent {
    val mediaType: String

    data class Text(override val mediaType: String, val text: String, val charset: String = "UTF-8") : MimeContent
    data class Multipart(override val mediaType: String, val parts: List<MimeContent>) : MimeContent
    data class Binary(
        override val mediaType: String,
        val ref: MessagePartRef?,
        val fileName: String?,
        val contentId: String?,
        val disposition: PartDisposition,
        val content: ByteContent,
    ) : MimeContent
    data class Embedded(override val mediaType: String, val message: MimeContent) : MimeContent
}

data class MailAttachment(
    val fileName: String,
    val mediaType: String,
    val content: ByteContent,
    val contentId: String? = null,
    val inline: Boolean = false,
    val ref: MessagePartRef? = null,
) {
    override fun toString(): String = "MailAttachment(mediaType=$mediaType, size=${content.size})"
}

/** Complete message: envelope plus decoded content and attachments. */
data class MailMessage(
    val envelope: MessageEnvelope,
    val content: MimeContent,
    val attachments: List<MailAttachment> = emptyList(),
) {
    val location: MessageLocation get() = envelope.location

    val plainText: String? get() = findText("text/plain", content)
    val html: String? get() = findText("text/html", content)

    private fun findText(type: String, c: MimeContent): String? = when (c) {
        is MimeContent.Text -> if (c.mediaType.startsWith(type, true)) c.text else null
        is MimeContent.Multipart -> c.parts.firstNotNullOfOrNull { findText(type, it) }
        is MimeContent.Embedded, is MimeContent.Binary -> null
    }

    override fun toString(): String = "MailMessage(location=$location)"
}
