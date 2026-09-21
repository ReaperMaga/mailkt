package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.client.Conversations
import dev.reapermaga.mailkt.model.*

/**
 * Envelope-only conversation reading and incremental synchronization. No body or attachment data
 * is ever requested; callers fetch content for messages they care about through `Messages`.
 */
internal class ConversationsImpl(private val rt: MailboxRuntime, private val messages: MessagesImpl) : Conversations {

    override fun assemble(envelopes: List<MessageEnvelope>): List<Conversation> = ConversationAssembler.assemble(envelopes)

    override suspend fun of(location: MessageLocation, maxMessages: Int): Conversation {
        require(maxMessages > 0) { "maxMessages must be positive" }
        val origin = messages.envelope(location)
        val folders = listOfNotNull(location.folder, sentFolder()).distinct()
        val pool = folders.flatMap { window(it, WINDOW, upperUid = null).second }
        val related = relatedTo(listOf(origin), pool + origin)
        val truncated = related.size > maxMessages
        val kept = if (truncated) related.sortedByDescending { it.timestamp() }.take(maxMessages).toSet() + origin else related
        val all = ConversationAssembler.assemble(kept.toList())
        val own = all.firstOrNull { c -> c.messages.any { it.location == location || (origin.messageId != null && it.messageId == origin.messageId) } }
            ?: ConversationAssembler.assemble(listOf(origin)).single()
        return if (truncated) own.copy(truncated = true) else own
    }

    override suspend fun synchronize(
        folder: FolderPath,
        from: ConversationCheckpoint?,
        maxMessages: Int,
        query: MessageQuery,
    ): ConversationSync {
        require(maxMessages > 0) { "maxMessages must be positive" }
        if (from != null) Checkpoints.validate(from, rt.key, folder)
        val head = rt.retrying("sync.head") { rt.withFolder(folder) { it.uidValidity to (it.uidNext - 1) } }
        val (validity, upper) = head
        val reset = from != null && from.uidValidity != validity
        val incremental = from != null && !reset
        val lastSeen = if (incremental) from!!.lastUid else 0L

        if (incremental && upper <= lastSeen) return ConversationSync(emptyList(), checkpoint(folder, validity, lastSeen))

        val uids = rt.retrying("sync.uids") {
            rt.withFolder(folder) { f ->
                Checkpoints.uidValidity(validity, f.uidValidity)
                f.search(MessageRange.All, query).filter { it > lastSeen && it <= upper }.sorted()
            }
        }
        // Incremental syncs proceed oldest-first so the checkpoint can advance; a first sync or reset
        // takes the newest window and marks older history as unread by truncation.
        val taken = if (incremental) uids.take(maxMessages) else uids.takeLast(maxMessages)
        val more = uids.size > taken.size
        val fresh = taken.chunked(BATCH).flatMap { rt.fetchEnvelopes(folder, validity, it) }
        val next = when {
            incremental && taken.isNotEmpty() -> taken.last()
            incremental -> lastSeen
            else -> upper
        }
        val context = if (fresh.isEmpty()) emptyList()
        else window(folder, WINDOW, upperUid = fresh.minOf { it.location.uid } - 1, query = query).second
        val related = relatedTo(fresh, context + fresh)
        val freshLocations = fresh.map { it.location }.toSet()
        val changed = ConversationAssembler.assemble(related.toList())
            .filter { c -> c.messages.any { it.location in freshLocations } || fresh.any { f -> c.messages.any { it.messageId != null && it.messageId == f.messageId } } }
            .map { if (more) it.copy(truncated = true) else it }
        return ConversationSync(changed, checkpoint(folder, validity, next), reset)
    }

    private fun checkpoint(folder: FolderPath, validity: Long, lastUid: Long) =
        ConversationCheckpoint(rt.key, folder.value, validity, lastUid)

    /** Envelopes of the newest [size] UIDs at or below [upperUid]; also returns the validity used. */
    private suspend fun window(
        folder: FolderPath,
        size: Int,
        upperUid: Long?,
        query: MessageQuery = MessageQuery.ALL,
    ): Pair<Long, List<MessageEnvelope>> {
        val (validity, uids) = rt.retrying("window") {
            rt.withFolder(folder) { f ->
                f.uidValidity to f.search(MessageRange.All, query).filter { upperUid == null || it <= upperUid }.sorted().takeLast(size)
            }
        }
        return validity to uids.chunked(BATCH).flatMap { rt.fetchEnvelopes(folder, validity, it) }
    }

    private suspend fun sentFolder(): FolderPath? =
        try {
            FoldersImpl(rt).special(SpecialUse.SENT)?.path
        } catch (e: MailException) {
            if (e is MailException.MailboxClosed) throw e else null
        }

    private companion object {
        /** Older messages fetched purely to attach fresh ones to an existing thread. */
        const val WINDOW = 500
        const val BATCH = 100
    }
}
