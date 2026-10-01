package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.client.Outbox
import dev.reapermaga.mailkt.internal.mime.RecoveryClassifier
import dev.reapermaga.mailkt.internal.transport.BuiltMessage
import dev.reapermaga.mailkt.internal.transport.SubmissionOutcome
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.CancellationException

/**
 * SMTP submission with explicit outcomes. A submission is attempted exactly once: reconnecting never
 * re-sends, and a failed Sent-folder append can never trigger another send. UNKNOWN means the
 * connection failed after DATA started; reconcile by Message-ID in the Sent folder before resending.
 * Cancellation may also occur after DATA: persist the Message-ID before calling `send`.
 */
internal class OutboxImpl(private val rt: MailboxRuntime, private val folders: FoldersImpl) : Outbox {

    override fun newDraft(): Draft = ReplyBuilder.newDraft(rt.email)

    override fun reply(original: MessageEnvelope, replyAll: Boolean, text: String?): Draft =
        ReplyBuilder.reply(rt.email, original, replyAll, text)

    override suspend fun send(draft: Draft, saveToSent: Boolean): SendResult {
        require(draft.from.address.normalized == rt.email.normalized) { "Sender must match the authenticated mailbox" }
        require(draft.to.isNotEmpty() || draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()) { "At least one recipient is required" }
        val built = rt.codec.build(draft)
        val lease = try {
            rt.manager.withConnection { it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MailException) {
            return SendResult.Failed(built.messageId, e)
        }
        val smtp = lease.connection.smtp ?: return SendResult.Failed(built.messageId, MailException.OutboxUnavailable())
        val outcome = try {
            smtp.submit(built.raw, rt.email, built.recipients)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = (RecoveryClassifier.toMailException(e) as? MailException.ConnectionFailed)?.recovery ?: RecoveryReason.NETWORK
            SubmissionOutcome.Uncertain(reason)
        }
        return when (outcome) {
            SubmissionOutcome.Accepted -> SendResult.Accepted(built.messageId, if (saveToSent) appendSent(built) else null)
            is SubmissionOutcome.Rejected -> SendResult.Failed(built.messageId, outcome.cause)
            is SubmissionOutcome.Uncertain -> {
                rt.manager.reportFailure(lease.generation, outcome.reason)
                SendResult.Unknown(built.messageId, outcome.reason)
            }
        }
    }

    /** Best effort: provider-saved copies are skipped, and no failure here changes the send result. */
    private suspend fun appendSent(built: BuiltMessage): MessageLocation? {
        if (rt.id.provider in AUTO_SAVING_PROVIDERS) return null
        return try {
            val sent = folders.special(SpecialUse.SENT) ?: return null
            rt.withFolder(sent.path, readOnly = false) { f ->
                f.append(built.raw, MessageFlags(seen = true))?.let { MessageLocation(rt.id, sent.path, f.uidValidity, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            rt.log.warn("outbox.sent-append", e, "outcome" to "failed")
            null
        }
    }

    private companion object {
        /** Their SMTP services already store a Sent copy; appending would duplicate it. */
        val AUTO_SAVING_PROVIDERS = setOf("gmail", "outlook")
    }
}
