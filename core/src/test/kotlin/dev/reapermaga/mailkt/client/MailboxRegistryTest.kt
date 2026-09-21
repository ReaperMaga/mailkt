package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MailboxEvent
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.MailboxState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private inline fun <reified T : Any> unsupported(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
        throw UnsupportedOperationException()
    } as T

private class FakeMailbox(
    override val id: MailboxId,
    private val closeGate: CompletableDeferred<Unit>? = null,
    private val closeFailure: Throwable? = null,
) : Mailbox {
    val closes = AtomicInteger()
    override val email = MailAddress("x@example.com")
    override val state: StateFlow<MailboxState> = MutableStateFlow(MailboxState.Connected)
    override val events: Flow<MailboxEvent> = emptyFlow()
    override val folders: Folders = unsupported()
    override val messages: Messages = unsupported()
    override val conversations: Conversations = unsupported()
    override val outbox: Outbox? = null
    override suspend fun reconnect() {}
    override suspend fun close() {
        closes.incrementAndGet()
        closeGate?.await()
        closeFailure?.let { throw it }
    }
}

class MailboxRegistryTest {
    private fun id(n: String, provider: String = "gmail") = MailboxId(provider, "$n@example.com")

    @Test
    fun rejectsDuplicateIdentityButAllowsSameAddressOnAnotherProvider() {
        val registry = MailboxRegistry()
        registry.register(FakeMailbox(id("a")))
        assertFailsWith<MailException.DuplicateMailbox> { registry.register(FakeMailbox(id("a"))) }
        registry.register(FakeMailbox(id("a", "outlook")))
        assertEquals(2, registry.ids().size)
    }

    @Test
    fun unregisterFreesTheIdentity() {
        val registry = MailboxRegistry()
        val first = registry.register(FakeMailbox(id("a")))
        assertSame(first, registry[id("a")])
        registry.unregister(id("a"))
        registry.register(FakeMailbox(id("a")))
    }

    @Test
    fun bulkCloseIsConcurrentAndReportsIndividualFailures() = runBlocking {
        val registry = MailboxRegistry()
        val gate = CompletableDeferred<Unit>()
        val slow = FakeMailbox(id("slow"), closeGate = gate)
        val broken = FakeMailbox(id("broken"), closeFailure = IllegalStateException("secret text"))
        val ok = FakeMailbox(id("ok"))
        listOf(slow, broken, ok).forEach(registry::register)
        val report = async { registry.close() }
        // ok and broken finish although slow is still blocked: closes run concurrently under supervision.
        withTimeout(5_000) { while (ok.closes.get() == 0 || broken.closes.get() == 0 || slow.closes.get() == 0) yield() }
        gate.complete(Unit)
        val result = report.await()
        assertEquals(setOf(broken.id), result.failures.keys)
        assertTrue(!result.isClean)
        assertTrue(registry.ids().isEmpty())
    }
}
