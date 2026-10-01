package dev.reapermaga.mailkt.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionPolicyTest {
    private val policy = ConnectionPolicy()

    @Test
    fun defaultsMatchContract() {
        assertEquals(30.seconds, policy.keepAliveInterval)
        assertEquals(30.seconds, policy.attemptTimeout)
        assertEquals(5, policy.maxReconnectAttempts)
        assertEquals(1.seconds, policy.initialBackoff)
        assertEquals(30.seconds, policy.maxBackoff)
        assertEquals(0.2, policy.jitterFraction)
    }

    @Test
    fun backoffDoublesAndCapsAtThirtySeconds() {
        val secs = (1..8).map { policy.backoff(it, 0.5).inWholeMilliseconds }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L), secs)
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        assertEquals(800.milliseconds, policy.backoff(1, 0.0))
        assertEquals(1200.milliseconds, policy.backoff(1, 1.0))
        assertEquals(24.seconds, policy.backoff(9, 0.0))
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { ConnectionPolicy(maxReconnectAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { ConnectionPolicy(jitterFraction = 2.0) }
    }
}
