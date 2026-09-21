package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.internal.connection.testId
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.*

class ConversationsTest {
    private val inbox = FolderPath("INBOX")
    private fun run(block: suspend () -> Any?) { runBlocking { withTimeout(10_000) { block() } } }
    private fun at(day: Int) = Instant.parse("2026-01-%02dT00:00:00Z".format(day))

    private fun env(uid: Long, id: String?, replyTo: String? = null, refs: List<String> = emptyList(), folder: String = "INBOX", day: Int = uid.toInt()) =
        MessageEnvelope(
            MessageLocation(testId(), FolderPath(folder), 1, uid), id, replyTo, refs,
            from = listOf(MailParticipant(MailAddress("contact@example.com"))), to = listOf(MailParticipant(MailAddress("a@example.com"))),
            subject = "Invoice", sentAt = at(day), receivedAt = at(day),
        )

    @Test fun `groups by explicit relationships only, never by subject`() {
        val list = ConversationAssembler.assemble(listOf(
            env(1, "<a>"), env(2, "<b>", "<a>"), env(3, "<c>", refs = listOf("<a>", "<b>")), env(4, "<unrelated>"), env(5, null),
        ))
        assertEquals(3, list.size)
        assertEquals(listOf(3, 1, 1), list.map { it.messages.size }.sortedDescending())
    }

    @Test fun `ancestors missing from the pool still link replies`() {
        val list = ConversationAssembler.assemble(listOf(env(2, "<b>", "<a>"), env(3, "<c>", "<a>")))
        assertEquals(1, list.size)
    }

    @Test fun `copies with the same Message-ID merge across folders`() {
        val list = ConversationAssembler.assemble(listOf(env(1, "<a>"), env(9, "<a>", folder = "All", day = 1), env(2, "<b>", "<a>")))
        assertEquals(1, list.size)
        assertEquals(2, list.single().messages.size)
    }

    @Test fun `different content under one Message-ID does not merge`() {
        val other = env(9, "<a>", folder = "All", day = 1).copy(subject = "Different")
        assertEquals(2, ConversationAssembler.assemble(listOf(env(1, "<a>"), other)).single().messages.size)
    }

    @Test fun `conversation metadata is derived from envelopes`() {
        val c = ConversationAssembler.assemble(listOf(env(1, "<a>"), env(2, "<b>", "<a>"))).single()
        assertEquals("Invoice", c.subject)
        assertEquals(at(2), c.lastActivity)
        assertEquals(setOf("contact@example.com", "a@example.com"), c.participants.map { it.value }.toSet())
    }

    @Test fun `of() reads related envelopes only and never content`() = run {
        val rig = Rig.open()
        rig.server.add("INBOX", FakeMsg("<a@x>"))
        rig.server.add("INBOX", FakeMsg("<b@x>", inReplyTo = "<a@x>"))
        rig.server.add("INBOX", FakeMsg("<z@x>"))
        val loc = MessageLocation(rig.server.id, inbox, 1, 2)
        val c = rig.conversations.of(loc)
        assertEquals(2, c.messages.size)
        assertFalse(c.truncated)
        assertEquals(0, rig.server.totalContentFetches())
        rig.close()
    }

    @Test fun `of() reports truncation at the message limit`() = run {
        val rig = Rig.open()
        rig.server.add("INBOX", FakeMsg("<a@x>"))
        repeat(4) { rig.server.add("INBOX", FakeMsg("<r$it@x>", inReplyTo = "<a@x>")) }
        val c = rig.conversations.of(MessageLocation(rig.server.id, inbox, 1, 1), maxMessages = 3)
        assertTrue(c.truncated)
        assertTrue(c.messages.size <= 4)
        rig.close()
    }

    @Test fun `synchronize is incremental and checkpoints continue`() = run {
        val rig = Rig.open()
        rig.server.add("INBOX", FakeMsg("<a@x>"))
        val first = rig.conversations.synchronize(inbox)
        assertEquals(1, first.changed.size)
        assertEquals(1L, first.next.lastUid)
        assertFalse(first.reset)

        val idle = rig.conversations.synchronize(inbox, first.next)
        assertTrue(idle.changed.isEmpty())
        assertEquals(1L, idle.next.lastUid)

        rig.server.add("INBOX", FakeMsg("<b@x>", inReplyTo = "<a@x>"))
        val second = rig.conversations.synchronize(inbox, idle.next)
        assertEquals(1, second.changed.size)
        assertEquals(2, second.changed.single().messages.size, "older ancestors are linked for context")
        assertEquals(2L, second.next.lastUid)
        assertEquals(0, rig.server.totalContentFetches())
        rig.close()
    }

    @Test fun `synchronize truncates to maxMessages and resumes oldest first`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 5)
        val a = rig.conversations.synchronize(inbox, ConversationCheckpoint(rig.rt.key, "INBOX", 1, 0), maxMessages = 2)
        assertEquals(2L, a.next.lastUid)
        assertTrue(a.changed.all { it.truncated })
        val b = rig.conversations.synchronize(inbox, a.next, maxMessages = 10)
        assertEquals(5L, b.next.lastUid)
        assertTrue(b.changed.none { it.truncated })
        rig.close()
    }

    @Test fun `uidvalidity change resets the cursor`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 2)
        val cp = rig.conversations.synchronize(inbox).next
        rig.server.folder("INBOX").uidValidity = 77
        val again = rig.conversations.synchronize(inbox, cp)
        assertTrue(again.reset)
        assertEquals(77L, again.next.uidValidity)
        assertEquals(2, again.changed.sumOf { it.messages.size })
        rig.close()
    }

    @Test fun `foreign conversation checkpoint is rejected`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 1)
        assertFailsWith<MailException.InvalidCheckpoint> { rig.conversations.synchronize(inbox, ConversationCheckpoint("x", "INBOX", 1, 0)) }
        rig.close()
    }
}
