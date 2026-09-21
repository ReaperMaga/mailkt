package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class MessagesTest {
    private fun run(block: suspend () -> Any?) { runBlocking { withTimeout(10_000) { block() } } }

    @Test fun `envelope filtering happens before any structure or content fetch`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 6) { if (it % 2 == 0) "contact@example.com" else "spam@example.com" }
        val query = MessageQuery(from = setOf(MailAddress("contact@example.com")))
        val seen = rig.messages.envelopes(folderSel(query = query)).toList()
        assertEquals(3, seen.size)
        assertEquals(0, rig.server.totalContentFetches())
        val full = rig.messages.messages(folderSel(query = query)).toList()
        assertEquals(3, full.size)
        assertEquals(3, rig.server.rawFetches.get(), "only envelope-matching messages are downloaded")
        rig.close()
    }

    @Test fun `sparse pages may be empty but carry a continuation and continuity holds`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 7) { if (it == 6) "contact@example.com" else "spam@example.com" }
        val query = MessageQuery(from = setOf(MailAddress("contact@example.com")))
        var cp: ScanCheckpoint? = null
        val found = mutableListOf<MessageEnvelope>()
        var emptyPages = 0
        do {
            val page = rig.messages.page(folderSel(query = query, cp = cp), limit = 3)
            if (page.envelopes.isEmpty() && page.next != null) emptyPages++
            found += page.envelopes
            cp = page.next
        } while (cp != null)
        assertEquals(1, found.size)
        assertTrue(emptyPages >= 1)
        assertEquals(0, rig.server.totalContentFetches())
        rig.close()
    }

    @Test fun `resumed scan keeps its frozen snapshot and ignores later arrivals`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 4)
        val first = rig.messages.page(folderSel(), limit = 2)
        rig.server.seed("INBOX", 3)
        val second = rig.messages.page(folderSel(cp = first.next), limit = 10)
        assertEquals(listOf(1L, 2L), first.envelopes.map { it.location.uid })
        assertEquals(listOf(3L, 4L), second.envelopes.map { it.location.uid })
        assertNull(second.next)
        rig.close()
    }

    @Test fun `newest first scans descending`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 5)
        val uids = rig.messages.envelopes(folderSel(newestFirst = true)).toList().map { it.location.uid }
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), uids)
        rig.close()
    }

    @Test fun `uidvalidity change between pages is an integrity failure`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 4)
        val first = rig.messages.page(folderSel(), limit = 2)
        rig.server.folder("INBOX").uidValidity = 99
        val e = assertFailsWith<MailException.IntegrityViolation> { rig.messages.page(folderSel(cp = first.next), limit = 2) }
        assertEquals(IntegrityKind.UID_VALIDITY_CHANGED, e.kind)
        rig.close()
    }

    @Test fun `checkpoint of another mailbox is rejected`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 3)
        val cp = ScanCheckpoint("other:key", "INBOX", 1, 1, 3)
        assertFailsWith<MailException.InvalidCheckpoint> { rig.messages.page(folderSel(cp = cp)) }
        assertFailsWith<MailException.InvalidCheckpoint> { rig.messages.page(folderSel(cp = ScanCheckpoint(rig.rt.key, "INBOX", 1, 1, 3, formatVersion = 7))) }
        rig.close()
    }

    @Test fun `expunge between selection and download is a typed unavailable failure`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 2)
        val envelope = rig.messages.envelopes(folderSel()).toList().first()
        rig.server.expunge("INBOX", envelope.location.uid)
        assertFailsWith<MailException.MessageUnavailable> { rig.messages.get(envelope.location) }
        assertFailsWith<MailException.MessageUnavailable> { rig.messages.structure(envelope.location) }
        rig.close()
    }

    @Test fun `stale uidvalidity location is never silently substituted`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 1)
        val loc = rig.messages.envelope(MessageLocation(rig.server.id, FolderPath("INBOX"), 1, 1)).location
        rig.server.folder("INBOX").uidValidity = 5
        val e = assertFailsWith<MailException.IntegrityViolation> { rig.messages.get(loc) }
        assertEquals(IntegrityKind.UID_VALIDITY_CHANGED, e.kind)
        rig.close()
    }

    @Test fun `location of another mailbox is rejected`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 1)
        val foreign = MessageLocation(MailboxId("test", "other@example.com"), FolderPath("INBOX"), 1, 1)
        assertFailsWith<MailException.MessageUnavailable> { rig.messages.envelope(foreign) }
        rig.close()
    }

    @Test fun `attachment filtering uses structure and downloads only surviving PDFs`() = run {
        val rig = Rig.open()
        rig.server.add("INBOX", FakeMsg("<inv@example.com>", parts = listOf(
            FakePart("1", "text/plain", null, "hi".toByteArray()),
            FakePart("2", "application/pdf", "invoice.pdf", ByteArray(50)),
            FakePart("3", "image/png", "logo.png", ByteArray(500)),
        )))
        val envelope = rig.messages.envelopes(folderSel()).toList().single()
        val structure = rig.messages.structure(envelope.location)
        val pdfs = structure.attachments.filter { it.isPdf && it.fileName == "invoice.pdf" }
        pdfs.forEach { rig.messages.download(it.ref, maxBytes = 1024) }
        assertEquals(listOf("2"), rig.server.partSections.toList(), "rejected parts are never downloaded")
        assertEquals(0, rig.server.rawFetches.get())
        rig.close()
    }

    @Test fun `part downloads honor the byte limit and detect changed parts`() = run {
        val rig = Rig.open()
        rig.server.add("INBOX", FakeMsg("<a@example.com>", parts = listOf(FakePart("1", "application/pdf", "a.pdf", ByteArray(100)))))
        val loc = rig.messages.envelopes(folderSel()).toList().single().location
        assertFailsWith<MailException.LimitExceeded> { rig.messages.download(MessagePartRef(loc, "1"), maxBytes = 10) }
        assertFailsWith<MailException.IntegrityViolation> { rig.messages.download(MessagePartRef(loc, "9"), maxBytes = 1000) }
        assertFailsWith<IllegalArgumentException> { rig.messages.download(MessagePartRef(loc, "1"), maxBytes = 0) }
        rig.close()
    }

    @Test fun `advertised size over limit fails without downloading`() = run {
        val rig = Rig.open()
        rig.server.add("INBOX", FakeMsg("<big@example.com>", size = 10_000))
        val loc = rig.messages.envelopes(folderSel()).toList().single().location
        assertFailsWith<MailException.LimitExceeded> { rig.messages.get(loc, maxBytes = 100) }
        assertEquals(0, rig.server.rawFetches.get())
        rig.close()
    }

    @Test fun `interrupted read retries on the replacement connection`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 3)
        rig.server.failNext = { java.net.SocketException("reset") }
        val uids = rig.messages.envelopes(folderSel()).toList().map { it.location.uid }
        assertEquals(listOf(1L, 2L, 3L), uids)
        assertEquals(2L, rig.manager.currentGeneration)
        rig.close()
    }

    @Test fun `consumer code is not retried when a read succeeded`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 2)
        var calls = 0
        assertFailsWith<IllegalStateException> {
            rig.messages.envelopes(folderSel()).collect { calls++; error("consumer failure") }
        }
        assertEquals(1, calls)
        rig.close()
    }
}
