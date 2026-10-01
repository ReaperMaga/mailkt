package dev.reapermaga.mailkt.internal.connection

import dev.reapermaga.mailkt.client.ConnectionPolicy
import dev.reapermaga.mailkt.internal.transport.ImapFolderPort
import dev.reapermaga.mailkt.internal.transport.ImapSessionPort
import dev.reapermaga.mailkt.internal.transport.SmtpPort
import dev.reapermaga.mailkt.internal.transport.TransportConnection
import dev.reapermaga.mailkt.internal.transport.TransportFactory
import dev.reapermaga.mailkt.model.FolderInfo
import dev.reapermaga.mailkt.model.FolderPath
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.MailboxState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal class FakeConnection(var onNoop: suspend () -> Unit = {}) : TransportConnection {
    val closes = AtomicInteger()
    override val smtp: SmtpPort? = null
    override val imap = object : ImapSessionPort {
        override suspend fun listFolders(): List<FolderInfo> = emptyList()
        override suspend fun <T> withFolder(
            path: FolderPath,
            readOnly: Boolean,
            block: suspend (ImapFolderPort) -> T,
        ): T = error("unused")
        override suspend fun noop() = onNoop()
        override suspend fun isConnected() = true
        override suspend fun close() {}
    }

    override suspend fun close() {
        closes.incrementAndGet()
    }
}

internal class FakeFactory : TransportFactory {
    val attempts = AtomicInteger()
    val created = CopyOnWriteArrayList<FakeConnection>()

    @Volatile
    var next: suspend () -> FakeConnection = { FakeConnection() }

    override suspend fun connect(): TransportConnection {
        attempts.incrementAndGet()
        return next().also { created += it }
    }
}

/** Backoff sleeps are recorded and return immediately; keep-alive sleeps wait for an explicit [tick]. */
internal class FakeDelayer(private val keepAliveMillis: Long) : Delayer {
    val backoff = CopyOnWriteArrayList<Long>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    fun tick() {
        ticks.trySend(Unit)
    }

    override suspend fun delay(millis: Long) {
        if (millis == keepAliveMillis) ticks.receive() else backoff += millis
    }
}

internal val TEST_POLICY = ConnectionPolicy(keepAliveInterval = 10.minutes, attemptTimeout = 2_000.milliseconds)
internal val MID_RANDOM = RandomSource { 0.5 }
internal fun testId(name: String = "a") = MailboxId("test", "$name@example.com")

internal suspend fun ConnectionManager.await(predicate: (MailboxState) -> Boolean): MailboxState =
    withTimeout(5_000.milliseconds) { state.first(predicate) }

internal suspend fun openManager(
    factory: FakeFactory,
    delayer: FakeDelayer = FakeDelayer(TEST_POLICY.keepAliveInterval.inWholeMilliseconds),
    policy: ConnectionPolicy = TEST_POLICY,
    id: MailboxId = testId(),
) = ConnectionManager.open(id, factory, policy, delayer = delayer, random = MID_RANDOM)
