package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailConnection
import dev.reapermaga.mailkt.session.ManagedMailSessionState
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.angus.mail.imap.IMAPStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

class FolderWatcherTest {
    @Test
    fun `detached message keeps attachment readable`() {
        val detached = detachMessage(message("normal", "attachment bytes"))
        val multipart = assertIs<MimeMultipart>(detached.content)
        val attachment = multipart.getBodyPart(1)

        assertContentEquals("attachment bytes".encodeToByteArray(), attachment.inputStream.readAllBytes())
        assertEquals(null, detached.folder)
    }

    @Test
    fun `reconnection before emission cancels old generation and uses new connection`() = runBlocking {
        val first = connection()
        val second = connection()
        val states = MutableStateFlow<ManagedMailSessionState>(connected(first))
        val firstStarted = CompletableDeferred<Unit>()
        val firstCleaned = CompletableDeferred<Unit>()
        val collected = mutableListOf<String>()
        val job =
            launch {
                watchManagedConnections(states, "INBOX", ZERO) { connection ->
                        if (connection === first) {
                            flow {
                                firstStarted.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    firstCleaned.complete(Unit)
                                }
                            }
                        } else {
                            flow { emit(message("new")) }
                        }
                    }
                    .take(1)
                    .collect { collected += it.subject }
            }

        firstStarted.await()
        states.value = ManagedMailSessionState.Reconnecting(1)
        firstCleaned.await()
        states.value = connected(second, reconnected = true)
        withTimeout(2.seconds) { job.join() }

        assertEquals(listOf("new"), collected)
    }

    @Test
    fun `message remains readable when reconnection occurs during suspended processing`() = runBlocking {
        val first = connection()
        val second = connection()
        val states = MutableStateFlow<ManagedMailSessionState>(connected(first))
        val processing = CompletableDeferred<Message>()
        val resume = CompletableDeferred<Unit>()
        val bytes = CompletableDeferred<ByteArray>()
        val job =
            launch {
                watchManagedConnections(states, "INBOX", ZERO) { connection ->
                        if (connection === first) flow { emit(detachMessage(message("old", "safe"))) }
                        else flow { awaitCancellation() }
                    }
                    .collect { detached ->
                        processing.complete(detached)
                        resume.await()
                        val multipart = detached.content as MimeMultipart
                        bytes.complete(multipart.getBodyPart(1).inputStream.readAllBytes())
                        awaitCancellation()
                    }
            }

        processing.await()
        states.value = ManagedMailSessionState.Reconnecting(1)
        states.value = connected(second, reconnected = true)
        resume.complete(Unit)
        assertContentEquals("safe".encodeToByteArray(), withTimeout(2.seconds) { bytes.await() })
        job.cancelAndJoin()
    }

    @Test
    fun `buffered old-generation messages are detached and remain readable`() = runBlocking {
        val first = connection()
        val second = connection()
        val states = MutableStateFlow<ManagedMailSessionState>(connected(first))
        val firstReceived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bodies = mutableListOf<String>()
        val job =
            launch {
                watchManagedConnections(states, "INBOX", ZERO) { connection ->
                        if (connection === first) {
                            flow {
                                emit(detachMessage(message("one", "one-body")))
                                emit(detachMessage(message("two", "two-body")))
                                awaitCancellation()
                            }
                        } else flow { awaitCancellation() }
                    }
                    .buffer(2)
                    .collect { detached ->
                        if (bodies.isEmpty()) {
                            firstReceived.complete(Unit)
                            release.await()
                        }
                        val multipart = detached.content as MimeMultipart
                        bodies += multipart.getBodyPart(1).inputStream.readAllBytes().decodeToString()
                        if (bodies.size == 2) currentCoroutineContext().cancel()
                    }
            }

        firstReceived.await()
        states.value = ManagedMailSessionState.Reconnecting(1)
        states.value = connected(second, reconnected = true)
        release.complete(Unit)
        withTimeout(2.seconds) { job.join() }

        assertEquals(listOf("one-body", "two-body"), bodies)
    }

    @Test
    fun `unexpected folder closure transparently restarts current generation`() = runBlocking {
        val current = connection()
        val attempts = AtomicInteger()

        val delivered =
            watchManagedConnections(MutableStateFlow(connected(current)), "INBOX", ZERO) {
                    if (attempts.incrementAndGet() == 1) {
                        flow { throw FolderWatchException("closed") }
                    } else {
                        flow {
                            emit(message("recovered"))
                            awaitCancellation()
                        }
                    }
                }
                .first()

        assertEquals("recovered", delivered.subject)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancellation during processing cleans generation and is not swallowed`() = runBlocking {
        val cleaned = CompletableDeferred<Unit>()
        val processing = CompletableDeferred<Unit>()
        val job =
            launch {
                watchManagedConnections(MutableStateFlow(connected(connection())), "INBOX", ZERO) {
                        flow {
                            try {
                                emit(message("message"))
                                awaitCancellation()
                            } finally {
                                cleaned.complete(Unit)
                            }
                        }
                    }
                    .collect {
                        processing.complete(Unit)
                        awaitCancellation()
                    }
            }

        processing.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(cleaned.isCompleted)
    }

    @Test
    fun `repeated reconnects have one active generation and exactly once cleanup`() = runBlocking {
        val connections = List(4) { connection() }
        val states = MutableStateFlow<ManagedMailSessionState>(connected(connections.first()))
        val starts = Channel<MailConnection>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val cleanups = AtomicInteger()
        val job =
            launch {
                watchManagedConnections(states, "INBOX", ZERO) { connection ->
                        flow {
                            starts.send(connection)
                            val count = active.incrementAndGet()
                            maximumActive.updateAndGet { maxOf(it, count) }
                            try {
                                awaitCancellation()
                            } finally {
                                active.decrementAndGet()
                                cleanups.incrementAndGet()
                            }
                        }
                    }
                    .collect()
            }

        assertTrue(starts.receive() === connections[0])
        connections.drop(1).forEachIndexed { index, connection ->
            states.value = ManagedMailSessionState.Reconnecting(index + 1)
            states.value = connected(connection, reconnected = true)
            assertTrue(starts.receive() === connection)
        }
        job.cancelAndJoin()

        assertEquals(1, maximumActive.get())
        assertEquals(0, active.get())
        assertEquals(connections.size, cleanups.get())
    }

    @Test
    fun `resource cleanup action runs exactly once under concurrent close paths`() = runBlocking {
        val lifecycle = WatchLifecycle()
        val folders = AtomicInteger()
        val listeners = AtomicInteger()
        val idleManagers = AtomicInteger()
        val jobs = AtomicInteger()
        lifecycle.cleanupAction = {
            folders.incrementAndGet()
            listeners.incrementAndGet()
            idleManagers.incrementAndGet()
            jobs.incrementAndGet()
        }

        List(32) { launch { lifecycle.cleanup() } }.forEach { it.join() }

        assertEquals(1, folders.get())
        assertEquals(1, listeners.get())
        assertEquals(1, idleManagers.get())
        assertEquals(1, jobs.get())
    }

    @Test
    fun `watching is cold and each collector owns a generation`() = runBlocking {
        val starts = AtomicInteger()
        val watched =
            watchManagedConnections(MutableStateFlow(connected(connection())), "INBOX", ZERO) {
                flow {
                    starts.incrementAndGet()
                    emit(message("value"))
                    awaitCancellation()
                }
            }

        assertEquals(0, starts.get())
        assertEquals(2, listOf(watched.first(), watched.first()).size)
        assertEquals(2, starts.get())
    }

    @Test
    fun `stopped session terminates with non-recoverable exception`() = runBlocking {
        val states = MutableStateFlow<ManagedMailSessionState>(ManagedMailSessionState.Stopped())
        var failure: FolderWatchException? = null
        try {
            watchManagedConnections(states, "INBOX", ZERO) { flow { awaitCancellation() } }.first()
        } catch (exception: FolderWatchException) {
            failure = exception
        }

        assertFalse(requireNotNull(failure).recoverable)
    }

    private fun connected(connection: MailConnection, reconnected: Boolean = false) =
        ManagedMailSessionState.Connected(connection, reconnected)

    private fun connection(): MailConnection {
        val session = Session.getInstance(Properties())
        return MailConnection(session, session.getStore("imap") as IMAPStore)
    }

    private fun message(subject: String, attachment: String = "body"): MimeMessage {
        val session = Session.getInstance(Properties())
        return MimeMessage(session).apply {
            this.subject = subject
            val text = MimeBodyPart().apply { setText("text") }
            val file =
                MimeBodyPart().apply {
                    dataHandler =
                        DataHandler(ByteArrayDataSource(attachment.encodeToByteArray(), "text/plain"))
                    fileName = "attachment.txt"
                }
            setContent(MimeMultipart(text, file))
            saveChanges()
        }
    }
}
