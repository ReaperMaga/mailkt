package dev.reapermaga.mailkt.internal.transport

import dev.reapermaga.mailkt.model.*

/** Narrow IMAP port over one authenticated connection. No Jakarta types cross this boundary. */
internal interface ImapSessionPort {
    suspend fun listFolders(): List<FolderInfo>

    /** Opens [path] for the duration of [block] and always closes it afterwards. */
    suspend fun <T> withFolder(path: FolderPath, readOnly: Boolean = true, block: suspend (ImapFolderPort) -> T): T

    /** Cheap liveness probe (NOOP). Throws on a dead connection. */
    suspend fun noop()

    suspend fun isConnected(): Boolean

    suspend fun close()
}

/** Operations on one selected folder. UIDs are always validated against [uidValidity] by callers. */
internal interface ImapFolderPort {
    val uidValidity: Long

    /** Highest assigned UID + 1. */
    val uidNext: Long

    suspend fun messageCount(): Int

    /** Ascending UIDs matching [range] and server-evaluable parts of [query]. */
    suspend fun search(range: MessageRange, query: MessageQuery): List<Long>

    /** Envelope-only fetch. Missing (expunged) UIDs are omitted. */
    suspend fun fetchEnvelopes(uids: List<Long>): List<MessageEnvelope>

    /** MIME structure without content; null if the message is gone. */
    suspend fun fetchStructure(uid: Long): MessageStructure?

    /** One part, decoded, bounded by [maxBytes]; throws [MailException.LimitExceeded]; null if gone. */
    suspend fun fetchPart(uid: Long, section: String, maxBytes: Long): DownloadedPart?

    /** Raw RFC 822 bytes bounded by [maxBytes]; null if gone. */
    suspend fun fetchRaw(uid: Long, maxBytes: Long): ByteArray?

    /** Appends raw message; returns new UID when the server reports it (UIDPLUS). */
    suspend fun append(raw: ByteArray, flags: MessageFlags): Long?

    /**
     * Suspends until the folder changes or [timeoutMillis] elapses (IDLE or polling fallback).
     * Returns true if a change was signalled.
     */
    suspend fun awaitChange(timeoutMillis: Long): Boolean
}
