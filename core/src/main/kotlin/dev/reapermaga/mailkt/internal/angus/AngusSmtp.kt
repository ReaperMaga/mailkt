package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.client.AccessTokenSource
import dev.reapermaga.mailkt.client.ImapEndpoint
import dev.reapermaga.mailkt.internal.mime.RecoveryClassifier
import dev.reapermaga.mailkt.internal.transport.SmtpPort
import dev.reapermaga.mailkt.internal.transport.SubmissionOutcome
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.RecoveryReason
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import java.io.ByteArrayInputStream

/** One SMTP submission per call on a fresh authenticated connection; never retried. */
internal class AngusSmtp(
    private val email: MailAddress,
    private val endpoint: ImapEndpoint,
    private val tokens: AccessTokenSource,
) : SmtpPort {

    override suspend fun submit(raw: ByteArray, sender: MailAddress, recipients: List<MailAddress>): SubmissionOutcome {
        val token = try {
            tokens.accessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return SubmissionOutcome.Rejected(RecoveryClassifier.toMailException(e))
        }
        return runInterruptible(Dispatchers.IO) { deliver(raw, sender, recipients, token) }
    }

    private fun deliver(raw: ByteArray, sender: MailAddress, recipients: List<MailAddress>, token: String): SubmissionOutcome {
        val session = Session.getInstance(AngusProperties.smtp(endpoint))
        val message = MimeMessage(session, ByteArrayInputStream(raw))
        message.setFrom(InternetAddress(sender.value))
        val transport = session.getTransport("smtp")
        var submitting = false
        var accepted = false
        try {
            transport.connect(endpoint.smtpHost, endpoint.smtpPort, email.value, token)
            submitting = true
            transport.sendMessage(message, recipients.map { InternetAddress(it.value) }.toTypedArray())
            accepted = true
            return SubmissionOutcome.Accepted
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted && e is InterruptedException) throw e
            return when {
                accepted -> SubmissionOutcome.Accepted
                !submitting && e is AuthenticationFailedException -> SubmissionOutcome.Rejected(MailException.AuthenticationRequired(cause = e))
                !submitting -> SubmissionOutcome.Rejected(RecoveryClassifier.toMailException(e))
                e is SendFailedException && !e.validSentAddresses.isNullOrEmpty() -> uncertain(e)
                e is SMTPAddressFailedException -> SubmissionOutcome.Rejected(MailException.Unexpected(e))
                e is SMTPSendFailedException && e.returnCode in 400..599 -> SubmissionOutcome.Rejected(MailException.Unexpected(e))
                else -> uncertain(e)
            }
        } finally {
            // QUIT failure does not invalidate a successful DATA acknowledgement.
            runCatching { transport.close() }
        }
    }

    private fun uncertain(e: Exception): SubmissionOutcome.Uncertain {
        val reason = (RecoveryClassifier.toMailException(e) as? MailException.ConnectionFailed)?.recovery ?: RecoveryReason.NETWORK
        return SubmissionOutcome.Uncertain(reason)
    }

    override suspend fun close() = Unit
}
