package dev.reapermaga.mailkt.session

import jakarta.mail.Session
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertIs
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.angus.mail.imap.IMAPStore
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MailSessionManagerTest {
    @Test
    fun `publishes reconnecting before replacing the connection`() = runBlocking {
        val manager =
            MailSessionManager(
                keepAliveInterval = 10.milliseconds,
                reconnectTimeout = 1.seconds,
                parentScope = this,
            )
        val session = FakeMailSession()
        val providerEntered = CompletableDeferred<Unit>()
        val allowReplacement = CompletableDeferred<Unit>()
        try {
            var initial = true
            val managed =
                manager.manage(session) {
                    if (initial) {
                        initial = false
                    } else {
                        providerEntered.complete(Unit)
                        allowReplacement.await()
                    }
                    it.connect(credentials())
                }
            session.connected = false

            providerEntered.await()
            assertTrue(managed.state.value is ManagedMailSessionState.Reconnecting)
            assertEquals(1, session.connectCalls)

            allowReplacement.complete(Unit)
            withTimeout(2.seconds) {
                managed.state
                    .filterIsInstance<ManagedMailSessionState.Connected>()
                    .first { it.reconnected }
            }
            assertEquals(2, session.connectCalls)
        } finally {
            allowReplacement.complete(Unit)
            manager.stop()
        }
    }

    @Test
    fun `reconnects a disconnected session and publishes state`() = runBlocking {
        val manager =
            MailSessionManager(
                keepAliveInterval = 10.milliseconds,
                reconnectTimeout = 1.seconds,
                parentScope = this,
            )
        val session = FakeMailSession()
        try {
            val managed = manager.manage(session) { it.connect(credentials()) }
            session.connected = false

            val state =
                withTimeout(2.seconds) {
                    managed.state
                        .filterIsInstance<ManagedMailSessionState.Connected>()
                        .first { it.reconnected }
                }

            assertTrue(state.reconnected)
            assertEquals(2, session.connectCalls)
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `removes a session after reconnect attempts are exhausted`() = runBlocking {
        val manager =
            MailSessionManager(
                keepAliveInterval = 10.milliseconds,
                reconnectTimeout = 1.seconds,
                maxReconnectAttempts = 2,
                parentScope = this,
            )
        val session = FakeMailSession()
        var initialConnection = true
        try {
            manager.manage(session) {
                if (initialConnection) {
                    initialConnection = false
                    it.connect(credentials())
                } else {
                    error("reconnect failed")
                }
            }
            session.connected = false

            withTimeout(2.seconds) { manager.sessions.first { it.isEmpty() } }

            assertEquals(0, manager.sessions.value.size)
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `timed out health check does not stop monitoring or delay other mailboxes and restores Connected`() = runBlocking {
        withTimeout(5.seconds) {
            val failed = CompletableDeferred<Throwable>()
            val manager = MailSessionManager(10.milliseconds, 500.milliseconds, parentScope = this,
                exceptionHandler = { failure, _ -> failed.complete(failure) })
            val blocked = FakeMailSession("blocked")
            val healthy = FakeMailSession("healthy")
            val entered = CompletableDeferred<Unit>()
            val otherChecks = CompletableDeferred<Unit>()
            val checks = AtomicInteger()
            try {
                val managed = manager.manage(blocked) { it.connect(credentials()) }
                blocked.onCheck = {
                    if (!entered.isCompleted) {
                        entered.complete(Unit)
                        CountDownLatch(1).await()
                    }
                }
                healthy.onCheck = { if (checks.incrementAndGet() >= 3) otherChecks.complete(Unit) }
                manager.manage(healthy) { it.connect(credentials()) }
                entered.await()
                otherChecks.await()
                assertFalse(failed.isCompleted, "Other mailboxes must progress while this check is blocked")
                assertIs<TimeoutException>(failed.await())
                managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first()
                assertEquals(1, blocked.connectCalls)
                assertEquals(2, manager.sessions.value.size)
            } finally { manager.stop() }
        }
    }

    @Test
    fun `timed out reconnect is retried even when provider leaves session connected`() = runBlocking {
        withTimeout(5.seconds) {
            val failed = CompletableDeferred<Throwable>()
            val manager = MailSessionManager(10.milliseconds, 100.milliseconds, parentScope = this,
                exceptionHandler = { failure, _ -> failed.complete(failure) })
            val session = FakeMailSession()
            var calls = 0
            try {
                val managed = manager.manage(session) {
                    calls++
                    val connection = it.connect(credentials())
                    if (calls == 2) awaitCancellation()
                    connection
                }
                session.connected = false
                assertIs<TimeoutException>(failed.await())
                managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first { it.reconnected }
                assertEquals(3, calls)
                assertEquals(0, managed.currentReconnectAttempt)
            } finally { manager.stop() }
        }
    }

    @Test
    fun `concurrent requests coalesce and stale generations cannot reconnect a replacement`() = runBlocking {
        withTimeout(5.seconds) {
            val manager = MailSessionManager(10.milliseconds, 1.seconds, parentScope = this)
            val session = FakeMailSession()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            try {
                val managed = manager.manage(session) {
                    if (++calls > 1) { entered.complete(Unit); release.await() }
                    it.connect(credentials())
                }
                val generation = managed.generation()
                val accepted = (1..20).map {
                    async(Dispatchers.Default) {
                        managed.requestRecovery(generation, ManagedMailSession.RecoveryReason.STORE_CLOSED)
                    }
                }.awaitAll().count { it }
                assertEquals(1, accepted)
                entered.await()
                assertIs<ManagedMailSessionState.Reconnecting>(managed.state.value)
                release.complete(Unit)
                managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first { it.reconnected }
                assertEquals(generation.number + 1, managed.generation().number)
                assertFalse(managed.requestRecovery(generation, ManagedMailSession.RecoveryReason.FOLDER_CLOSED))
                val checked = CompletableDeferred<Unit>()
                session.onCheck = { checked.complete(Unit) }
                checked.await()
                assertEquals(2, calls)
            } finally { release.complete(Unit); manager.stop() }
        }
    }

    @Test
    fun `parent cancellation and shutdown interrupt reconnect and reject further recovery`() = runBlocking {
        withTimeout(5.seconds) {
            for (cancelParent in listOf(false, true)) {
                val parent = SupervisorJob()
                val manager = MailSessionManager(10.milliseconds, 30.seconds,
                    parentScope = CoroutineScope(parent + Dispatchers.Default))
                val session = FakeMailSession()
                val entered = CompletableDeferred<Unit>()
                val exited = CompletableDeferred<Unit>()
                var initial = true
                try {
                    val managed = manager.manage(session) {
                        if (initial) { initial = false; it.connect(credentials()) }
                        else {
                            entered.complete(Unit)
                            try { awaitCancellation() } finally { exited.complete(Unit) }
                        }
                    }
                    managed.requestRecovery(managed.generation(), ManagedMailSession.RecoveryReason.STORE_CLOSED)
                    entered.await()
                    if (cancelParent) parent.cancelAndJoin() else manager.stop()
                    exited.await()
                    assertIs<ManagedMailSessionState.Stopped>(managed.state.value)
                    manager.stop()
                    assertIs<ManagedMailSessionState.Stopped>(managed.state.value)
                    assertFalse(managed.requestRecovery(managed.generation(), ManagedMailSession.RecoveryReason.STORE_CLOSED))
                    assertFalse(session.connected)
                } finally { manager.stop(); parent.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `request arriving during health check cannot be overwritten by healthy result`() = runBlocking {
        withTimeout(5.seconds) {
            val manager = MailSessionManager(10.milliseconds, 1.seconds, parentScope = this)
            val session = FakeMailSession()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            try {
                val managed = manager.manage(session) { it.connect(credentials()) }
                session.onCheck = { entered.complete(Unit); release.await() }
                entered.await()
                managed.requestRecovery(managed.generation(), ManagedMailSession.RecoveryReason.STORE_CLOSED)
                release.countDown()
                managed.state.filterIsInstance<ManagedMailSessionState.Connected>().first { it.reconnected }
                assertEquals(2, session.connectCalls)
            } finally { release.countDown(); manager.stop() }
        }
    }

    @Test
    fun `repeated health timeouts exhaust attempts and close session`() = runBlocking {
        withTimeout(5.seconds) {
            val manager = MailSessionManager(10.milliseconds, 50.milliseconds, maxReconnectAttempts = 2, parentScope = this)
            val session = FakeMailSession()
            try {
                val managed = manager.manage(session) { it.connect(credentials()) }
                session.onCheck = { CountDownLatch(1).await() }
                managed.monitorJob!!.join()
                assertEquals(2, managed.currentReconnectAttempt)
                assertIs<TimeoutException>(assertIs<ManagedMailSessionState.Stopped>(managed.state.value).cause)
                assertTrue(manager.sessions.value.isEmpty())
                assertFalse(session.connected)
            } finally { manager.stop() }
        }
    }

    private fun credentials() = MailCredentials.oauth2("user@example.com", "token")

    private class FakeMailSession(override val id: String = "fake-session") : MailSession {
        private val jakartaSession = Session.getInstance(Properties())
        private val store = jakartaSession.getStore("imap") as IMAPStore
        private var connection = MailConnection(jakartaSession, store)

        override val currentConnection: MailConnection get() = connection
        @Volatile var onCheck: () -> Unit = {}

        @Volatile var connected = false
        var connectCalls = 0

        override val isConnected: Boolean
            get() { onCheck(); return connected }

        override suspend fun connect(credentials: MailCredentials): MailConnection {
            connectCalls++
            connected = true
            connection = MailConnection(jakartaSession, jakartaSession.getStore("imap") as IMAPStore)
            return connection
        }

        override suspend fun disconnect() {
            connected = false
        }
    }
}
