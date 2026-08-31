package dev.reapermaga.mailkt.session

import jakarta.mail.Session
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

    private fun credentials() = MailCredentials.oauth2("user@example.com", "token")

    private class FakeMailSession : MailSession {
        private val jakartaSession = Session.getInstance(Properties())
        private val store = jakartaSession.getStore("imap") as IMAPStore
        private val connection = MailConnection(jakartaSession, store)

        override val id = "fake-session"
        override val currentConnection: MailConnection = connection

        @Volatile var connected = false
        var connectCalls = 0

        override val isConnected: Boolean
            get() = connected

        override suspend fun connect(credentials: MailCredentials): MailConnection {
            connectCalls++
            connected = true
            return connection
        }

        override suspend fun disconnect() {
            connected = false
        }
    }
}
