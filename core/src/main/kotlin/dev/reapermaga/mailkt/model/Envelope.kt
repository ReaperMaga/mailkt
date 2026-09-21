package dev.reapermaga.mailkt.model

import java.time.Instant

/** Lightweight metadata only: never contains body or attachment bytes. */
data class MessageEnvelope(
    val location: MessageLocation,
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String> = emptyList(),
    val from: List<MailParticipant> = emptyList(),
    val to: List<MailParticipant> = emptyList(),
    val cc: List<MailParticipant> = emptyList(),
    val replyTo: List<MailParticipant> = emptyList(),
    val subject: String? = null,
    val sentAt: Instant? = null,
    val receivedAt: Instant? = null,
    val flags: MessageFlags = MessageFlags(),
    val contentType: String? = null,
    val advertisedSize: Long? = null,
    val headers: Map<String, List<String>> = emptyMap(),
    val threadId: String? = null,
) {
    override fun toString(): String = "MessageEnvelope(location=$location)"
}

/** Predicates evaluable from envelope data only. Body/attachment content filtering is post-download. */
data class MessageQuery(
    val folder: FolderPath? = null,
    val receivedFrom: Instant? = null,
    val receivedBefore: Instant? = null,
    val sentFrom: Instant? = null,
    val sentBefore: Instant? = null,
    val from: Set<MailAddress> = emptySet(),
    val to: Set<MailAddress> = emptySet(),
    val subjectContains: String? = null,
    val seen: Boolean? = null,
    val flagged: Boolean? = null,
    val requiredFlags: Set<String> = emptySet(),
    val headerEquals: Map<String, String> = emptyMap(),
    val threadIds: Set<String> = emptySet(),
    val minSize: Long? = null,
    val maxSize: Long? = null,
) {
    companion object {
        val ALL = MessageQuery()
    }
}

/** Membership of a historical scan; frozen when the scan starts. */
sealed interface MessageRange {
    data object All : MessageRange
    /** 1-based inclusive message positions, newest last. */
    data class Positions(val first: Int, val last: Int) : MessageRange
    data class Dates(val from: Instant?, val before: Instant?) : MessageRange
}

data class MessageSelection(
    val folder: FolderPath,
    val query: MessageQuery = MessageQuery.ALL,
    val range: MessageRange = MessageRange.All,
    val newestFirst: Boolean = false,
    val batchSize: Int = 50,
    val checkpoint: ScanCheckpoint? = null,
) {
    init {
        require(batchSize in 1..1000) { "batchSize must be 1..1000" }
    }
}
