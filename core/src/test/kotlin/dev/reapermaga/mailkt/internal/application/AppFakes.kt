package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.internal.connection.*
import dev.reapermaga.mailkt.internal.transport.*
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal data class FakePart(val section: String, val mediaType: String, val fileName: String?, val bytes: ByteArray)

internal class FakeMsg(
    val messageId: String?,
    val from: String = "contact@example.com",
    val to: String = "a@example.com",
    val subject: String = "Subject",
    val sentAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val parts: List<FakePart> = emptyList(),
    val size: Long = 100,
) {
    var uid: Long = 0
}

internal class FakeFolderData(var uidValidity: Long = 1) {
    val messages = CopyOnWriteArrayList<FakeMsg>()
    var nextUid = 1L
}

/** In-memory IMAP server shared by every connection generation of a test. */
internal class FakeServer(val id: MailboxId = testId()) {
    val folders = mutableMapOf<String, FakeFolderData>()
    val envelopeUids = AtomicInteger()
    val structureFetches = AtomicInteger()
    val partFetches = AtomicInteger()
    val rawFetches = AtomicInteger()
    val partSections = CopyOnWriteArrayList<String>()
    val changes = Channel<Unit>(Channel.CONFLATED)

    @Volatile var failNext: (() -> Throwable)? = null

    fun folder(name: String) = folders.getOrPut(name) { FakeFolderData() }

    fun add(folder: String, msg: FakeMsg): Long {
        val f = folder(folder)
        msg.uid = f.nextUid++
        f.messages += msg
        changes.trySend(Unit)
        return msg.uid
    }

    fun expunge(folder: String, uid: Long) { folder(folder).messages.removeIf { it.uid == uid } }

    fun envelope(folder: String, m: FakeMsg): MessageEnvelope {
        val f = folder(folder)
        return MessageEnvelope(
            location = MessageLocation(id, FolderPath(folder), f.uidValidity, m.uid),
            messageId = m.messageId, inReplyTo = m.inReplyTo, references = m.references,
            from = listOf(MailParticipant(MailAddress(m.from))), to = listOf(MailParticipant(MailAddress(m.to))),
            subject = m.subject, sentAt = m.sentAt, receivedAt = m.sentAt, advertisedSize = m.size,
        )
    }

    fun totalContentFetches() = structureFetches.get() + partFetches.get() + rawFetches.get()
}

internal class FakeFolder(private val server: FakeServer, private val name: String, private val data: FakeFolderData) : ImapFolderPort {
    override val uidValidity get() = data.uidValidity
    override val uidNext get() = data.nextUid
    private fun loc(uid: Long) = MessageLocation(server.id, FolderPath(name), uidValidity, uid)

    override suspend fun messageCount() = data.messages.size

    override suspend fun search(range: MessageRange, query: MessageQuery): List<Long> {
        val all = data.messages.map { it.uid }.sorted()
        return when (range) {
            MessageRange.All -> all
            is MessageRange.Positions -> all.drop(range.first - 1).take(range.last - range.first + 1)
            is MessageRange.Dates -> data.messages.filter {
                (range.from == null || it.sentAt >= range.from) && (range.before == null || it.sentAt < range.before)
            }.map { it.uid }.sorted()
        }
    }

    override suspend fun fetchEnvelopes(uids: List<Long>): List<MessageEnvelope> {
        server.envelopeUids.addAndGet(uids.size)
        return uids.mapNotNull { u -> data.messages.firstOrNull { it.uid == u } }.map { server.envelope(name, it) }
    }

    override suspend fun fetchStructure(uid: Long): MessageStructure? {
        server.structureFetches.incrementAndGet()
        val m = data.messages.firstOrNull { it.uid == uid } ?: return null
        val kids = m.parts.map { desc(uid, it) }
        return MessageStructure(loc(uid), MessagePartDescriptor(MessagePartRef(loc(uid), "0"), "multipart/mixed", children = kids))
    }

    private fun desc(uid: Long, p: FakePart) = MessagePartDescriptor(
        MessagePartRef(loc(uid), p.section), p.mediaType,
        if (p.fileName != null) PartDisposition.ATTACHMENT else PartDisposition.NONE, p.fileName, advertisedSize = p.bytes.size.toLong(),
    )

    override suspend fun fetchPart(uid: Long, section: String, maxBytes: Long): DownloadedPart? {
        val m = data.messages.firstOrNull { it.uid == uid } ?: return null
        val p = m.parts.firstOrNull { it.section == section } ?: throw MailException.IntegrityViolation(IntegrityKind.PART_CHANGED)
        if (p.bytes.size > maxBytes) throw MailException.LimitExceeded(maxBytes)
        server.partFetches.incrementAndGet()
        server.partSections += section
        return DownloadedPart(desc(uid, p), ByteContent(p.bytes))
    }

    override suspend fun fetchRaw(uid: Long, maxBytes: Long): ByteArray? {
        server.rawFetches.incrementAndGet()
        return data.messages.firstOrNull { it.uid == uid }?.let { "RAW".toByteArray() }
    }

    override suspend fun append(raw: ByteArray, flags: MessageFlags): Long =
        FakeMsg(messageId = "<appended@example.com>").let { server.add(name, it) }

    override suspend fun awaitChange(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { server.changes.receive(); true } ?: false
}

internal class FakeSession(private val server: FakeServer) : ImapSessionPort {
    override suspend fun listFolders() = server.folders.keys.map {
        FolderInfo(FolderPath(it), it, when (it) { "INBOX" -> SpecialUse.INBOX; "Sent" -> SpecialUse.SENT; else -> null })
    }

    override suspend fun <T> withFolder(path: FolderPath, readOnly: Boolean, block: suspend (ImapFolderPort) -> T): T {
        server.failNext?.let { server.failNext = null; throw it() }
        val data = server.folders[path.value] ?: throw MailException.FolderNotFound(path)
        return block(FakeFolder(server, path.value, data))
    }

    override suspend fun noop() = Unit
    override suspend fun isConnected() = true
    override suspend fun close() = Unit
}

internal class FakeSmtp(var outcome: () -> SubmissionOutcome = { SubmissionOutcome.Accepted }) : SmtpPort {
    val submissions = AtomicInteger()
    override suspend fun submit(raw: ByteArray, sender: MailAddress, recipients: List<MailAddress>): SubmissionOutcome {
        submissions.incrementAndGet()
        return outcome()
    }
    override suspend fun close() = Unit
}

internal object TestCodec : MimeCodec {
    override fun build(draft: Draft) = BuiltMessage(
        "RAW".toByteArray(), draft.messageId ?: "<gen@example.com>", (draft.to + draft.cc + draft.bcc).map { it.address },
    )

    override fun parse(raw: ByteArray, envelope: MessageEnvelope, maxBytes: Long) =
        MailMessage(envelope, MimeContent.Text("text/plain", "body"))
}

internal class TestConnection(override val imap: ImapSessionPort, override val smtp: SmtpPort?) : TransportConnection {
    override suspend fun close() = Unit
}

/** A mailbox over the fake server with instant delays. */
internal class Rig private constructor(val server: FakeServer, val smtp: FakeSmtp, val manager: ConnectionManager, val rt: MailboxRuntime) {
    val messages = MessagesImpl(rt)
    val folders = FoldersImpl(rt)
    val conversations = ConversationsImpl(rt, messages)
    val outbox = OutboxImpl(rt, folders)

    suspend fun close() = manager.close()

    companion object {
        suspend fun open(
            server: FakeServer = FakeServer(),
            smtp: FakeSmtp = FakeSmtp(),
            id: MailboxId = server.id,
        ): Rig {
            val factory = object : TransportFactory {
                override suspend fun connect(): TransportConnection = TestConnection(FakeSession(server), smtp)
            }
            val delayer = FakeDelayer(TEST_POLICY.keepAliveInterval.inWholeMilliseconds)
            val manager = ConnectionManager.open(id, factory, TEST_POLICY, delayer = delayer, random = MID_RANDOM)
            val rt = MailboxRuntime(id, MailAddress("a@example.com"), manager, TestCodec, delayer, RetryPolicy(retryDelayMillis = 1, idleTimeoutMillis = 50))
            return Rig(server, smtp, manager, rt)
        }
    }
}

internal fun folderSel(name: String = "INBOX", query: MessageQuery = MessageQuery.ALL, batch: Int = 2, newestFirst: Boolean = false, cp: ScanCheckpoint? = null) =
    MessageSelection(FolderPath(name), query, MessageRange.All, newestFirst, batch, cp)

internal fun FakeServer.seed(folder: String, n: Int, from: (Int) -> String = { "contact@example.com" }) {
    repeat(n) { add(folder, FakeMsg(messageId = "<m$it@example.com>", from = from(it), subject = "S$it")) }
}
