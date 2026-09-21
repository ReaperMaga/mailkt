package dev.reapermaga.mailkt.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Lifecycle behavior of one mailbox. Defaults follow the redesign contract. */
data class ConnectionPolicy(
    val keepAliveInterval: Duration = 30.seconds,
    val attemptTimeout: Duration = 30.seconds,
    val maxReconnectAttempts: Int = 5,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 30.seconds,
    val jitterFraction: Double = 0.2,
) {
    init {
        require(maxReconnectAttempts >= 1) { "maxReconnectAttempts must be >= 1" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be within 0..1" }
        require(keepAliveInterval.isPositive() && attemptTimeout.isPositive()) { "durations must be positive" }
    }

    /** Backoff before retry number [attempt] (1-based) using [random] in `[0,1)`; capped, then jittered. */
    fun backoff(attempt: Int, random: Double): Duration {
        val exp = (attempt - 1).coerceIn(0, 30)
        val base = minOf(initialBackoff * Math.pow(2.0, exp.toDouble()), maxBackoff)
        val factor = 1.0 + jitterFraction * (random * 2.0 - 1.0)
        return base * factor
    }
}
