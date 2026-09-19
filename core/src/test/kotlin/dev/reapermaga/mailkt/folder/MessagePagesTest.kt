package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.message.composeMessage
import dev.reapermaga.mailkt.session.*
import jakarta.mail.*
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.util.Properties
import kotlin.test.*

class MessagePagesTest {
    internal class Fixture : MailSession {
        val jakarta = Session.getInstance(Properties())
        val rows = sortedMapOf<Long, MimeMessage>()
        var validity = 10L
        var nextUid = 7L
        var opened = 0
        val windows = mutableListOf<LongRange>()
        val appended = mutableListOf<Message>()
        var envelopeFetches = 0
        var fullMessageFetches = 0
        var fullMessageFetchDelayMillis = 0L
        var failFullMessageFetchAt: Int? = null
        val store = object : IMAPStore(jakarta, null) {
            override fun isConnected() = true
            override fun getFolder(name: String): Folder = object : IMAPFolder(name, '/', this, false) {
                var live = false
                override fun open(mode: Int) { live = true; opened++ }
                override fun isOpen() = live
                override fun close(expunge: Boolean) { live = false; opened-- }
                override fun getUIDValidity() = validity
                override fun getUIDNext() = nextUid
                override fun getUIDNotSticky() = false
                override fun getMessagesByUID(start: Long, end: Long): Array<Message> {
                    windows += start..end
                    return rows.filterKeys { it in start..end }.values.toTypedArray()
                }
                override fun getMessagesByUID(uids: LongArray): Array<Message> {
                    if (uids.isNotEmpty()) windows += uids.min()..uids.max()
                    return uids.asSequence().mapNotNull { rows[it] }.toList().toTypedArray()
                }
                override fun getUID(message: Message) = rows.entries.first { it.value === message }.key
                override fun fetch(messages: Array<out Message>, profile: FetchProfile) {
                    if (profile.contains(IMAPFolder.FetchProfileItem.MESSAGE)) {
                        fullMessageFetches++
                        if (fullMessageFetchDelayMillis > 0) Thread.sleep(fullMessageFetchDelayMillis)
                        if (failFullMessageFetchAt == fullMessageFetches) error("simulated partial batch failure")
                    } else envelopeFetches++
                }
                override fun appendMessages(messages: Array<out Message>) { appended.addAll(messages) }
            }
        }
        override val id = "account"
        override val isConnected = true
        override val currentConnection = MailConnection(jakarta, store)
        override suspend fun connect(credentials: MailCredentials) = currentConnection
        override suspend fun disconnect() {}
        fun add(uid: Long, from: String = "contact@example.com") {
            rows[uid] = composeMessage(from, listOf("me@example.com"), "Subject", "body-$uid")
        }
    }

    @Test fun `bounded sparse history progresses freezes boundary and detaches ordinary email`() = runBlocking {
        val fixture = Fixture().apply { add(1); add(3, "other@example.com"); add(5) }
        fixture.rows.getValue(5).setFlag(Flags.Flag.SEEN, true)
        val filter = MessageFilter(setOf("contact@example.com"))
        val first = readMessagePage(fixture, "INBOX", filter, uidWindowSize = 2)
        assertEquals(listOf(5L), first.messages.map { it.uid })
        assertEquals(6L, first.snapshotUpperUid)
        fixture.add(7)
        fixture.nextUid = 8
        val second = readMessagePage(fixture, "INBOX", filter, first.nextCursor, uidWindowSize = 2)
        assertTrue(second.messages.isEmpty())
        assertNotNull(second.nextCursor)
        val third = readMessagePage(fixture, "INBOX", filter, second.nextCursor, uidWindowSize = 2)
        assertEquals(listOf(1L), third.messages.map { it.uid })
        assertNull(third.nextCursor)
        assertEquals(6L, third.snapshotUpperUid)
        assertEquals(listOf(5L..6L, 3L..4L, 1L..2L), fixture.windows)
        assertEquals(0, fixture.opened)
        assertNull(first.messages.single().message.folder)
        assertEquals("body-5", first.messages.single().message.content)
        assertTrue(first.messages.single().message.isSet(Flags.Flag.SEEN))
    }

    @Test fun `incremental UIDs ordered oldest first and UIDVALIDITY changes fail`() = runBlocking {
        val fixture = Fixture().apply { add(2); add(4); add(6) }
        val first = readMessagePage(fixture, "Sent", afterUid = 2, expectedUidValidity = 10, uidWindowSize = 2)
        assertEquals(listOf(4L), first.messages.map { it.uid })
        val second = readMessagePage(fixture, "Sent", cursor = first.nextCursor, uidWindowSize = 2)
        assertEquals(listOf(6L), second.messages.map { it.uid })
        assertNull(second.nextCursor)
        fixture.validity = 11
        assertFailsWith<IllegalStateException> { readMessagePage(fixture, "Sent", cursor = first.nextCursor) }
        assertFailsWith<IllegalStateException> { readMessagePage(fixture, "Sent", afterUid = 6, expectedUidValidity = 10) }
        assertEquals(0, fixture.opened)
    }

    @Test fun `size limits and foreign cursors fail without leaking folders`() = runBlocking<Unit> {
        val fixture = Fixture().apply { add(6) }
        assertFailsWith<IllegalStateException> { readMessagePage(fixture, "INBOX", maxMessageBytes = 1) }
        assertFailsWith<IllegalStateException> { readMessagePage(fixture, "INBOX", maxPageBytes = 1) }
        assertEquals(0, fixture.opened)
        val cursor = MessagePageCursor("another account", "INBOX", 10, 1, 6, true)
        assertFailsWith<IllegalArgumentException> { readMessagePage(fixture, "INBOX", cursor = cursor) }
        assertFailsWith<IllegalArgumentException> { readMessagePage(fixture, "INBOX", afterUid = 6) }
    }

    @Test fun `optional sent append preserves ID marks copy seen and does not mutate draft`() = runBlocking {
        val fixture = Fixture()
        val draft = composeMessage("me@example.com", listOf("contact@example.com"), "Hello", "Hello")
        appendSentMessage(fixture, "Sent", draft)
        val copy = fixture.appended.single() as MimeMessage
        assertEquals(draft.messageID, copy.messageID)
        assertTrue(copy.isSet(Flags.Flag.SEEN))
        assertFalse(draft.isSet(Flags.Flag.SEEN))
        assertEquals(0, fixture.opened)
    }
}
