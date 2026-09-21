package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.model.*

// region compose
suspend fun composeAndSend(mailbox: Mailbox, pdf: ByteArray): SendResult {
    val outbox = mailbox.outbox ?: error("This mailbox has no SMTP configured")
    val draft = outbox.newDraft().copy(
        to = listOf(MailParticipant(MailAddress("customer@example.com"), "Customer")),
        subject = "Your invoice",
        text = "Please find the invoice attached.",
        attachments = listOf(MailAttachment("invoice.pdf", "application/pdf", ByteContent(pdf))),
    )
    // draft.messageId is assigned already: persist it BEFORE sending to reconcile an UNKNOWN outcome.
    return outbox.send(draft)
}
// endregion

// region reply
suspend fun replyToMessage(mailbox: Mailbox, original: MessageEnvelope): SendResult {
    val outbox = checkNotNull(mailbox.outbox)
    val reply = outbox.reply(original, replyAll = false, text = "Thank you, received.")
    return outbox.send(reply, saveToSent = true)
}
// endregion

// region send-results
suspend fun sendAndInterpret(mailbox: Mailbox, draft: Draft) {
    when (val result = checkNotNull(mailbox.outbox).send(draft)) {
        is SendResult.Accepted -> println("Accepted; Sent copy stored: ${result.sentCopy != null}")
        is SendResult.Failed -> println("Definitely not sent: ${result.cause.javaClass.simpleName}")
        is SendResult.Unknown -> {
            // Never resend blindly. Look for result.messageId in the Sent folder first.
            val sent = mailbox.folders.special(SpecialUse.SENT)
            println("Unknown outcome (${result.reason}); reconcile ${result.messageId} in ${sent?.name}")
        }
    }
}
// endregion

// region append-draft
suspend fun saveAsDraft(mailbox: Mailbox, draft: Draft): MessageLocation? {
    val drafts = mailbox.folders.special(SpecialUse.DRAFTS) ?: return null
    return mailbox.folders.append(drafts.path, draft, MessageFlags(draft = true))
}
// endregion
