package dev.reapermaga.mailkt.internal.connection

import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MailboxEvent
import dev.reapermaga.mailkt.model.MailboxState
import dev.reapermaga.mailkt.model.RecoveryReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.IOException
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionManagerTest {
    @Test
    fun openConnectsAndPublishesGenerationOne() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        assertEquals(MailboxState.Connected, m.state.value)
        assertEquals(1, m.currentGeneration)
        m.close()
    }

    @Test
    fun initialAuthFailureIsTypedAndLeavesNothingOpen() = runBlocking {
        val factory = FakeFactory().apply { next = { throw MailException.AuthenticationRequired() } }
        assertFailsWith<MailException.AuthenticationRequired> { openManager(factory) }
        Unit
    }

    @Test
    fun initialNetworkFailureIsConnectionFailedWithoutRetry() = runBlocking {
        val factory = FakeFactory().apply { next = { throw SocketException("boom") } }
        val e = assertFailsWith<MailException.ConnectionFailed> { openManager(factory) }
        assertEquals(RecoveryReason.SOCKET, e.recovery)
        assertEquals(1, factory.attempts.get())
    }

    @Test
    fun initialAttemptTimeoutBecomesConnectionFailed() = runBlocking {
        val factory = FakeFactory().apply { next = { CompletableDeferred<FakeConnection>().await() } }
        val policy = TEST_POLICY.copy(attemptTimeout = kotlin.time.Duration.parse("100ms"))
        val e = assertFailsWith<MailException.ConnectionFailed> { openManager(factory, policy = policy) }
        assertEquals(RecoveryReason.TIMEOUT, e.recovery)
    }

    @Test
    fun reportedFailureReconnectsAndClosesObsoleteConnectionOnce() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        val first = factory.created[0]
        m.reportFailure(1, RecoveryReason.SOCKET)
        m.await { it == MailboxState.Connected && m.currentGeneration == 2L }
        assertEquals(1, first.closes.get())
        assertEquals(0, factory.created[1].closes.get())
        m.close()
        assertEquals(1, factory.created[1].closes.get())
        assertEquals(1, first.closes.get())
    }

    @Test
    fun concurrentReportsForSameGenerationCoalesce() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        val gate = CompletableDeferred<Unit>()
        factory.next = { gate.await(); FakeConnection() }
        repeat(5) { m.reportFailure(1, RecoveryReason.NETWORK) }
        m.await { it is MailboxState.Reconnecting }
        gate.complete(Unit)
        m.await { it == MailboxState.Connected && m.currentGeneration == 2L }
        assertEquals(2, factory.attempts.get())
        m.close()
    }

    @Test
    fun staleGenerationReportCannotInvalidateNewerConnection() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        m.reportFailure(1, RecoveryReason.SOCKET)
        m.await { it == MailboxState.Connected && m.currentGeneration == 2L }
        m.reportFailure(1, RecoveryReason.SOCKET)
        assertEquals(MailboxState.Connected, m.state.value)
        assertEquals(2, factory.attempts.get())
        assertEquals(0, factory.created[1].closes.get())
        m.close()
    }

    @Test
    fun operationFailureRequestsImmediateRecoveryAndIsTyped() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        val e = assertFailsWith<MailException.ConnectionFailed> {
            m.withConnection { throw IOException("dropped") }
        }
        assertEquals(RecoveryReason.NETWORK, e.recovery)
        m.await { it == MailboxState.Connected && m.currentGeneration == 2L }
        m.close()
    }

    @Test
    fun operationsPinTheirGenerationAndUnrelatedErrorsDoNotReconnect() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        val gen = m.withConnection { it.generation }
        assertEquals(1, gen)
        assertFailsWith<MailException.Unexpected> { m.withConnection { error("feature bug") } }
        assertEquals(1, factory.attempts.get())
        m.close()
    }

    @Test
    fun ownerCancellationIsNotClassifiedAsTransportFailure() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        val started = CompletableDeferred<Unit>()
        val job = launch { m.withConnection { started.complete(Unit); CompletableDeferred<Unit>().await() } }
        started.await()
        job.cancelAndJoin()
        yield()
        assertEquals(MailboxState.Connected, m.state.value)
        assertEquals(1, factory.attempts.get())
        m.close()
    }

    @Test
    fun healthCheckFailureTriggersRecoveryAndAuthFailureRequiresAuthentication() = runBlocking {
        val factory = FakeFactory()
        val delayer = FakeDelayer(TEST_POLICY.keepAliveInterval.inWholeMilliseconds)
        val m = openManager(factory, delayer)
        factory.created[0].onNoop = { throw SocketException("dead") }
        delayer.tick()
        m.await { it == MailboxState.Connected && m.currentGeneration == 2L }
        factory.created[1].onNoop = { throw MailException.AuthenticationRequired() }
        delayer.tick()
        m.await { it == MailboxState.AuthenticationRequired }
        withTimeout(5_000) { while (factory.created[1].closes.get() == 0) delay(10) }
        assertEquals(1, factory.created[1].closes.get())
        m.close()
    }

    @Test
    fun closeIsIdempotentPublishesClosedOnceAndRejectsOperations() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        val events = mutableListOf<MailboxEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { m.events.collect { events += it } }
        List(4) { async { m.close() } }.awaitAll()
        m.close()
        assertEquals(MailboxState.Closed, m.state.value)
        assertEquals(1, factory.created[0].closes.get())
        yield()
        assertEquals(1, events.count { it is MailboxEvent.StateChanged && it.state == MailboxState.Closed })
        assertFailsWith<MailException.MailboxClosed> { m.withConnection { } }
        assertFailsWith<MailException.MailboxClosed> { m.reconnect() }
        collector.cancelAndJoin()
    }

    @Test
    fun closeDuringRecoveryClosesLateConnectionAndCancelsWork() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        val gate = CompletableDeferred<Unit>()
        factory.next = { gate.await(); FakeConnection() }
        m.reportFailure(1, RecoveryReason.SOCKET)
        m.await { it is MailboxState.Reconnecting }
        m.close()
        gate.complete(Unit)
        assertEquals(MailboxState.Closed, m.state.value)
        assertEquals(1, factory.created[0].closes.get())
        assertTrue(factory.created.size <= 2)
        factory.created.drop(1).forEach { assertEquals(1, it.closes.get()) }
    }

    @Test
    fun closingOneManagerNeverAffectsAnother() = runBlocking {
        val fa = FakeFactory()
        val fb = FakeFactory()
        val a = openManager(fa, id = testId("a"))
        val b = openManager(fb, id = testId("b"))
        b.reportFailure(1, RecoveryReason.SOCKET)
        b.await { it == MailboxState.Connected && b.currentGeneration == 2L }
        a.close()
        assertEquals(MailboxState.Connected, b.state.value)
        assertEquals(1, a.currentGeneration)
        assertNotSame(a.state, b.state)
        assertSame(MailboxState.Closed, a.state.value)
        assertIs<MailboxState.Connected>(b.state.value)
        b.close()
    }
}
