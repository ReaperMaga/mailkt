package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.client.WatchedEnvelope
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class WatchingTest {
    private val inbox = FolderPath("INBOX")
    private fun run(block: suspend kotlinx.coroutines.CoroutineScope.() -> Any?) { runBlocking { withTimeout(10_000) { block() } } }

    @Test fun `catches up from a checkpoint and suppresses duplicates`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 3)
        val cp = WatchCheckpoint(rig.rt.key, "INBOX", 1, 1)
        val got = rig.messages.watchEnvelopes(inbox, from = cp).take(2).toList()
        assertEquals(listOf(2L, 3L), got.map { it.envelope.location.uid })
        assertEquals(3L, got.last().next.lastUid)
        rig.close()
    }

    @Test fun `first collection starts from now and delivers arrivals`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 2)
        val flow = rig.messages.watchEnvelopes(inbox).take(1)
        val result = async(Dispatchers.Default) { flow.toList() }
        delay(50)
        rig.server.add("INBOX", FakeMsg("<new@example.com>"))
        assertEquals(3L, result.await().single().envelope.location.uid)
        rig.close()
    }

    @Test fun `envelope filter runs before any content fetch`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 4) { if (it == 3) "contact@example.com" else "spam@example.com" }
        val q = MessageQuery(from = setOf(MailAddress("contact@example.com")))
        val got = rig.messages.watchEnvelopes(inbox, q, WatchCheckpoint(rig.rt.key, "INBOX", 1, 0)).take(1).toList()
        assertEquals(4L, got.single().envelope.location.uid)
        assertEquals(0, rig.server.totalContentFetches())
        val msgs = rig.messages.watch(inbox, q, WatchCheckpoint(rig.rt.key, "INBOX", 1, 0)).take(1).toList()
        assertEquals(1, rig.server.rawFetches.get())
        assertEquals(4L, msgs.single().message.location.uid)
        rig.close()
    }

    @Test fun `uidvalidity change rescans preferring duplicates over loss`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 2)
        rig.server.folder("INBOX").uidValidity = 42
        val got = rig.messages.watchEnvelopes(inbox, from = WatchCheckpoint(rig.rt.key, "INBOX", 1, 2)).take(2).toList()
        assertEquals(listOf(1L, 2L), got.map { it.envelope.location.uid })
        assertEquals(42L, got.first().next.uidValidity)
        rig.close()
    }

    @Test fun `watcher survives reconnect without losing or duplicating`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 1)
        rig.server.failNext = { java.net.SocketException("reset") }
        val got: List<WatchedEnvelope> = rig.messages.watchEnvelopes(inbox, from = WatchCheckpoint(rig.rt.key, "INBOX", 1, 0)).take(1).toList()
        assertEquals(1L, got.single().envelope.location.uid)
        assertTrue(rig.manager.currentGeneration >= 2)
        rig.close()
    }

    @Test fun `foreign checkpoint is rejected`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 1)
        assertFailsWith<MailException.InvalidCheckpoint> {
            rig.messages.watchEnvelopes(inbox, from = WatchCheckpoint("x", "INBOX", 1, 0)).take(1).toList()
        }
        rig.close()
    }

    @Test fun `closing the mailbox ends the watcher with a typed failure`() = run {
        val rig = Rig.open()
        rig.server.seed("INBOX", 1)
        val job = async(Dispatchers.Default) {
            runCatching { rig.messages.watchEnvelopes(inbox).toList() }.exceptionOrNull()
        }
        delay(50)
        rig.close()
        assertIs<MailException.MailboxClosed>(job.await())
    }
}
