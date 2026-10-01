package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.internal.connection.testId
import dev.reapermaga.mailkt.internal.transport.SubmissionOutcome
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class OutboxTest {
    private val me = MailAddress("a@example.com")
    private fun run(block: suspend () -> Any?) { runBlocking { withTimeout(10_000) { block() } } }
    private fun p(a: String) = MailParticipant(MailAddress(a))
    private fun Rig.draft(): Draft = outbox.newDraft().copy(to = listOf(p("you@example.com")), subject = "Hi", text = "Hello")

    @Test fun `accepted send returns explicit status and preserves the Message-ID`() = run {
        val rig = Rig.open()
        val d = rig.draft()
        val r = rig.outbox.send(d, saveToSent = false)
        assertIs<SendResult.Accepted>(r)
        assertEquals(d.messageId, r.messageId)
        assertEquals(SendStatus.ACCEPTED, r.status)
        rig.close()
    }

    @Test fun `rejection is FAILED and never retried`() = run {
        val smtp = FakeSmtp { SubmissionOutcome.Rejected(MailException.Unexpected()) }
        val rig = Rig.open(smtp = smtp)
        assertEquals(SendStatus.FAILED, rig.outbox.send(rig.draft()).status)
        assertEquals(1, smtp.submissions.get())
        rig.close()
    }

    @Test fun `uncertain outcome is UNKNOWN, triggers recovery but is not resent`() = run {
        val smtp = FakeSmtp { SubmissionOutcome.Uncertain(RecoveryReason.SOCKET) }
        val rig = Rig.open(smtp = smtp)
        val r = rig.outbox.send(rig.draft())
        assertEquals(SendStatus.UNKNOWN, r.status)
        assertEquals(RecoveryReason.SOCKET, (r as SendResult.Unknown).reason)
        rig.manager.await { it is MailboxState.Connected && rig.manager.currentGeneration >= 2 }
        assertEquals(1, smtp.submissions.get(), "reconnecting never re-sends")
        rig.close()
    }

    @Test fun `throwing transport after DATA is reported as UNKNOWN`() = run {
        val smtp = FakeSmtp { throw java.net.SocketException("reset") }
        val rig = Rig.open(smtp = smtp)
        assertEquals(SendStatus.UNKNOWN, rig.outbox.send(rig.draft()).status)
        assertEquals(1, smtp.submissions.get())
        rig.close()
    }

    @Test fun `no connection before submission is FAILED not UNKNOWN`() = run {
        val smtp = FakeSmtp()
        val rig = Rig.open(smtp = smtp)
        rig.close()
        val r = rig.outbox.send(rig.draft())
        assertEquals(SendStatus.FAILED, r.status)
        assertEquals(0, smtp.submissions.get())
    }

    @Test fun `sent copy is appended for providers that do not save one and failures do not change the result`() = run {
        val server = FakeServer(MailboxId("custom", "a@example.com"))
        server.folder("Sent")
        val rig = Rig.open(server)
        val r = rig.outbox.send(rig.draft())
        assertIs<SendResult.Accepted>(r)
        assertNotNull(r.sentCopy)
        assertEquals(1, server.folder("Sent").messages.size)

        val broken = FakeServer(MailboxId("custom", "a@example.com"))
        val rig2 = Rig.open(broken) // no Sent folder at all
        val r2 = rig2.outbox.send(rig2.draft())
        assertIs<SendResult.Accepted>(r2)
        assertNull(r2.sentCopy)
        assertEquals(1, rig2.smtp.submissions.get())
        rig.close(); rig2.close()
    }

    @Test fun `providers that store Sent copies themselves are not appended`() = run {
        val server = FakeServer(MailboxId("gmail", "a@example.com"))
        server.folder("Sent")
        val rig = Rig.open(server)
        val r = rig.outbox.send(rig.draft())
        assertNull((r as SendResult.Accepted).sentCopy)
        assertEquals(0, server.folder("Sent").messages.size)
        rig.close()
    }

    @Test fun `sender must match the mailbox and recipients are required`() = run {
        val rig = Rig.open()
        assertFailsWith<IllegalArgumentException> { rig.outbox.send(rig.draft().copy(from = p("evil@example.com"))) }
        assertFailsWith<IllegalArgumentException> { rig.outbox.send(rig.outbox.newDraft()) }
        rig.close()
    }

    @Test fun `reply respects Reply-To, excludes self and never exposes Bcc`() {
        val original = MessageEnvelope(
            MessageLocation(testId(), FolderPath("INBOX"), 1, 1), "<p@x>", null, listOf("<earlier@x>"),
            from = listOf(p("contact@example.com")), replyTo = listOf(p("reply@example.com")),
            to = listOf(p("a@example.com"), p("other@example.com")), cc = listOf(p("copy@example.com"), p("A@example.com")),
            subject = "Invoice",
        )
        val all = ReplyBuilder.reply(me, original, replyAll = true, text = "Thanks")
        assertEquals("Re: Invoice", all.subject)
        assertEquals("<p@x>", all.inReplyTo)
        assertEquals(listOf("<earlier@x>", "<p@x>"), all.references)
        assertEquals(listOf("reply@example.com", "other@example.com"), all.to.map { it.address.value })
        assertEquals(listOf("copy@example.com"), all.cc.map { it.address.value })
        assertTrue(all.bcc.isEmpty())
        assertNotNull(all.messageId)
        assertEquals(listOf("reply@example.com"), ReplyBuilder.reply(me, original, false, null).to.map { it.address.value })
    }

    @Test fun `reply to own message goes to the original recipients`() {
        val sent = MessageEnvelope(
            MessageLocation(testId(), FolderPath("Sent"), 1, 1), "<s@x>", null, from = listOf(p("a@example.com")),
            to = listOf(p("contact@example.com")), subject = "Re: Question",
        )
        val r = ReplyBuilder.reply(me, sent, false, "Follow-up")
        assertEquals(listOf("contact@example.com"), r.to.map { it.address.value })
        assertEquals("Re: Question", r.subject)
    }
}

private suspend fun dev.reapermaga.mailkt.internal.connection.ConnectionManager.await(p: (MailboxState) -> Boolean) =
    withTimeout(5_000.milliseconds) { state.first(p) }
