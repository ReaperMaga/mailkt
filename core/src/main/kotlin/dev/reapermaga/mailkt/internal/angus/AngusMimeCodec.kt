package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.internal.transport.BuiltMessage
import dev.reapermaga.mailkt.internal.transport.MimeCodec
import dev.reapermaga.mailkt.model.Draft
import dev.reapermaga.mailkt.model.MailMessage
import dev.reapermaga.mailkt.model.MessageEnvelope

internal object AngusMimeCodec : MimeCodec {
    override fun build(draft: Draft): BuiltMessage = AngusMimeBuilder.build(draft)

    override fun parse(raw: ByteArray, envelope: MessageEnvelope, maxBytes: Long): MailMessage =
        AngusMimeParser(envelope, maxBytes).parse(raw)
}
