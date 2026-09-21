package dev.reapermaga.mailkt.internal.connection

import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MailboxState
import dev.reapermaga.mailkt.model.RecoveryReason
import kotlinx.coroutines.runBlocking
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionRecoveryTest {
    @Test
    fun exhaustionBecomesRecoverableFailedWithExponentialBackoffThenExplicitReconnectResumes() = runBlocking {
        val factory = FakeFactory()
        val delayer = FakeDelayer(TEST_POLICY.keepAliveInterval.inWholeMilliseconds)
        val m = openManager(factory, delayer)
        factory.next = { throw SocketException("down") }
        m.reportFailure(1, RecoveryReason.SOCKET)
        val failed = m.await { it is MailboxState.Failed }
        assertIs<MailboxState.Failed>(failed)
        assertTrue(failed.recoverable)
        assertEquals(1 + 5, factory.attempts.get())
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delayer.backoff.toList())
        assertEquals(1, factory.created[0].closes.get())

        val settled = factory.attempts.get()
        assertFailsWith<MailException.NotConnected> { m.withConnection { } }
        assertEquals(settled, factory.attempts.get(), "idle until reconnect() or close()")

        factory.next = { FakeConnection() }
        m.reconnect()
        assertEquals(MailboxState.Connected, m.state.value)
        assertEquals(2, m.currentGeneration)
        m.close()
    }

    @Test
    fun authFailureDuringRecoveryStopsRetriesAndReconnectAfterReauthorizationResumes() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        factory.next = { throw MailException.AuthenticationRequired() }
        m.reportFailure(1, RecoveryReason.SOCKET)
        m.await { it == MailboxState.AuthenticationRequired }
        assertEquals(2, factory.attempts.get())

        assertFailsWith<MailException.AuthenticationRequired> { m.reconnect() }
        factory.next = { FakeConnection() }
        m.reconnect()
        assertEquals(MailboxState.Connected, m.state.value)
        m.close()
    }

    @Test
    fun reconnectWhileConnectedIsNoOpAndConcurrentReconnectsCoalesce() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        m.reconnect()
        assertEquals(1, factory.attempts.get())
        m.close()
    }

    @Test
    fun nonRecoverableFailureIsNotRetried() = runBlocking {
        val factory = FakeFactory()
        val m = openManager(factory)
        factory.next = { throw IllegalStateException("bug") }
        m.reportFailure(1, RecoveryReason.SOCKET)
        val failed = m.await { it is MailboxState.Failed } as MailboxState.Failed
        assertEquals(false, failed.recoverable)
        assertEquals(2, factory.attempts.get())
        assertFailsWith<MailException.Unexpected> { m.reconnect() }
        m.close()
    }

    @Test
    fun attemptTimeoutDuringRecoveryIsRetried() = runBlocking {
        val factory = FakeFactory()
        val policy = TEST_POLICY.copy(attemptTimeout = kotlin.time.Duration.parse("100ms"))
        val m = openManager(factory, policy = policy)
        var calls = 0
        factory.next = {
            if (calls++ == 0) kotlinx.coroutines.CompletableDeferred<FakeConnection>().await() else FakeConnection()
        }
        m.reportFailure(1, RecoveryReason.SOCKET)
        m.await { it == MailboxState.Connected && m.currentGeneration == 2L }
        m.close()
    }
}
