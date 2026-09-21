package dev.reapermaga.mailkt.model

/** Immutable message under composition. Build with copy(); create via Outbox. */
data class Draft(
    val from: MailParticipant,
    val to: List<MailParticipant> = emptyList(),
    val cc: List<MailParticipant> = emptyList(),
    val bcc: List<MailParticipant> = emptyList(),
    val subject: String = "",
    val text: String? = null,
    val html: String? = null,
    val attachments: List<MailAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    /** Assigned on creation; used for SMTP outcome reconciliation. */
    val messageId: String? = null,
) {
    override fun toString(): String = "Draft(attachments=${attachments.size})"
}

/** Outcome of a submission. Never retried automatically. */
sealed interface SendResult {
    val messageId: String

    /** Server accepted the message. [sentCopy] is set if it was appended to the Sent folder. */
    data class Accepted(override val messageId: String, val sentCopy: MessageLocation? = null) : SendResult

    /** Definitely not sent. */
    data class Failed(override val messageId: String, val cause: MailException) : SendResult

    /** Outcome unknown (e.g. connection lost after DATA); reconcile via Message-ID in Sent. */
    data class Unknown(override val messageId: String, val reason: RecoveryReason) : SendResult

    val status: SendStatus
        get() = when (this) {
            is Accepted -> SendStatus.ACCEPTED
            is Failed -> SendStatus.FAILED
            is Unknown -> SendStatus.UNKNOWN
        }
}

enum class SendStatus { ACCEPTED, FAILED, UNKNOWN }
