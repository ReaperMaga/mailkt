package dev.reapermaga.mailkt.internal.transport

import dev.reapermaga.mailkt.model.*

/** Result of one SMTP submission attempt as seen by the transport. */
internal sealed interface SubmissionOutcome {
    data object Accepted : SubmissionOutcome

    /** Server definitively rejected (or failure occurred before DATA). */
    data class Rejected(val cause: MailException) : SubmissionOutcome

    /** Connection failed after DATA started; acceptance unknown. */
    data class Uncertain(val reason: RecoveryReason) : SubmissionOutcome
}

internal interface SmtpPort {
    /** Submits once; never retries. */
    suspend fun submit(raw: ByteArray, sender: MailAddress, recipients: List<MailAddress>): SubmissionOutcome

    suspend fun close()
}

/** Converts between MailKT models and RFC 822 bytes. Implemented by internal.angus. */
internal interface MimeCodec {
    /** Builds bytes for a draft; assigns Message-ID if missing (returned via [BuiltMessage]). */
    fun build(draft: Draft): BuiltMessage

    /** Parses raw bytes into a detached message; parts beyond [maxBytes] fail with LimitExceeded. */
    fun parse(raw: ByteArray, envelope: MessageEnvelope, maxBytes: Long): MailMessage
}

internal class BuiltMessage(val raw: ByteArray, val messageId: String, val recipients: List<MailAddress>)

/** One authenticated connection generation (IMAP plus optional SMTP). */
internal interface TransportConnection {
    val imap: ImapSessionPort
    val smtp: SmtpPort?
    suspend fun close()
}

/** Creates fully authenticated and validated connections. Shared safely between mailboxes. */
internal interface TransportFactory {
    /** Throws [MailException.AuthenticationRequired] / [MailException.ConnectionFailed]. */
    suspend fun connect(): TransportConnection
}
