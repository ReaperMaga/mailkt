package dev.reapermaga.mailkt.model

import java.time.Instant

/** A group of related messages, merged across folders/copies. */
data class Conversation(
    val id: String,
    val subject: String?,
    val messages: List<MessageEnvelope>,
    val participants: Set<MailAddress>,
    val lastActivity: Instant?,
    /** True if the conversation reached the configured size limit. */
    val truncated: Boolean = false,
) {
    override fun toString(): String = "Conversation(size=${messages.size}, truncated=$truncated)"
}

data class ConversationSync(
    val changed: List<Conversation>,
    val next: ConversationCheckpoint,
    /** True when the caller must discard cached state (UIDVALIDITY changed). */
    val reset: Boolean = false,
)
