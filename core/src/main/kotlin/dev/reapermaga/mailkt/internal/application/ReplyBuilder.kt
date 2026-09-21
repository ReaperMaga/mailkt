package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.model.*
import java.util.UUID

/** Pure draft construction: no I/O, no Jakarta. */
internal object ReplyBuilder {
    fun newDraft(self: MailAddress): Draft = Draft(from = MailParticipant(self), messageId = newMessageId(self))

    /** Reply-To is respected; reply-all excludes the mailbox itself and never exposes Bcc. */
    fun reply(self: MailAddress, original: MessageEnvelope, replyAll: Boolean, text: String?): Draft {
        fun clean(list: List<MailParticipant>) =
            list.filterNot { it.address.normalized == self.normalized }.distinctBy { it.address.normalized }
        val primary = clean(original.replyTo.ifEmpty { original.from }).ifEmpty { clean(original.to) }
        val to = (primary + if (replyAll) clean(original.to) else emptyList()).distinctBy { it.address.normalized }
        val cc = if (replyAll) clean(original.cc).filterNot { c -> to.any { it.address.normalized == c.address.normalized } } else emptyList()
        val subject = original.subject.orEmpty().let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" }
        val parent = original.messageId
        return Draft(
            from = MailParticipant(self), to = to, cc = cc, subject = subject, text = text,
            inReplyTo = parent,
            references = if (parent != null) original.references + parent else original.references,
            messageId = newMessageId(self),
        )
    }

    fun newMessageId(self: MailAddress): String = "<${UUID.randomUUID()}@${self.value.substringAfter('@')}>"
}
