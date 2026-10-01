package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.internal.mime.Descriptors
import dev.reapermaga.mailkt.model.*
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility

/**
 * Shared MIME tree traversal so structure inspection, selective download and full parsing agree on
 * section ids (IMAP style: multipart root "0", children "1", "1.2"; a single-part message is "1").
 */
internal object PartWalker {
    fun isMultipart(p: Part) = p.isMimeType("multipart/*")

    fun rootSection(p: Part) = if (isMultipart(p)) Descriptors.ROOT else "1"

    fun children(p: Part): List<Part> {
        val mp = p.content as Multipart
        return (0 until mp.count).map { mp.getBodyPart(it) }
    }

    fun fileName(p: Part): String? = p.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }

    /** Descriptor tree without touching part content (IMAP serves this from BODYSTRUCTURE). */
    fun describe(p: Part, location: MessageLocation, section: String): MessagePartDescriptor {
        val kids = if (isMultipart(p)) children(p).mapIndexed { i, c -> describe(c, location, Descriptors.childSection(section, i)) } else emptyList()
        return MessagePartDescriptor(
            ref = MessagePartRef(location, section),
            mediaType = Descriptors.mediaTypeOf(p.contentType),
            disposition = Descriptors.disposition(p.disposition),
            fileName = fileName(p),
            contentId = (p as? MimePart)?.contentID,
            transferEncoding = (p as? MimePart)?.encoding,
            advertisedSize = p.size.toLong().takeIf { it >= 0 },
            children = kids,
        )
    }

    /** Resolves an IMAP-style section to a part, or null if it does not exist. */
    fun resolve(root: Part, section: String): Part? {
        val nums = section.split('.').map { it.toIntOrNull() ?: return null }
        if (!isMultipart(root)) return if (nums == listOf(1)) root else null
        var cur: Part = root
        for (n in nums) {
            if (!isMultipart(cur)) return null
            val kids = children(cur)
            if (n < 1 || n > kids.size) return null
            cur = kids[n - 1]
        }
        return cur.takeIf { section != Descriptors.ROOT }
    }
}
