package dev.reapermaga.mailkt.message

import dev.reapermaga.mailkt.folder.HistoricalMessage
import dev.reapermaga.mailkt.folder.MessagePagesTest
import dev.reapermaga.mailkt.session.*
import jakarta.mail.FolderClosedException
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.*
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.OutputStream
import java.util.Date
import java.util.Properties
import javax.net.ssl.SSLException
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConversationsTest {
    private fun mail(id: String?, parent: String? = null, sender: String = "contact@example.com", body: String = "Hello", time: Long = 1): MimeMessage =
        composeMessage(sender, listOf("me@example.com"), "Same subject", body).apply {
            if (id == null) removeHeader("Message-ID") else setHeader("Message-ID", id)
            if (parent != null) setHeader("In-Reply-To", parent)
            sentDate = Date(time)
        }

    private fun located(message: MimeMessage, uid: Long, folder: String = "INBOX", account: String = "account") =
        LocatedMessage(MessageLocation(account, folder, 10, uid), HistoricalMessage(message, uid, 10, null))

    @Test fun `group replies chronological merge copies and keep subject matches separate`() {
        val parent = mail("<root@example.com>", time = 1)
        val reply = mail("<reply@example.com>", "<root@example.com>", time = 2)
        val threads = assembleConversations(listOf(located(reply, 2), located(parent, 1),
            located(MimeMessage(parent), 8, "Sent"), located(mail("<separate@example.com>", time = 3), 3)))
        assertEquals(2, threads.size)
        assertEquals(listOf("<root@example.com>", "<reply@example.com>"), threads.first().messages.map { it.message.messageID })
        assertEquals(2, threads.first().messages.first().copies.size)
    }

    @Test fun `missing IDs collisions and different accounts are never lost`() {
        val threads = assembleConversations(listOf(located(mail(null), 1), located(mail(null), 2),
            located(mail("<collision@example.com>", body = "A"), 3),
            located(mail("<collision@example.com>", body = "B"), 4),
            located(mail("<collision@example.com>", body = "A"), 5, account = "other")))
        assertEquals(5, threads.sumOf { it.messages.size })
        assertEquals(4, threads.size)
    }

    @Test fun `discovers ancestors and changed-participant replies regardless of scan order`() = runBlocking {
        val fixture = MessagePagesTest.Fixture()
        fixture.rows[1] = mail("<grandchild@example.com>", "<child@example.com>", "other@example.com")
        fixture.rows[2] = mail("<ancestor@example.com>", sender = "other@example.com")
        fixture.rows[3] = mail("<child@example.com>", "<root@example.com>", "other@example.com")
        fixture.rows[4] = mail("<root@example.com>", "<ancestor@example.com>")
        fixture.rows[5] = mail("<unrelated@example.com>", sender = "else@example.com")
        val history = readConversations(fixture, listOf("INBOX", "Sent"), setOf("contact@example.com"))
        assertEquals(1, history.threads.size)
        assertEquals(4, history.threads.single().messages.size)
        assertTrue(history.threads.single().messages.all { it.copies.size == 2 })
        assertEquals(0, fixture.opened)
        assertTrue(fixture.windows.all { it.last - it.first < 100 })
        assertTrue(history.threads.single().messages.all { it.message.folder == null })
    }

    @Test fun `truncation seed validation and limits are explicit`() = runBlocking<Unit> {
        val fixture = MessagePagesTest.Fixture()
        fixture.rows[1] = mail("<old@example.com>")
        fixture.rows[6] = mail("<new@example.com>")
        val result = readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(maxUidPositionsPerFolder = 2))
        assertTrue(result.folders.single().olderHistoryAvailable)
        assertEquals("<new@example.com>", result.threads.single().messages.single().message.messageID)
        assertFailsWith<IllegalArgumentException> { readConversations(fixture, listOf("INBOX"), emptySet()) }
        assertFailsWith<IllegalStateException> { readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(maxMatchedMessages = 1)) }
        assertFailsWith<IllegalStateException> { readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(maxTotalBytes = 1)) }
        assertEquals(0, fixture.opened)
    }

    @Test fun `older windows discover referenced ancestors and merge with latest flags`() = runBlocking {
        val fixture = MessagePagesTest.Fixture()
        fixture.rows[1] = mail("<old@example.com>", sender = "other@example.com", time = 1)
        fixture.rows[6] = mail("<new@example.com>", "<old@example.com>", time = 2)
        val recent = readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(maxUidPositionsPerFolder = 2))
        val older = readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            threadMessageIds = recent.threads.single().relatedMessageIds,
            options = ConversationReadOptions(beforeUidByFolder = mapOf("INBOX" to recent.folders.single().lowerUid)))
        assertFalse(older.folders.single().olderHistoryAvailable)
        assertEquals("<old@example.com>", older.threads.single().messages.single().message.messageID)
        val copies = (recent.threads + older.threads).flatMap { it.messages }.flatMap { it.copies }
        val fresh = located(MimeMessage(fixture.rows.getValue(6)).apply { setFlag(jakarta.mail.Flags.Flag.SEEN, true) }, 6)
        val merged = assembleConversations(copies + fresh)
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().messages.size)
        assertFalse(merged.single().messages.last().unread)
    }

    @Test fun `full messages are fetched in bounded batches with identical grouping`() = runBlocking {
        val batched = MessagePagesTest.Fixture()
        repeat(60) { index -> batched.rows[(index + 1).toLong()] = mail("<message-$index@example.com>") }
        batched.nextUid = 61
        val optimized = synchronizeConversations(batched, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(fullMessageBatchSize = 20))

        val legacyShape = MessagePagesTest.Fixture()
        legacyShape.rows.putAll(batched.rows.mapValues { MimeMessage(it.value) })
        legacyShape.nextUid = 61
        val individual = synchronizeConversations(legacyShape, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(fullMessageBatchSize = 1))

        assertEquals(3, optimized.metrics.fullMessageFetchCommands)
        assertEquals(60, individual.metrics.fullMessageFetchCommands)
        assertEquals(individual.history.threads.map { it.id }, optimized.history.threads.map { it.id })
        assertEquals(individual.history.threads.flatMap { it.messages }.map { it.message.messageID },
            optimized.history.threads.flatMap { it.messages }.map { it.message.messageID })
        assertEquals(0, batched.opened)
    }

    @Test fun `incremental cursor fetches only new UIDs and UIDVALIDITY reset rescans safely`() = runBlocking {
        val fixture = MessagePagesTest.Fixture().apply {
            rows[1] = mail("<one@example.com>")
            rows[2] = mail("<two@example.com>")
            nextUid = 3
        }
        val initial = synchronizeConversations(fixture, listOf("INBOX"), setOf("contact@example.com"))
        fixture.windows.clear()
        fixture.rows[3] = mail("<three@example.com>")
        fixture.nextUid = 4
        val incremental = synchronizeConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            cursor = initial.cursor)
        assertEquals(listOf(3L..3L, 3L..3L), fixture.windows)
        assertEquals(listOf("<three@example.com>"), incremental.history.threads.flatMap { it.messages }
            .map { it.message.messageID })
        assertEquals(3, incremental.cursor.folders.getValue("INBOX").highestProcessedUid)

        fixture.windows.clear()
        fixture.validity = 11
        val reset = synchronizeConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            cursor = incremental.cursor, options = ConversationReadOptions(maxUidPositionsPerFolder = 2))
        assertEquals(setOf("INBOX"), reset.uidValidityResetFolders)
        assertEquals(11, reset.cursor.folders.getValue("INBOX").uidValidity)
        assertEquals(2L..3L, fixture.windows.first())
        assertEquals(0, fixture.opened)
    }

    @Test fun `partial batch failure keeps retry cursor and closes folder`() = runBlocking {
        val fixture = MessagePagesTest.Fixture().apply {
            repeat(5) { index -> rows[(index + 1).toLong()] = mail("<failure-$index@example.com>") }
            nextUid = 6
        }
        val checkpoint = ConversationSyncCursor("account", mapOf("INBOX" to ConversationFolderCursor(10, 0)))
        fixture.failFullMessageFetchAt = 2
        val failure = assertFailsWith<ConversationSyncException> {
            synchronizeConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
                cursor = checkpoint, options = ConversationReadOptions(fullMessageBatchSize = 2))
        }
        assertEquals(checkpoint, failure.retryCursor)
        assertEquals(listOf(3L, 4L), failure.failedLocations.map { it.uid })
        assertEquals(0, fixture.opened)
    }

    @Test fun `cancellation is not converted to partial synchronization`() = runBlocking<Unit> {
        val fixture = MessagePagesTest.Fixture().apply { rows[1] = mail("<cancel@example.com>"); nextUid = 2 }
        val transport = object : ConversationTransport {
            override suspend fun index(session: dev.reapermaga.mailkt.session.MailSession, folders: List<String>,
                options: ConversationReadOptions, cursor: ConversationSyncCursor?) = ConversationIndex(
                listOf(ConversationEnvelope(MessageLocation(session.id, "INBOX", 10, 1),
                    setOf("contact@example.com"), setOf("<cancel@example.com>"))),
                listOf(ConversationFolderSnapshot("INBOX", 10, 1, false)),
                mapOf("INBOX" to ConversationFolderCursor(10, 1)))
            override suspend fun download(session: dev.reapermaga.mailkt.session.MailSession,
                envelopes: List<ConversationEnvelope>, options: ConversationReadOptions): ConversationDownload =
                throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            synchronizeConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
                emptySet(), null, ConversationReadOptions(), transport)
        }
    }

    @Test fun `malformed message fails explicitly without leaking folder`() = runBlocking {
        val fixture = MessagePagesTest.Fixture()
        fixture.rows[1] = object : MimeMessage(mail("<malformed@example.com>")) {
            override fun writeTo(output: OutputStream) = throw jakarta.mail.MessagingException("malformed body")
        }
        fixture.nextUid = 2
        val failure = assertFailsWith<ConversationSyncException> {
            synchronizeConversations(fixture, listOf("INBOX"), setOf("contact@example.com"))
        }
        assertEquals(listOf(1L), failure.failedLocations.map { it.uid })
        assertEquals(0, fixture.opened)
    }

    @Test fun `managed indexing reconnects after nested SSL folder closure without reusing generation`() = runBlocking {
        val mailSession = ReconnectingMailSession()
        val manager = MailSessionManager(keepAliveInterval = 10.seconds, reconnectTimeout = 1.seconds,
            parentScope = this)
        val transport = RecoveringConversationTransport(mail("<managed-index@example.com>"), indexFailures = 1)
        try {
            val managed = manager.manage(mailSession) { mailSession.connect(testCredentials) }
            val result = synchronizeConversations(managed, listOf("INBOX"), setOf("contact@example.com"),
                emptySet(), null, recoveryOptions, transport)

            assertEquals(2, transport.indexConnections.size)
            assertNotSame(transport.indexConnections[0], transport.indexConnections[1])
            assertEquals(2, mailSession.connectCalls)
            assertEquals(1, result.metrics.recoveryAttempts)
            assertEquals(1, result.metrics.successfulRecoveries)
            assertEquals(listOf("<managed-index@example.com>"), result.history.threads.flatMap { it.messages }
                .map { it.message.messageID })
            assertEquals(0, transport.openResources)
            assertTrue(mailSession.closedStores >= 1)
        } finally {
            manager.stop()
        }
    }

    @Test fun `managed full-message recovery reindexes resets validity and does not duplicate`() = runBlocking {
        val mailSession = ReconnectingMailSession()
        val manager = MailSessionManager(keepAliveInterval = 10.seconds, reconnectTimeout = 1.seconds,
            parentScope = this)
        val transport = RecoveringConversationTransport(mail("<managed-download@example.com>"),
            downloadFailures = 1, validityAfterReconnect = 11)
        val checkpoint = ConversationSyncCursor("account", mapOf("INBOX" to ConversationFolderCursor(10, 0)))
        try {
            val managed = manager.manage(mailSession) { mailSession.connect(testCredentials) }
            val result = synchronizeConversations(managed, listOf("INBOX"), setOf("contact@example.com"),
                emptySet(), checkpoint, recoveryOptions, transport)

            assertEquals(2, transport.indexConnections.size)
            assertEquals(2, transport.downloadConnections.size)
            assertNotSame(transport.downloadConnections[0], transport.downloadConnections[1])
            assertEquals(setOf("INBOX"), result.uidValidityResetFolders)
            assertEquals(11, result.cursor.folders.getValue("INBOX").uidValidity)
            assertEquals(1, result.history.threads.flatMap { it.messages }.size)
            assertEquals(0, transport.openResources)
        } finally {
            manager.stop()
        }
    }

    @Test fun `managed recovery stops at configured limit and preserves retry cursor`() = runBlocking {
        val mailSession = ReconnectingMailSession()
        val manager = MailSessionManager(keepAliveInterval = 10.seconds, reconnectTimeout = 1.seconds,
            parentScope = this)
        val transport = RecoveringConversationTransport(mail("<never@example.com>"), indexFailures = Int.MAX_VALUE)
        val checkpoint = ConversationSyncCursor("account", mapOf("INBOX" to ConversationFolderCursor(10, 7)))
        try {
            val managed = manager.manage(mailSession) { mailSession.connect(testCredentials) }
            val failure = assertFailsWith<ConversationSyncException> {
                synchronizeConversations(managed, listOf("INBOX"), setOf("contact@example.com"),
                    emptySet(), checkpoint, recoveryOptions.copy(maxRecoveryAttempts = 2), transport)
            }

            assertEquals(3, transport.indexConnections.size)
            assertEquals(checkpoint, failure.retryCursor)
            assertContains(failure.message.orEmpty(), "after 2 retries")
            assertEquals(0, transport.openResources)
        } finally {
            manager.stop()
        }
    }

    @Test fun `managed cancellation propagates immediately and closes boundary resources`() = runBlocking {
        val mailSession = ReconnectingMailSession()
        val manager = MailSessionManager(keepAliveInterval = 10.seconds, reconnectTimeout = 1.seconds,
            parentScope = this)
        val transport = RecoveringConversationTransport(mail("<cancel-managed@example.com>"), cancelDownload = true)
        try {
            val managed = manager.manage(mailSession) { mailSession.connect(testCredentials) }
            assertFailsWith<CancellationException> {
                synchronizeConversations(managed, listOf("INBOX"), setOf("contact@example.com"),
                    emptySet(), null, recoveryOptions, transport)
            }
            assertEquals(1, mailSession.connectCalls)
            assertEquals(0, transport.openResources)
        } finally {
            manager.stop()
        }
    }

    @Test fun `two thousand UID performance comparison reduces delayed fetch runtime`() = runBlocking {
        suspend fun measured(batchSize: Int): ConversationSyncResult {
            val fixture = MessagePagesTest.Fixture().apply {
                repeat(2000) { index ->
                    rows[(index + 1).toLong()] = mail("<benchmark-$index@example.com>",
                        sender = if (index % 20 == 0) "contact@example.com" else "other@example.com")
                }
                nextUid = 2001
                fullMessageFetchDelayMillis = 1
            }
            return synchronizeConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
                options = ConversationReadOptions(fullMessageBatchSize = batchSize))
        }
        val individual = measured(1)
        val batched = measured(25)
        assertEquals(100, individual.metrics.fullMessageFetchCommands)
        assertEquals(4, batched.metrics.fullMessageFetchCommands)
        assertTrue(batched.metrics.fullMessageDownloadNanos < individual.metrics.fullMessageDownloadNanos)
        println("Conversation benchmark: individual=${individual.metrics.totalFetchCommands} commands/" +
            "${individual.metrics.fullMessageDownloadNanos / 1_000_000}ms, " +
            "batched=${batched.metrics.totalFetchCommands} commands/" +
            "${batched.metrics.fullMessageDownloadNanos / 1_000_000}ms")
    }

    private val testCredentials = MailCredentials(MailAuthMethod.PLAIN, "test", "test")
    private val recoveryOptions = ConversationReadOptions(
        recoveryBackoff = 1.milliseconds,
        recoveryTimeout = 2.seconds,
    )

    private class ReconnectingMailSession : MailSession {
        private val jakarta = Session.getInstance(Properties())
        @Volatile private var connection: MailConnection? = null
        var connectCalls = 0
        var closedStores = 0

        override val id = "account"
        override val isConnected: Boolean get() = connection?.store?.isConnected == true
        override val currentConnection: MailConnection? get() = connection

        override suspend fun connect(credentials: MailCredentials): MailConnection {
            connectCalls++
            val store = object : IMAPStore(jakarta, null) {
                private var live = true
                override fun isConnected() = live
                override fun close() {
                    if (live) closedStores++
                    live = false
                }
            }
            return MailConnection(jakarta, store).also { connection = it }
        }

        override suspend fun disconnect() {
            connection?.store?.close()
            connection = null
        }
    }

    private class RecoveringConversationTransport(
        private val source: MimeMessage,
        private val indexFailures: Int = 0,
        private val downloadFailures: Int = 0,
        private val validityAfterReconnect: Long = 10,
        private val cancelDownload: Boolean = false,
    ) : ConversationTransport {
        val indexConnections = mutableListOf<MailConnection>()
        val downloadConnections = mutableListOf<MailConnection>()
        var openResources = 0
        private var indexCalls = 0
        private var downloadCalls = 0

        override suspend fun index(session: MailSession, folders: List<String>, options: ConversationReadOptions,
            cursor: ConversationSyncCursor?): ConversationIndex {
            val connection = checkNotNull(session.currentConnection)
            indexConnections += connection
            openResources++
            try {
                indexCalls++
                if (indexCalls <= indexFailures) throw nestedSslFolderClosure()
                val validity = if (indexConnections.distinctBy { it.store }.size > 1) validityAfterReconnect else 10
                val reset = if (cursor?.folders?.get("INBOX")?.uidValidity?.let { it != validity } == true)
                    setOf("INBOX") else emptySet()
                val location = MessageLocation(session.id, "INBOX", validity, 1)
                return ConversationIndex(
                    listOf(ConversationEnvelope(location, setOf("contact@example.com"),
                        setOf(source.messageID), source.size)),
                    listOf(ConversationFolderSnapshot("INBOX", validity, 1, false)),
                    mapOf("INBOX" to ConversationFolderCursor(validity, 1)),
                    reset,
                )
            } finally {
                openResources--
            }
        }

        override suspend fun download(session: MailSession, envelopes: List<ConversationEnvelope>,
            options: ConversationReadOptions): ConversationDownload {
            val connection = checkNotNull(session.currentConnection)
            downloadConnections += connection
            openResources++
            try {
                if (cancelDownload) throw CancellationException("cancelled")
                downloadCalls++
                if (downloadCalls <= downloadFailures) throw nestedSslFolderClosure()
                val messages = envelopes.distinctBy { it.location }.map { envelope ->
                    val detached = MimeMessage(source)
                    LocatedMessage(envelope.location, HistoricalMessage(detached, envelope.location.uid,
                        envelope.location.uidValidity, null))
                }
                return ConversationDownload(messages, 0)
            } finally {
                openResources--
            }
        }

        private fun nestedSslFolderClosure(): FolderClosedException {
            val connectionFailure = MessagingException("connection failure", SSLException("unexpected_message"))
            return FolderClosedException(null, "folder closed").apply { setNextException(connectionFailure) }
        }
    }
}
