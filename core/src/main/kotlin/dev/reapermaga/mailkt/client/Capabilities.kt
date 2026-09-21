package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.flow.Flow

interface Folders {
    suspend fun list(): List<FolderInfo>

    /** Resolves a special-use folder, or null if the server has none. */
    suspend fun special(use: SpecialUse): FolderInfo?

    /** Appends [draft] to [folder] (e.g. Drafts or Sent) and returns its location if known. */
    suspend fun append(folder: FolderPath, draft: Draft, flags: MessageFlags = MessageFlags()): MessageLocation?
}

/** Envelope-first reading: envelope -> structure -> selective part download. */
interface Messages {
    /** One page of envelopes; pass `page.next` back to continue. Sparse pages may be empty. */
    suspend fun page(selection: MessageSelection, limit: Int = 50): EnvelopePage

    /** Streams envelopes of a frozen snapshot, filtered by the selection's query. */
    fun envelopes(selection: MessageSelection): Flow<MessageEnvelope>

    /** Streams full messages; envelope filtering happens before any content fetch. */
    fun messages(selection: MessageSelection, maxBytesPerMessage: Long = DEFAULT_MAX_BYTES): Flow<MailMessage>

    suspend fun envelope(location: MessageLocation): MessageEnvelope

    /** MIME structure without content. */
    suspend fun structure(location: MessageLocation): MessageStructure

    /** Downloads one part; [maxBytes] is mandatory. */
    suspend fun download(ref: MessagePartRef, maxBytes: Long): DownloadedPart

    /** Downloads the complete message within [maxBytes]. */
    suspend fun get(location: MessageLocation, maxBytes: Long = DEFAULT_MAX_BYTES): MailMessage

    /** Live envelopes (IDLE) with catch-up from [from]; at-least-once, duplicates suppressed in-session. */
    fun watchEnvelopes(
        folder: FolderPath,
        query: MessageQuery = MessageQuery.ALL,
        from: WatchCheckpoint? = null,
    ): Flow<WatchedEnvelope>

    /** Like [watchEnvelopes] but fetches full messages for envelopes that pass the query. */
    fun watch(
        folder: FolderPath,
        query: MessageQuery = MessageQuery.ALL,
        from: WatchCheckpoint? = null,
        maxBytesPerMessage: Long = DEFAULT_MAX_BYTES,
    ): Flow<WatchedMessage>

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 25L * 1024 * 1024
    }
}

/** Envelope plus the checkpoint to persist once the caller has durably processed it. */
data class WatchedEnvelope(val envelope: MessageEnvelope, val next: WatchCheckpoint)

data class WatchedMessage(val message: MailMessage, val next: WatchCheckpoint)

interface Conversations {
    /** Conversation containing [location], merged across copies. */
    suspend fun of(location: MessageLocation, maxMessages: Int = 200): Conversation

    /**
     * Incremental sync of [folder]; returns changed conversations and the next checkpoint.
     *
     * [query] narrows the membership server-side before any envelope is fetched. Callers that care
     * about one correspondent should pass it rather than filtering the result, because an unfiltered
     * sync has to fetch every envelope in the window to find out what it is.
     */
    suspend fun synchronize(
        folder: FolderPath,
        from: ConversationCheckpoint? = null,
        maxMessages: Int = 500,
        query: MessageQuery = MessageQuery.ALL,
    ): ConversationSync

    /** Groups already-fetched envelopes without contacting the server. */
    fun assemble(envelopes: List<MessageEnvelope>): List<Conversation>
}

interface Outbox {
    fun newDraft(): Draft

    /** Reply draft with recipients, subject and threading headers derived from [original]. */
    fun reply(original: MessageEnvelope, replyAll: Boolean = false, text: String? = null): Draft

    /** Submits without retry; the result is explicit ACCEPTED, FAILED or UNKNOWN. */
    suspend fun send(draft: Draft, saveToSent: Boolean = true): SendResult
}
