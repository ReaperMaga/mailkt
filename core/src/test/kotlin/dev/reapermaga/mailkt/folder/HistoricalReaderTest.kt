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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import org.eclipse.angus.mail.iap.ConnectionException
import org.eclipse.angus.mail.iap.Response
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

    @Test fun `snapshot prefetch is bounded and preserves fixed position membership and order`() = runBlocking {
        val fixture = Fixture()
        val count = 2 * SNAPSHOT_UID_BATCH_SIZE + 3
        fixture.rows.clear()
        fixture.rows += (1L..count.toLong()).map { Row(it, Instant.parse("2026-09-02T12:00:00Z")) }
        fixture.onFetch = { _, _ ->
            if (fixture.fetchBatches.size == 1) {
                fixture.rows += Row(count + 1L, Instant.parse("2026-09-03T12:00:00Z"))
            }
        }
        val snapshot = ImapHistoricalTransport.snapshot(fixture.managed.lastConnection, "INBOX",
            HistoricalRange.Positions(2..count - 1))
        val expected = (count - 1L downTo 2L).toList()
        assertEquals(expected, snapshot.uids)
        assertEquals(expected, fixture.fetchBatches.flatten())
        assertEquals(listOf(500, 500, 1), fixture.fetchBatches.map { it.size })
        assertEquals(1, fixture.positionSelections)
        assertEquals(0, fixture.dateSelections)
        assertEquals(expected.size, fixture.resolvedUIDs)
        assertTrue(fixture.downloads.isEmpty())
        fixture.assertClosed()
    }

    @Test fun `date snapshot prefetch selects only matches newest first and empty snapshots skip fetch`() = runBlocking {
        val fixture = Fixture()
        val snapshot = ImapHistoricalTransport.snapshot(fixture.managed.lastConnection, "INBOX",
            HistoricalRange.Dates(LocalDate.parse("2026-09-02")..LocalDate.parse("2026-09-03")))
        assertEquals(listOf(3L, 2L), snapshot.uids)
        assertEquals(listOf(listOf(3L, 2L)), fixture.fetchBatches.toList())
        assertEquals(1, fixture.dateSelections)
        assertEquals(0, fixture.positionSelections)
        fixture.fetchBatches.clear()
        assertTrue(readMessagesFlow(fixture.managed, "INBOX", 9..10, options).toList().isEmpty())
        assertTrue(readMessagesFlow(fixture.managed, "INBOX",
            LocalDate.parse("2020-01-01")..LocalDate.parse("2020-01-02"), options).toList().isEmpty())
        fixture.rows.clear()
        assertTrue(readMessagesFlow(fixture.managed, "INBOX", 1..3, options).toList().isEmpty())
        assertTrue(fixture.fetchBatches.isEmpty())
        fixture.assertClosed()
    }

    @Test fun `mid snapshot folder and store failures recover but fail current scan without partial output`() = runBlocking {
        withTimeout(5.seconds) {
            for (folderFailure in listOf(true, false)) {
                val fixture = Fixture()
                val recoveryEntered = CompletableDeferred<Unit>()
                val releaseRecovery = CompletableDeferred<Unit>()
                val manager = fixture.startManaging(this) { recoveryEntered.complete(Unit); releaseRecovery.await() }
                try {
                    var original: Exception? = null
                    fixture.onUID = { source ->
                        if (fixture.resolvedUIDs == 1) {
                            original = if (folderFailure) {
                                // Reproduce Angus's exact wrapping: the SSL cause is reduced to text.
                                val bye = Response.byeResponse(SSLException("(unexpected_message) null"))
                                val connectionFailure = ConnectionException(null, bye)
                                FolderClosedException(source.owner, connectionFailure.message).also { assertNull(it.cause) }
                            } else MessagingException("wrapped", IOException(StoreClosedException(source.owner.store)))
                            throw original!!
                        }
                    }
                    val emitted = mutableListOf<Long>()
                    val failure = assertFailsWith<HistoricalReadException> {
                        readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect { emitted += it.uid }
                    }
                    assertSame(original, failure.cause)
                    assertEquals("resolve-range", failure.operation)
                    assertNull(failure.uid)
                    assertEquals(1, failure.attempt)
                    assertTrue(emitted.isEmpty())
                    assertTrue(fixture.downloads.isEmpty())
                    assertEquals(1, fixture.positionSelections)
                    assertEquals(1, fixture.resolvedUIDs)
                    fixture.assertClosed()
                    recoveryEntered.await()
                    assertEquals(if (folderFailure) ManagedMailSession.RecoveryReason.FOLDER_CLOSED
                        else ManagedMailSession.RecoveryReason.STORE_CLOSED, fixture.managed.recoveryReason())
                    releaseRecovery.complete(Unit)
                    fixture.managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first { it.reconnected }
                    assertEquals(1, fixture.replacements)
                    fixture.onUID = {}
                    assertEquals(listOf(3L, 2L, 1L), readMessagesFlow(fixture.managed, "INBOX", 1..3, options).toList().map { it.uid })
                    fixture.assertClosed()
                } finally { releaseRecovery.complete(Unit); manager.stop() }
            }
        }
    }

    @Test fun `failure in later prefetch batch closes folder requests recovery and never reselects`() = runBlocking {
        withTimeout(5.seconds) {
            val fixture = Fixture()
            fixture.rows.clear()
            fixture.rows += (1L..501L).map { Row(it, Instant.parse("2026-09-02T12:00:00Z")) }
            val manager = fixture.startManaging(this)
            try {
                fixture.onFetch = { _, folder ->
                    if (fixture.fetchBatches.size == 2) throw StoreClosedException(folder.store)
                }
                val failure = assertFailsWith<HistoricalReadException> {
                    readMessagesFlow(fixture.managed, "INBOX", 1..501, options).collect { fail("Partial emission") }
                }
                assertEquals("resolve-range", failure.operation)
                assertIs<StoreClosedException>(failure.cause)
                assertEquals(1, fixture.positionSelections)
                assertEquals(500, fixture.resolvedUIDs)
                assertTrue(fixture.downloads.isEmpty())
                fixture.managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first { it.reconnected }
                assertEquals(1, fixture.replacements)
                fixture.assertClosed()
            } finally { manager.stop() }
        }
    }

    @Test fun `locally owned snapshot deadlines request recovery and close folder`() = runBlocking {
        withTimeout(5.seconds) {
            for (downloadDeadline in listOf(true, false)) {
                val fixture = Fixture()
                val recoveryEntered = CompletableDeferred<Unit>()
                val releaseRecovery = CompletableDeferred<Unit>()
                val manager = fixture.startManaging(this) { recoveryEntered.complete(Unit); releaseRecovery.await() }
                try {
                    fixture.onFetch = { _, _ -> CountDownLatch(1).await() }
                    val deadlines = options.copy(
                        downloadTimeout = if (downloadDeadline) 100.milliseconds else 2.seconds,
                        recoveryTimeout = if (downloadDeadline) 2.seconds else 100.milliseconds,
                    )
                    val failure = assertFailsWith<HistoricalReadException> {
                        readMessagesFlow(fixture.managed, "INBOX", 1..3, deadlines).collect { fail("Partial emission") }
                    }
                    assertEquals("resolve-range", failure.operation)
                    assertEquals(1, failure.attempt)
                    assertIs<TimeoutException>(failure.cause)
                    recoveryEntered.await()
                    assertEquals(ManagedMailSession.RecoveryReason.SNAPSHOT_TIMEOUT, fixture.managed.recoveryReason())
                    assertEquals(1, fixture.positionSelections)
                    assertEquals(0, fixture.resolvedUIDs)
                    fixture.assertClosed()
                } finally { releaseRecovery.complete(Unit); manager.stop() }
            }
        }
    }

    @Test fun `snapshot parent cancellation and deadline propagate without recovery`() = runBlocking {
        withTimeout(5.seconds) {
            for (deadline in listOf(true, false)) {
                val fixture = Fixture()
                val entered = CompletableDeferred<Unit>()
                val manager = fixture.startManaging(this)
                try {
                    fixture.onFetch = { _, _ -> entered.complete(Unit); CountDownLatch(1).await() }
                    val read = async {
                        runCatching {
                            if (deadline) withTimeout(100.milliseconds) {
                                readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect()
                            } else readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect()
                        }
                    }
                    entered.await()
                    if (deadline) assertIs<TimeoutCancellationException>(read.await().exceptionOrNull())
                    else { read.cancelAndJoin(); assertTrue(read.isCancelled) }
                    assertEquals(0, fixture.replacements)
                    assertNull(fixture.managed.recoveryReason())
                    assertEquals(1L, fixture.managed.generation().number)
                    assertIs<ManagedMailSessionState.Connected>(fixture.managed.state.value)
                    assertTrue(fixture.downloads.isEmpty())
                    fixture.assertClosed()
                } finally { manager.stop() }
            }
        }
    }

    @Test fun `stale snapshot failure does not replace a newer connection`() = runBlocking {
        withTimeout(5.seconds) {
            val fixture = Fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val manager = fixture.startManaging(this)
            try {
                fixture.onUID = { source ->
                    if (fixture.resolvedUIDs == 1) {
                        entered.complete(Unit)
                        release.await()
                        throw FolderClosedException(source.owner)
                    }
                }
                val read = async { runCatching { readMessagesFlow(fixture.managed, "INBOX", 1..3, options).toList() } }
                entered.await()
                fixture.managed.requestRecovery(fixture.managed.generation(), ManagedMailSession.RecoveryReason.STORE_CLOSED)
                fixture.managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first { it.reconnected }
                val replacement = fixture.managed.generation()
                release.countDown()
                assertEquals("resolve-range", assertIs<HistoricalReadException>(read.await().exceptionOrNull()).operation)
                assertSame(replacement, fixture.managed.generation())
                assertNull(fixture.managed.recoveryReason())
                assertIs<ManagedMailSessionState.Connected>(fixture.managed.state.value)
                assertEquals(1, fixture.replacements)
                assertEquals(1, fixture.positionSelections)
                assertTrue(fixture.downloads.isEmpty())
                fixture.assertClosed()
            } finally { release.countDown(); manager.stop() }
        }
    }

    @Test fun `snapshot identity failures and arbitrary TLS text fail without requesting recovery`() = runBlocking {
        for (fault in listOf("validity", "expunged", "late-expunged", "zero-uid", "invalid-uid", "text-only")) {
            val fixture = Fixture()
            val manager = fixture.startManaging(this)
            try {
                fixture.onFetch = { sources, _ ->
                    when (fault) {
                        "validity" -> fixture.validity = 99
                        "expunged" -> sources[1].gone = true
                        "zero-uid" -> sources[1].uidOverride = 0
                        "invalid-uid" -> sources[1].uidOverride = -1
                        "text-only" -> throw IOException("BYE Jakarta Mail Exception: javax.net.ssl.SSLException: (unexpected_message) null")
                    }
                }
                if (fault == "late-expunged") fixture.onUID = { source -> source.gone = true }
                val failure = assertFailsWith<HistoricalReadException> {
                    readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect { fail("Partial emission") }
                }
                assertEquals("resolve-range", failure.operation)
                assertNull(failure.uid)
                assertEquals(1, failure.attempt)
                assertTrue(fixture.downloads.isEmpty())
                assertNull(fixture.managed.recoveryReason())
                assertEquals(0, fixture.replacements)
                assertIs<ManagedMailSessionState.Connected>(fixture.managed.state.value)
                fixture.assertClosed()
            } finally { manager.stop() }
        }
    }

    @Test fun `concurrent snapshot failures coalesce into one replacement`() = runBlocking {
        withTimeout(5.seconds) {
            val fixture = Fixture()
            val bothEntered = CompletableDeferred<Unit>()
            val arrivals = AtomicInteger()
            val releaseFailures = CountDownLatch(1)
            val recoveryEntered = CompletableDeferred<Unit>()
            val releaseRecovery = CompletableDeferred<Unit>()
            val manager = fixture.startManaging(this) { recoveryEntered.complete(Unit); releaseRecovery.await() }
            try {
                fixture.onFetch = { _, folder ->
                    if (arrivals.incrementAndGet() == 2) bothEntered.complete(Unit)
                    releaseFailures.await()
                    throw FolderClosedException(folder)
                }
                val reads = (1..2).map {
                    async { runCatching { readMessagesFlow(fixture.managed, "INBOX", 1..3, options).toList() } }
                }
                bothEntered.await()
                releaseFailures.countDown()
                reads.forEach { assertIs<HistoricalReadException>(it.await().exceptionOrNull()) }
                recoveryEntered.await()
                assertEquals(ManagedMailSession.RecoveryReason.FOLDER_CLOSED, fixture.managed.recoveryReason())
                assertEquals(1L, fixture.managed.generation().number)
                assertEquals(2, fixture.folders.size)
                assertTrue(fixture.downloads.isEmpty())
                fixture.assertClosed()
                releaseRecovery.complete(Unit)
                fixture.managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first { it.reconnected }
                assertEquals(1, fixture.replacements)
                assertEquals(2L, fixture.managed.generation().number)
            } finally { releaseFailures.countDown(); releaseRecovery.complete(Unit); manager.stop() }
        }
    }

    @Test fun `failed snapshot connection probe requests recovery for its candidate generation`() = runBlocking {
        withTimeout(5.seconds) {
            for (timeout in listOf(false, true)) {
                val fixture = Fixture()
                val manager = fixture.startManaging(this)
                val transport = object : HistoricalTransport by ImapHistoricalTransport {
                    override fun connected(connection: MailConnection): Boolean {
                        if (timeout) CountDownLatch(1).await()
                        throw StoreClosedException(connection.store)
                    }
                }
                try {
                    val failure = assertFailsWith<HistoricalReadException> {
                        historicalFlow(fixture.managed, "INBOX", HistoricalRange.Positions(1..3),
                            options.copy(recoveryTimeout = 100.milliseconds), transport).collect()
                    }
                    assertEquals("resolve-range", failure.operation)
                    if (timeout) assertIs<TimeoutException>(failure.cause) else assertIs<StoreClosedException>(failure.cause)
                    fixture.managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first { it.reconnected }
                    assertEquals(1, fixture.replacements)
                    assertTrue(fixture.folders.isEmpty())
                } finally { manager.stop() }
            }
        }
    }

    @Test fun `provider cancellation is not mistaken for a locally owned snapshot deadline`() = runBlocking {
        val unrelatedTimeout = assertFailsWith<TimeoutCancellationException> {
            withTimeout(1.milliseconds) { awaitCancellation() }
        }
        val fixture = Fixture()
        val manager = fixture.startManaging(this)
        try {
            fixture.onFetch = { _, _ -> throw unrelatedTimeout }
            val result = assertFailsWith<TimeoutCancellationException> {
                readMessagesFlow(fixture.managed, "INBOX", 1..3, options).collect()
            }
            // Coroutine stack-trace recovery may copy the exception across dispatcher boundaries.
            assertEquals(unrelatedTimeout.message, result.message)
            assertNull(fixture.managed.recoveryReason())
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
        val fetchBatches = CopyOnWriteArrayList<List<Long>>()
        var onFetch: (List<Source>, TestFolder) -> Unit = { _, _ -> }
        var onUID: (Source) -> Unit = {}
        var positionSelections = 0
        var dateSelections = 0
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
        override fun getMessages(start: Int, end: Int): Array<Message> {
            fixture.positionSelections++
            return (start..end).map { getMessage(it) }.toTypedArray()
        }
        override fun fetch(messages: Array<out Message>, profile: FetchProfile) {
            assertEquals(listOf(UIDFolder.FetchProfileItem.UID), profile.items.toList())
            assertTrue(profile.headerNames.isEmpty())
            val sources = messages.map { it as Source }
            fixture.fetchBatches += sources.map { it.row.uid }
            fixture.onFetch(sources, this)
            sources.forEach { it.prefetched = true }
        }
        override fun getMessage(number: Int): Message = Source(this, fixture.rows[number - 1], number)
        override fun getUID(message: Message): Long {
            val source = message as Source
            assertTrue(source.prefetched, "UID access must follow prefetch for the same message")
            fixture.onUID(source)
            if (fixture.failResolution) throw FolderClosedException(this)
            fixture.resolvedUIDs++
            return source.uidOverride ?: source.row.uid
        }
        override fun getMessageByUID(uid: Long): Message? = fixture.rows.indexOfFirst { it.uid == uid }.let {
            if (it < 0) null else getMessage(it + 1)
        }
        override fun search(term: SearchTerm): Array<Message> {
            fixture.dateSelections++
            return (1..messageCount).map { getMessage(it) }.filter { term.match(it) }.toTypedArray()
        }
    }

    private class Source(val owner: TestFolder, val row: Row, number: Int) : MimeMessage(owner, number) {
        var prefetched = false
        var gone = false
        var uidOverride: Long? = null
        override fun isExpunged() = gone
        private val contentMessage by lazy { MimeMessage(owner.fixture.jakarta).apply {
            val attachment = MimeBodyPart().apply { setText("payload-${row.uid}") }
            val nested = MimeBodyPart().apply { setContent(MimeMultipart(attachment)) }
            setContent(MimeMultipart(nested))
            saveChanges()
        } }
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
