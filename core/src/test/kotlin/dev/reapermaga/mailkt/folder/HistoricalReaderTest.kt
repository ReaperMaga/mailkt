package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.*
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.search.SearchTerm
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HistoricalReaderTest {
    private val options = HistoricalReadOptions(retryDelay = 1.milliseconds, recoveryTimeout = 2.seconds)

    @Test fun `positions dates order empty and received metadata`() = runBlocking {
        val fixture = Fixture()
        val positions = readMessagesFlow(fixture.managed, "INBOX", 2..3, options).toList()
        assertEquals(listOf(2L, 1L), positions.map { it.uid })
        assertEquals(fixture.rows[1].date, positions[0].receivedAt)
        assertEquals(listOf(3L, 2L), readMessagesFlow(fixture.managed, "INBOX",
            LocalDate.parse("2026-09-02")..LocalDate.parse("2026-09-03"), options).toList().map { it.uid })
        assertTrue(readMessagesFlow(fixture.managed, "INBOX", 9..10, options).toList().isEmpty())
        assertTrue(readMessagesFlow(fixture.managed, "INBOX",
            LocalDate.parse("2020-01-01")..LocalDate.parse("2020-01-02"), options).toList().isEmpty())
        fixture.assertClosed()
    }

    @Test fun `nested attachment survives all source folders closing`() = runBlocking {
        val fixture = Fixture()
        val result = readMessagesFlow(fixture.managed, "INBOX", 1..1, options).single()
        fixture.assertClosed()
        val mixed = result.message.content as MimeMultipart
        val nested = mixed.getBodyPart(0).content as MimeMultipart
        assertEquals("payload-3", nested.getBodyPart(0).inputStream.use { it.readBytes().decodeToString() })
        assertNull(result.message.folder)
    }

    @Test fun `nested disconnect resumes interrupted UID on replacement and freezes positions`() = runBlocking {
        val fixture = Fixture()
        val failed = CompletableDeferred<Unit>()
        fixture.onDownload = { uid, folder ->
            if (uid == 2L && fixture.downloads.count { it == uid } == 1) {
                fixture.store.live = false
                fixture.managed.updateState(ManagedMailSessionState.Reconnecting(1))
                failed.complete(Unit)
                throw MessagingException("copy failed", IOException(FolderClosedException(folder)))
            }
        }
        val job = async { readMessagesFlow(fixture.managed, "INBOX", 1..3, options).toList() }
        failed.await()
        fixture.rows += Row(4, Instant.parse("2026-09-04T12:00:00Z"))
        val replacement = fixture.replace()
        fixture.managed.updateState(ManagedMailSessionState.Connected(replacement, true))
        assertEquals(listOf(3L, 2L, 1L), job.await().map { it.uid })
        assertEquals(listOf(3L, 2L, 2L, 1L), fixture.downloads.toList())
        fixture.assertClosed()
    }

    @Test fun `slow downstream gets no prefetch and reconnect does not cancel processing`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val results = mutableListOf<Long>()
        val job = launch {
            readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect {
                if (it.uid == 3L) {
                    entered.complete(Unit)
                    release.await()
                    assertTrue(it.message.content is MimeMultipart)
                }
                results += it.uid
            }
        }
        entered.await()
        assertEquals(listOf(3L), fixture.downloads.toList())
        fixture.assertClosed()
        fixture.managed.updateState(ManagedMailSessionState.Reconnecting(1))
        fixture.managed.updateState(ManagedMailSessionState.Connected(fixture.replace(), true))
        release.complete(Unit)
        job.join()
        assertEquals(listOf(3L, 2L, 1L), results)
    }

    @Test fun `caller buffer retains detached messages through reconnect`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<Unit>()
        fixture.onDownload = { uid, _ -> if (uid == 1L) queued.complete(Unit) }
        val result = mutableListOf<Long>()
        val job = launch {
            readMessagesFlow(fixture.managed, "INBOX", 1..3, options).buffer(1).collect {
                if (it.uid == 3L) { entered.complete(Unit); release.await() }
                assertTrue(it.message.content is MimeMultipart)
                result += it.uid
            }
        }
        entered.await(); queued.await()
        fixture.managed.updateState(ManagedMailSessionState.Connected(fixture.replace(), true))
        release.complete(Unit); job.join()
        assertEquals(listOf(3L, 2L, 1L), result)
        fixture.assertClosed()
    }

    @Test fun `expunged and changed validity fail without skipping`() = runBlocking {
        for (changeValidity in listOf(false, true)) {
            val fixture = Fixture()
            val failure = assertFailsWith<HistoricalReadException> {
                readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect {
                    if (changeValidity) fixture.validity = 9 else fixture.rows.removeIf { row -> row.uid == 2L }
                }
            }
            assertEquals(2L, failure.uid)
            assertEquals(1, failure.attempt)
            fixture.assertClosed()
        }
    }

    @Test fun `cancellation interrupts active download and closes folder`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        fixture.onDownload = { _, _ -> entered.complete(Unit); CountDownLatch(1).await() }
        val job = launch { readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        fixture.assertClosed()
    }

    @Test fun `exhausted retries and nonrecoverable size failures have context`() = runBlocking {
        val fixture = Fixture()
        fixture.onDownload = { _, folder -> throw MessagingException("outer", IOException(FolderClosedException(folder))) }
        val failure = assertFailsWith<HistoricalReadException> {
            readMessagesFlow(fixture.managed, "INBOX", 1..3, options.copy(maxRecoveryAttempts = 2)).collect()
        }
        assertEquals(3, failure.attempt)
        assertEquals(3L, failure.uid)
        assertEquals("download", failure.operation)
        assertEquals(3, fixture.downloads.size)
        fixture.assertClosed()
        val sizeFixture = Fixture()
        val tooLarge = assertFailsWith<HistoricalReadException> {
            readMessagesFlow(sizeFixture.managed, "INBOX", 1..1, options.copy(maxMessageBytes = 20)).collect()
        }
        assertEquals(1, tooLarge.attempt)
        assertEquals("download", tooLarge.operation)
        sizeFixture.assertClosed()
    }

    @Test fun `per message timeouts are bounded and do not include consumer delay`() = runBlocking {
        val fixture = Fixture()
        val manager = fixture.startManaging(this)
        try {
            val quick = options.copy(downloadTimeout = 100.milliseconds, recoveryTimeout = 1.seconds,
                maxRecoveryAttempts = 1)
            fixture.onDownload = { _, _ -> CountDownLatch(1).await() }
            val failure = assertFailsWith<HistoricalReadException> {
                readMessagesFlow(fixture.managed, "INBOX", 1..1, quick).collect()
            }
            assertEquals(2, failure.attempt)
            fixture.assertClosed()
            fixture.onDownload = { _, _ -> }
            readMessagesFlow(fixture.managed, "INBOX", 1..2, quick).collect {
                delay(1100) // Longer than both budgets, and outside either scope.
                assertTrue(it.message.content is MimeMultipart)
            }
            fixture.assertClosed()
        } finally { manager.stop() }
    }

    @Test fun `exception chains detect wrapped transport errors and resist cycles`() {
        val error = MessagingException("top")
        error.setNextException(MessagingException("middle", IOException(StoreClosedException(null))))
        assertTrue(isHistoricalTransportFailure(error))
        assertFalse(isHistoricalTransportFailure(IOException("disk error")))
        val cycle = IOException("cycle")
        cycle.initCause(MessagingException("nested", cycle))
        assertFalse(isHistoricalTransportFailure(cycle))
    }

    @Test fun `known dead store is not downloaded again while awaiting replacement`() = runBlocking {
        val fixture = Fixture()
        fixture.onDownload = { _, _ -> throw StoreClosedException(fixture.store) }
        // Even if a provider still reports connected, StoreClosedException invalidates this
        // connection for this scan. Only a replacement state can make it usable again.
        val failure = assertFailsWith<HistoricalReadException> {
            readMessagesFlow(fixture.managed, "INBOX", 1..1,
                options.copy(recoveryTimeout = 200.milliseconds)).collect()
        }
        assertEquals(1, fixture.downloads.size)
        assertIs<StoreClosedException>(failure.cause)
        assertTrue(failure.suppressed.any { it is TimeoutCancellationException })
        fixture.assertClosed()
    }

    @Test fun `stopped session fails and waiting reconnect is cancellable`() = runBlocking {
        val fixture = Fixture()
        fixture.managed.updateState(ManagedMailSessionState.Stopped())
        val failure = assertFailsWith<HistoricalReadException> {
            readMessagesFlow(fixture.managed, "INBOX", 1..1, options).collect()
        }
        assertEquals("resolve-range", failure.operation)
        assertTrue(fixture.folders.isEmpty())
        val waiting = Fixture()
        waiting.managed.updateState(ManagedMailSessionState.Reconnecting(1))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            readMessagesFlow(waiting.managed, "INBOX", 1..1, options).collect()
        }
        job.cancelAndJoin()
        assertTrue(fixture.folders.isEmpty())
    }

    @Test fun `downstream failure is never retried and collection is cold`() = runBlocking {
        val fixture = Fixture()
        val scan = readMessagesFlow(fixture.managed, "INBOX", 1..3, options)
        assertTrue(fixture.folders.isEmpty())
        val downstream = IOException("consumer failure")
        assertSame(downstream, assertFailsWith<IOException> {
            scan.collect { throw downstream }
        })
        assertEquals(listOf(3L), fixture.downloads.toList())
        assertEquals(listOf(3L, 2L, 1L), scan.toList().map { it.uid })
        fixture.assertClosed()
    }

    @Test fun `unsupported UID and interrupted snapshot fail explicitly`() = runBlocking {
        val fixture = Fixture()
        fixture.sticky = false
        val failure = assertFailsWith<HistoricalReadException> {
            readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect()
        }
        assertEquals("resolve-range", failure.operation)
        assertEquals(1, fixture.folders.size)
        assertTrue(fixture.downloads.isEmpty())
        fixture.assertClosed()
        fixture.sticky = true
        fixture.failResolution = true
        val interrupted = assertFailsWith<HistoricalReadException> {
            readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect()
        }
        assertEquals("resolve-range", interrupted.operation)
        assertEquals(2, fixture.folders.size) // No re-resolution against shifting positions.
        fixture.assertClosed()
    }

    @Test fun `real manager escalates folder closures and resumes same UID with original snapshot`() = runBlocking {
        withTimeout(5.seconds) {
            val fixture = Fixture()
            val failedStore = fixture.store
            val manager = fixture.startManaging(this) {
                fixture.rows += Row(4, Instant.parse("2026-09-04T12:00:00Z"))
            }
            try {
                fixture.onDownload = { uid, folder ->
                    if (uid == 2L && (folder as TestFolder).testStore === failedStore) {
                        // The pool still claims to be healthy through all three failures.
                        assertTrue(failedStore.live)
                        throw IOException(FolderClosedException(folder))
                    }
                }
                val result = readMessagesFlow(fixture.managed, "INBOX", 1..3, options).toList()
                assertEquals(listOf(3L, 2L, 1L), result.map { it.uid })
                assertEquals(listOf(3L, 2L, 2L, 2L, 2L, 1L), fixture.downloads.toList())
                assertEquals(1, fixture.replacements)
                assertEquals(3, fixture.resolvedUIDs)
                assertIs<ManagedMailSessionState.Connected>(fixture.managed.state.value)
                assertEquals(2, fixture.managed.generation().number)
                result.forEach { assertNull(it.message.folder); assertTrue(it.message.content is MimeMultipart) }
                fixture.assertClosed()
            } finally { manager.stop() }
        }
    }

    @Test fun `real manager replaces connected failed store and validates UIDVALIDITY on replacement`() = runBlocking {
        withTimeout(5.seconds) {
            for (changeValidity in listOf(false, true)) {
                val fixture = Fixture()
                val failedStore = fixture.store
                val manager = fixture.startManaging(this) { if (changeValidity) fixture.validity = 99 }
                try {
                    fixture.onDownload = { uid, folder ->
                        if (uid == 2L && (folder as TestFolder).testStore === failedStore) {
                            assertTrue(failedStore.live)
                            throw MessagingException("outer", IOException(StoreClosedException(failedStore)))
                        }
                    }
                    val emitted = mutableListOf<Long>()
                    suspend fun collect() = readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect { emitted += it.uid }
                    if (changeValidity) {
                        val error = assertFailsWith<HistoricalReadException> { collect() }
                        assertEquals(2L, error.uid)
                        assertEquals(2, error.attempt)
                        assertTrue(error.cause!!.message!!.contains("UIDVALIDITY"))
                        assertEquals(listOf(3L), emitted)
                    } else {
                        collect()
                        assertEquals(listOf(3L, 2L, 1L), emitted)
                        assertEquals(listOf(3L, 2L, 2L, 1L), fixture.downloads.toList())
                    }
                    assertEquals(1, fixture.replacements)
                    assertEquals(3, fixture.resolvedUIDs)
                    fixture.assertClosed()
                } finally { manager.stop() }
            }
        }
    }

    @Test fun `real manager recovery exhaustion fails at interrupted UID and closes resources`() = runBlocking {
        withTimeout(5.seconds) {
            val fixture = Fixture()
            val manager = fixture.startManaging(this)
            try {
                fixture.onDownload = { uid, folder -> if (uid == 2L) throw StoreClosedException(folder.store) }
                val emitted = mutableListOf<Long>()
                val error = assertFailsWith<HistoricalReadException> {
                    readMessagesFlow(fixture.managed, "INBOX", 1..3, options.copy(maxRecoveryAttempts = 2))
                        .collect { emitted += it.uid }
                }
                assertEquals(listOf(3L), emitted)
                assertEquals(listOf(3L, 2L, 2L, 2L), fixture.downloads.toList())
                assertEquals(2L, error.uid)
                assertEquals(3, error.attempt)
                assertIs<StoreClosedException>(error.cause)
                fixture.assertClosed()
            } finally { manager.stop() }
            assertFalse(fixture.store.live)
        }
    }

    @Test fun `real manager shutdown stops historical recovery wait and cancellation propagates`() = runBlocking {
        withTimeout(5.seconds) {
            for (cancelRead in listOf(false, true)) {
                val fixture = Fixture()
                val entered = CompletableDeferred<Unit>()
                val manager = fixture.startManaging(this) { entered.complete(Unit); awaitCancellation() }
                try {
                    fixture.onDownload = { _, folder -> throw StoreClosedException(folder.store) }
                    val read = async {
                        runCatching { readMessagesFlow(fixture.managed, "INBOX", 1..1, options).collect() }
                    }
                    entered.await()
                    if (cancelRead) {
                        read.cancelAndJoin()
                        assertTrue(read.isCancelled)
                    } else {
                        manager.stop()
                        val error = assertIs<HistoricalReadException>(read.await().exceptionOrNull())
                        assertEquals("download", error.operation)
                        assertEquals(3L, error.uid)
                    }
                    fixture.assertClosed()
                } finally { manager.stop() }
            }
        }
    }

    @Test fun `parent deadline is propagated without historical retries`() = runBlocking {
        val fixture = Fixture()
        val manager = fixture.startManaging(this)
        try {
            fixture.onDownload = { _, _ -> CountDownLatch(1).await() }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100.milliseconds) {
                    readMessagesFlow(fixture.managed, "INBOX", 1..1, options).collect()
                }
            }
            assertEquals(listOf(3L), fixture.downloads.toList())
            assertEquals(0, fixture.replacements)
            fixture.assertClosed()
        } finally { manager.stop() }
    }

    private data class Row(val uid: Long, val date: Instant)

    private class Fixture {
        val rows = CopyOnWriteArrayList((1L..3L).map { Row(it, Instant.parse("2026-09-0${it}T12:00:00Z")) })
        val folders = CopyOnWriteArrayList<TestFolder>()
        val downloads = CopyOnWriteArrayList<Long>()
        @Volatile var validity = 7L
        var sticky = true
        var failResolution = false
        var resolvedUIDs = 0
        var replacements = 0
        var onDownload: (Long, Folder) -> Unit = { _, _ -> }
        val jakarta = Session.getInstance(Properties())
        var store = TestStore(this)
        private val mail = object : MailSession {
            override val id = "test"
            override val isConnected get() = store.live
            override val currentConnection get() = MailConnection(jakarta, store)
            override suspend fun connect(credentials: MailCredentials) = currentConnection
            override suspend fun disconnect() { store.live = false }
        }
        var managed = ManagedMailSession(mail, mail.currentConnection) { mail.currentConnection }
        suspend fun startManaging(scope: CoroutineScope, onReconnect: suspend () -> Unit = {}): MailSessionManager {
            val manager = MailSessionManager(10.milliseconds, 1.seconds, parentScope = scope)
            var initial = true
            managed = manager.manage(mail) {
                if (initial) { initial = false; mail.currentConnection }
                else { onReconnect(); replacements++; replace() }
            }
            return manager
        }
        fun replace(): MailConnection { store.live = false; store = TestStore(this); return mail.currentConnection }
        fun assertClosed() { assertTrue(folders.isNotEmpty()); folders.forEach { assertFalse(it.opened); assertEquals(1, it.closes) } }
    }

    private class TestStore(val fixture: Fixture) : IMAPStore(fixture.jakarta, null) {
        @Volatile var live = true
        override fun isConnected() = live
        override fun getFolder(name: String): Folder = TestFolder(this).also { fixture.folders += it }
    }

    private class TestFolder(val testStore: TestStore) : IMAPFolder("INBOX", '/', testStore, false) {
        val fixture get() = testStore.fixture
        var opened = false
        var closes = 0
        override fun open(mode: Int) { opened = true }
        override fun close(expunge: Boolean) { opened = false; closes++ }
        override fun isOpen() = opened
        override fun getUIDValidity() = fixture.validity
        override fun getUIDNotSticky() = !fixture.sticky
        override fun getMessageCount() = fixture.rows.size
        override fun getMessages(start: Int, end: Int): Array<Message> = (start..end).map { getMessage(it) }.toTypedArray()
        override fun getMessage(number: Int): Message = Source(this, fixture.rows[number - 1], number)
        override fun getUID(message: Message): Long {
            if (fixture.failResolution) throw FolderClosedException(this)
            fixture.resolvedUIDs++
            return (message as Source).row.uid
        }
        override fun getMessageByUID(uid: Long): Message? = fixture.rows.indexOfFirst { it.uid == uid }.let {
            if (it < 0) null else getMessage(it + 1)
        }
        override fun search(term: SearchTerm): Array<Message> = (1..messageCount).map { getMessage(it) }.filter { term.match(it) }.toTypedArray()
    }

    private class Source(val owner: TestFolder, val row: Row, number: Int) : MimeMessage(owner, number) {
        private val contentMessage = MimeMessage(owner.fixture.jakarta).apply {
            val attachment = MimeBodyPart().apply { setText("payload-${row.uid}") }
            val nested = MimeBodyPart().apply { setContent(MimeMultipart(attachment)) }
            setContent(MimeMultipart(nested))
            saveChanges()
        }
        override fun getReceivedDate(): Date = Date.from(row.date)
        override fun getSize() = -1 // Exercise the streaming size cap, not just advertised size.
        override fun writeTo(out: OutputStream) {
            owner.fixture.downloads += row.uid
            owner.fixture.onDownload(row.uid, owner)
            if (!owner.opened) throw FolderClosedException(owner)
            contentMessage.writeTo(out)
        }
    }
}
