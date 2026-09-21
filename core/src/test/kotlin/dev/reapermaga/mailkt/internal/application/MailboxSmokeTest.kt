package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.client.*
import dev.reapermaga.mailkt.internal.transport.TransportConnection
import dev.reapermaga.mailkt.internal.transport.TransportFactory
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Public API smoke test: reading, watching, conversations and sending through the [Mailbox] handle only. */
class MailboxSmokeTest {
    private fun factory(server: FakeServer, smtp: FakeSmtp) = object : TransportFactory {
        override suspend fun connect(): TransportConnection = TestConnection(FakeSession(server), smtp)
    }

    private suspend fun open(server: FakeServer, options: MailboxOptions = MailboxOptions(), smtp: FakeSmtp = FakeSmtp()) =
        ManagedMailbox.open(server.id, MailAddress("a@example.com"), factory(server, smtp), TestCodec, options, hasSmtp = true)

    @Test fun `mailbox exposes the four capabilities and a closed lifecycle`() = runBlocking<Unit> {
        withTimeout(10_000) {
            val server = FakeServer()
            server.seed("INBOX", 3)
            server.folder("Sent")
            val mailbox = open(server)
            assertEquals(MailboxState.Connected, mailbox.state.value)
            assertEquals(2, mailbox.folders.list().size)

            val page = mailbox.messages.page(folderSel(), limit = 2)
            assertEquals(2, page.envelopes.size)
            assertEquals(3, mailbox.messages.messages(folderSel()).toList().size)
            val watched = mailbox.messages.watchEnvelopes(FolderPath("INBOX"), from = WatchCheckpoint(mailbox.id.storageKey, "INBOX", 1, 1)).take(2).toList()
            assertEquals(listOf(2L, 3L), watched.map { it.envelope.location.uid })
            assertTrue(mailbox.conversations.synchronize(FolderPath("INBOX")).changed.isNotEmpty())

            val outbox = assertNotNull(mailbox.outbox)
            val draft = outbox.newDraft().copy(to = listOf(MailParticipant(MailAddress("you@example.com"))), subject = "Hi", text = "Hello")
            assertEquals(SendStatus.ACCEPTED, outbox.send(draft, saveToSent = false).status)

            mailbox.close()
            mailbox.close()
            assertEquals(MailboxState.Closed, mailbox.state.value)
            assertFailsWith<MailException.MailboxClosed> { mailbox.folders.list() }
        }
    }

    @Test fun `use closes the mailbox even when the block fails`() = runBlocking<Unit> {
        val server = FakeServer()
        val mailbox = open(server)
        runCatching { mailbox.use { error("boom") } }
        assertEquals(MailboxState.Closed, mailbox.state.value)
    }

    @Test fun `outbox is absent when disabled and registry rejects duplicate ids`() = runBlocking<Unit> {
        val server = FakeServer()
        val registry = MailboxRegistry()
        val first = open(server, MailboxOptions(outboxEnabled = false, registry = registry))
        assertNull(first.outbox)
        assertFailsWith<MailException.DuplicateMailbox> { open(FakeServer(server.id), MailboxOptions(registry = registry)) }
        assertTrue(registry.close().isClean)
        assertEquals(MailboxState.Closed, first.state.value)
    }

    @Test fun `two mailboxes stay isolated when one closes`() = runBlocking<Unit> {
        val a = FakeServer(MailboxId("test", "a@example.com"))
        val b = FakeServer(MailboxId("test", "b@example.com"))
        a.seed("INBOX", 1); b.seed("INBOX", 2)
        val ma = open(a)
        val mb = ManagedMailbox.open(b.id, MailAddress("b@example.com"), factory(b, FakeSmtp()), TestCodec, MailboxOptions(), true)
        ma.close()
        assertEquals(MailboxState.Connected, mb.state.value)
        assertEquals(2, mb.messages.envelopes(folderSel()).toList().size)
        mb.close()
    }
}
