package dev.reapermaga.mailkt.model

/** Slash-agnostic folder path as reported by the server. */
@JvmInline
value class FolderPath(val value: String) {
    init {
        require(value.isNotEmpty()) { "Folder path must not be empty" }
    }

    override fun toString(): String = "FolderPath(***)"
}

enum class SpecialUse { INBOX, SENT, DRAFTS, TRASH, JUNK, ARCHIVE, ALL, FLAGGED }

data class FolderInfo(
    val path: FolderPath,
    val name: String,
    val specialUse: SpecialUse? = null,
    val selectable: Boolean = true,
    val messageCount: Int? = null,
    val unseenCount: Int? = null,
)

/** Identity of one message inside a mailbox. Only valid for the mailbox that produced it. */
data class MessageLocation(
    val mailboxId: MailboxId,
    val folder: FolderPath,
    val uidValidity: Long,
    val uid: Long,
) {
    override fun toString(): String = "MessageLocation(uid=$uid, uidValidity=$uidValidity)"
}

/** Standard IMAP flags plus keywords. */
data class MessageFlags(
    val seen: Boolean = false,
    val answered: Boolean = false,
    val flagged: Boolean = false,
    val deleted: Boolean = false,
    val draft: Boolean = false,
    val keywords: Set<String> = emptySet(),
)
