package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.internal.mime.BoundedOutputStream
import dev.reapermaga.mailkt.internal.mime.failureChain
import dev.reapermaga.mailkt.internal.mime.readBounded
import dev.reapermaga.mailkt.internal.transport.ImapFolderPort
import dev.reapermaga.mailkt.model.*
import jakarta.mail.*
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.search.AndTerm
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.*
import org.eclipse.angus.mail.imap.IMAPFolder
import java.io.ByteArrayInputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/** One selected IMAP folder. Blocking Jakarta calls run on the IO dispatcher and are interruptible. */
internal class AngusImapFolder(
    private val folder: IMAPFolder,
    private val session: Session,
    private val id: MailboxId,
    private val path: FolderPath,
) : ImapFolderPort {
    override val uidValidity: Long = folder.uidValidity
    override val uidNext: Long = folder.uidNext

    private suspend fun <T> io(body: () -> T): T = runInterruptible(Dispatchers.IO) {
        try {
            body()
        } catch (e: IllegalStateException) {
            // Angus can surface a folder-close race as IllegalStateException; classify it as closure.
            if (!folder.isOpen) throw FolderClosedException(folder, "Folder closed") else throw e
        }
    }

    private fun location(uid: Long) = MessageLocation(id, path, uidValidity, uid)

    override suspend fun messageCount(): Int = io { folder.messageCount }

    override suspend fun search(range: MessageRange, query: MessageQuery): List<Long> = io {
        val term = AngusSearch.term(query)
        val candidates: Array<Message> = when (range) {
            MessageRange.All -> if (term == null) folder.messages else folder.search(term)
            is MessageRange.Dates -> {
                val parts = listOfNotNull(range.from?.let { AngusSearch.receivedSince(Date.from(it)) }, term)
                when (parts.size) {
                    0 -> folder.messages
                    1 -> folder.search(parts.single())
                    else -> folder.search(AndTerm(parts.toTypedArray()))
                }
            }
            is MessageRange.Positions -> {
                val count = folder.messageCount
                val last = minOf(range.last, count)
                if (range.first > last) emptyArray<Message>() else folder.getMessages(range.first, last)
            }
        }
        if (candidates.isEmpty()) return@io emptyList()
        folder.fetch(candidates, FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID)
        })
        candidates.asSequence()
            .filter { range !is MessageRange.Positions || term == null || term.match(it) }
            .filter { m -> (range as? MessageRange.Dates)?.let { r -> inDates(m, r) } ?: true }
            .map { folder.getUID(it) }.sorted().toList()
    }

    private fun inDates(m: Message, r: MessageRange.Dates): Boolean {
        val at = m.receivedDate?.toInstant() ?: return false
        return (r.from == null || at >= r.from) && (r.before == null || at < r.before)
    }

    override suspend fun fetchEnvelopes(uids: List<Long>): List<MessageEnvelope> = io {
        val rows = folder.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
        if (rows.isEmpty()) return@io emptyList()
        folder.fetch(rows, FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(FetchProfile.Item.SIZE)
            add(UIDFolder.FetchProfileItem.UID)
            EnvelopeMapper.HEADERS.forEach { add(it) }
        })
        rows.filterNot { it.isExpunged }.map { EnvelopeMapper.map(it, location(folder.getUID(it))) }
    }

    override suspend fun fetchStructure(uid: Long): MessageStructure? = io {
        val msg = folder.getMessageByUID(uid) ?: return@io null
        if (msg.isExpunged) return@io null
        val loc = location(uid)
        MessageStructure(loc, PartWalker.describe(msg, loc, PartWalker.rootSection(msg)))
    }

    override suspend fun fetchPart(uid: Long, section: String, maxBytes: Long): DownloadedPart? = io {
        val msg = folder.getMessageByUID(uid) ?: return@io null
        if (msg.isExpunged) return@io null
        val part = PartWalker.resolve(msg, section) ?: throw MailException.IntegrityViolation(IntegrityKind.PART_CHANGED)
        val descriptor = PartWalker.describe(part, location(uid), section).copy(children = emptyList())
        // getInputStream decodes the transfer encoding; partial fetch keeps the read bounded.
        val bytes = try {
            part.inputStream.use { it.readBounded(maxBytes) }
        } catch (e: MessageRemovedException) {
            return@io null
        }
        DownloadedPart(descriptor, ByteContent(bytes))
    }

    override suspend fun fetchRaw(uid: Long, maxBytes: Long): ByteArray? = io {
        val msg = folder.getMessageByUID(uid) ?: return@io null
        val out = BoundedOutputStream(maxBytes)
        try {
            msg.writeTo(out)
        } catch (e: MessageRemovedException) {
            return@io null
        } catch (e: Exception) {
            e.failureChain().filterIsInstance<MailException.LimitExceeded>().firstOrNull()?.let { throw it }
            throw e
        }
        out.toByteArray()
    }

    override suspend fun append(raw: ByteArray, flags: MessageFlags): Long? = io {
        val msg = MimeMessage(session, ByteArrayInputStream(raw))
        msg.setFlags(EnvelopeMapper.toJakarta(flags), true)
        folder.appendUIDMessages(arrayOf(msg)).firstOrNull()?.uid
    }

    override suspend fun awaitChange(timeoutMillis: Long): Boolean {
        val changed = AtomicBoolean(false)
        val listener = object : MessageCountAdapter() {
            override fun messagesAdded(e: MessageCountEvent) = changed.set(true)
            override fun messagesRemoved(e: MessageCountEvent) = changed.set(true)
        }
        folder.addMessageCountListener(listener)
        try {
            if (idleSupported()) idle(timeoutMillis) else poll(timeoutMillis, changed)
        } finally {
            runCatching { folder.removeMessageCountListener(listener) }
        }
        return changed.get()
    }

    private fun idleSupported(): Boolean = runCatching { (folder.store as org.eclipse.angus.mail.imap.IMAPStore).hasCapability("IDLE") }.getOrDefault(false)

    /** IDLE until an event; any other command (NOOP) aborts it, which also serves timeout and cancellation. */
    private suspend fun idle(timeoutMillis: Long) = coroutineScope {
        val worker = launch(Dispatchers.IO) { folder.idle(true) }
        try {
            withTimeoutOrNull(timeoutMillis) { worker.join() }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { noop() } }
        }
    }

    private suspend fun poll(timeoutMillis: Long, changed: AtomicBoolean) {
        withTimeoutOrNull(timeoutMillis) {
            while (!changed.get()) {
                delay(POLL_MILLIS)
                io { noop() }
            }
        }
    }

    private fun noop() {
        folder.doCommand(IMAPFolder.ProtocolCommand { p -> p.noop(); null })
    }

    private companion object {
        const val POLL_MILLIS = 5_000L
    }
}
