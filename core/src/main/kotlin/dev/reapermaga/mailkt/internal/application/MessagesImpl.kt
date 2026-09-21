package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.client.Messages
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Envelope-first reading. Envelope filtering always precedes any structure or content request. */
internal class MessagesImpl(private val rt: MailboxRuntime) : Messages {

    override suspend fun page(selection: MessageSelection, limit: Int): EnvelopePage {
        require(limit in 1..1000) { "limit must be 1..1000" }
        val scan = rt.freeze(selection)
        val window = scan.uids.take(limit)
        val envelopes = rt.fetchEnvelopes(selection.folder, scan.validity, window).filter { it.matches(selection.query) }
        val more = scan.uids.size > window.size
        val done = (selection.checkpoint?.processedCount ?: 0) + window.size
        // A sparse page can be empty and still carry a continuation.
        val next = if (more) rt.scanCheckpoint(selection, scan, window.last(), done) else null
        return EnvelopePage(envelopes, next, window.size)
    }

    override fun envelopes(selection: MessageSelection): Flow<MessageEnvelope> = flow {
        val scan = rt.freeze(selection)
        for (batch in scan.uids.chunked(selection.batchSize)) {
            rt.fetchEnvelopes(selection.folder, scan.validity, batch)
                .filter { it.matches(selection.query) }
                .forEach { emit(it) }
        }
    }

    override fun messages(selection: MessageSelection, maxBytesPerMessage: Long): Flow<MailMessage> = flow {
        envelopes(selection).collect { emit(load(it, maxBytesPerMessage)) }
    }

    override suspend fun envelope(location: MessageLocation): MessageEnvelope {
        rt.requireOwn(location)
        return rt.retrying("envelope") {
            rt.withFolder(location.folder) { f ->
                Checkpoints.uidValidity(location.uidValidity, f.uidValidity)
                f.fetchEnvelopes(listOf(location.uid)).firstOrNull()
            }
        } ?: throw MailException.MessageUnavailable(location)
    }

    override suspend fun structure(location: MessageLocation): MessageStructure {
        rt.requireOwn(location)
        return rt.retrying("structure") {
            rt.withFolder(location.folder) { f ->
                Checkpoints.uidValidity(location.uidValidity, f.uidValidity)
                f.fetchStructure(location.uid)
            }
        } ?: throw MailException.MessageUnavailable(location)
    }

    override suspend fun download(ref: MessagePartRef, maxBytes: Long): DownloadedPart {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val location = ref.location
        rt.requireOwn(location)
        val part = rt.retrying("part") {
            rt.withFolder(location.folder) { f ->
                Checkpoints.uidValidity(location.uidValidity, f.uidValidity)
                f.fetchPart(location.uid, ref.section, maxBytes)
            }
        } ?: throw MailException.MessageUnavailable(location)
        if (part.descriptor.ref.section != ref.section) throw MailException.IntegrityViolation(IntegrityKind.PART_CHANGED)
        return part
    }

    override suspend fun get(location: MessageLocation, maxBytes: Long): MailMessage = load(envelope(location), maxBytes)

    private suspend fun load(envelope: MessageEnvelope, maxBytes: Long): MailMessage {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val location = envelope.location
        if ((envelope.advertisedSize ?: 0) > maxBytes) throw MailException.LimitExceeded(maxBytes)
        val raw = rt.retrying("raw") {
            rt.withFolder(location.folder) { f ->
                Checkpoints.uidValidity(location.uidValidity, f.uidValidity)
                f.fetchRaw(location.uid, maxBytes)
            }
        } ?: throw MailException.MessageUnavailable(location)
        return rt.codec.parse(raw, envelope, maxBytes)
    }

    override fun watchEnvelopes(folder: FolderPath, query: MessageQuery, from: WatchCheckpoint?) =
        Watching.envelopes(rt, folder, query, from)

    override fun watch(folder: FolderPath, query: MessageQuery, from: WatchCheckpoint?, maxBytesPerMessage: Long) =
        Watching.messages(rt, this, folder, query, from, maxBytesPerMessage)
}
