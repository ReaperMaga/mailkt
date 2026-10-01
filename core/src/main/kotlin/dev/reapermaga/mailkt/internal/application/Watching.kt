package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.client.Messages
import dev.reapermaga.mailkt.client.WatchedEnvelope
import dev.reapermaga.mailkt.client.WatchedMessage
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Live watching with catch-up. Delivery is at-least-once: duplicates are suppressed within one
 * collection, and a UIDVALIDITY change rescans (preferring duplicates over loss). Envelopes are
 * fetched and filtered before any content is requested. The connection is never held while the
 * consumer processes a value, and reconnects restart the folder session transparently.
 */
internal object Watching {
    private const val BATCH = 100

    fun envelopes(rt: MailboxRuntime, folder: FolderPath, query: MessageQuery, from: WatchCheckpoint?): Flow<WatchedEnvelope> = flow {
        if (from != null) Checkpoints.validate(from, rt.key, folder)
        var validity: Long? = from?.uidValidity
        var floor: Long? = from?.lastUid
        val delivered = HashSet<Long>()
        while (true) {
            val (uids, envelopes) = try {
                rt.withFolder(folder) { f ->
                    if (validity == null) validity = f.uidValidity
                    else if (validity != f.uidValidity) {
                        validity = f.uidValidity
                        floor = 0L
                        delivered.clear()
                    }
                    if (floor == null) floor = f.uidNext - 1
                    suspend fun pending(): List<Long> =
                        if (f.uidNext - 1 <= floor!!) emptyList()
                        else f.search(MessageRange.All, MessageQuery.ALL).filter { it > floor!! && it !in delivered }.sorted()
                    var uids = pending()
                    if (uids.isEmpty()) {
                        f.awaitChange(rt.retry.idleTimeoutMillis)
                        uids = pending()
                    }
                    uids to uids.chunked(BATCH).flatMap { f.fetchEnvelopes(it) }.sortedBy { it.location.uid }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MailException) {
                if (!rt.isConnectionLoss(e)) throw e
                rt.awaitConnected()
                continue
            }
            val current = validity!!
            for (envelope in envelopes) {
                val uid = envelope.location.uid
                if (!delivered.add(uid)) continue
                if (envelope.matches(query)) {
                    emit(WatchedEnvelope(envelope, WatchCheckpoint(rt.key, folder.value, current, uid)))
                }
            }
            uids.maxOrNull()?.let { floor = maxOf(floor ?: 0L, it) }
        }
    }

    fun messages(
        rt: MailboxRuntime,
        messages: Messages,
        folder: FolderPath,
        query: MessageQuery,
        from: WatchCheckpoint?,
        maxBytes: Long,
    ): Flow<WatchedMessage> = flow {
        envelopes(rt, folder, query, from).collect { watched ->
            val message = try {
                messages.get(watched.envelope.location, maxBytes)
            } catch (e: MailException.MessageUnavailable) {
                rt.log.debug("watch.expunged", "uid" to watched.envelope.location.uid)
                return@collect
            }
            emit(WatchedMessage(message, watched.next))
        }
    }
}
