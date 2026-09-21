package dev.reapermaga.mailkt.model

/**
 * Reference to one MIME part. Only usable with the mailbox that issued it; downloads verify
 * UIDVALIDITY/UID and fail with [MailException.IntegrityViolation] on mismatch.
 */
data class MessagePartRef(val location: MessageLocation, val section: String) {
    init {
        require(section.isNotEmpty()) { "MIME section must not be empty" }
    }
}

enum class PartDisposition { INLINE, ATTACHMENT, NONE }

/** Metadata of one MIME part; no bytes. */
data class MessagePartDescriptor(
    val ref: MessagePartRef,
    val mediaType: String,
    val disposition: PartDisposition = PartDisposition.NONE,
    val fileName: String? = null,
    val contentId: String? = null,
    val transferEncoding: String? = null,
    val advertisedSize: Long? = null,
    val children: List<MessagePartDescriptor> = emptyList(),
) {
    val isMultipart: Boolean get() = mediaType.startsWith("multipart/", ignoreCase = true)
    val isPdf: Boolean
        get() = mediaType.equals("application/pdf", true) || fileName?.endsWith(".pdf", true) == true
    val isAttachment: Boolean
        get() = !isMultipart && (disposition == PartDisposition.ATTACHMENT || (fileName != null && disposition != PartDisposition.INLINE))

    override fun toString(): String = "MessagePartDescriptor(section=${ref.section}, mediaType=$mediaType)"
}

/** MIME tree of one message without content. */
data class MessageStructure(val location: MessageLocation, val root: MessagePartDescriptor) {
    /** All parts, depth-first. */
    val parts: List<MessagePartDescriptor> get() = flatten(root)
    val attachments: List<MessagePartDescriptor> get() = parts.filter { it.isAttachment }
    val textParts: List<MessagePartDescriptor>
        get() = parts.filter { !it.isMultipart && !it.isAttachment && it.mediaType.startsWith("text/", true) }

    private fun flatten(p: MessagePartDescriptor): List<MessagePartDescriptor> =
        listOf(p) + p.children.flatMap(::flatten)
}
