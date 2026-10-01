package dev.reapermaga.mailkt.internal.connection

import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** Injected delay so tests never wait in real time. */
internal fun interface Delayer {
    suspend fun delay(millis: Long)

    companion object {
        val System = Delayer { kotlinx.coroutines.delay(it.milliseconds) }
    }
}

/** Injected jitter source returning values in `[0,1)`. */
internal fun interface RandomSource {
    fun nextDouble(): Double

    companion object {
        val Default = RandomSource { Random.nextDouble() }
    }
}
