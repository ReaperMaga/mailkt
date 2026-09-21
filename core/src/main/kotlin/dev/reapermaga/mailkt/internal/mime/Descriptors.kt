package dev.reapermaga.mailkt.internal.mime

import dev.reapermaga.mailkt.model.*

/** Framework-free helpers for building and filtering MailKT MIME model values. */
internal object Descriptors {
    fun disposition(raw: String?): PartDisposition = when (raw?.trim()?.lowercase()) {
        "attachment" -> PartDisposition.ATTACHMENT
        "inline" -> PartDisposition.INLINE
        else -> PartDisposition.NONE
    }

    /** Child section id: root is "TEXT-less" "1"-based like IMAP BODY[section]. */
    fun childSection(parent: String?, index: Int): String =
        if (parent == null || parent == ROOT) "${index + 1}" else "$parent.${index + 1}"

    const val ROOT: String = "0"

    fun mediaTypeOf(contentType: String?): String =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null } ?: "application/octet-stream"

    fun byteContentOrEmpty(bytes: ByteArray?): ByteContent = ByteContent(bytes ?: ByteArray(0))
}
