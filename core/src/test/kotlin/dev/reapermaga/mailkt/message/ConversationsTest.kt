package dev.reapermaga.mailkt.message

import dev.reapermaga.mailkt.folder.HistoricalMessage
import dev.reapermaga.mailkt.folder.MessagePagesTest
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import java.util.Date
import kotlin.test.*

class ConversationsTest {
    private fun mail(id: String?, parent: String? = null, sender: String = "contact@example.com", body: String = "Hello", time: Long = 1): MimeMessage =
        composeMessage(sender, listOf("me@example.com"), "Same subject", body).apply {
            if (id == null) removeHeader("Message-ID") else setHeader("Message-ID", id)
            if (parent != null) setHeader("In-Reply-To", parent)
            sentDate = Date(time)
        }

    private fun located(message: MimeMessage, uid: Long, folder: String = "INBOX", account: String = "account") =
        LocatedMessage(MessageLocation(account, folder, 10, uid), HistoricalMessage(message, uid, 10, null))

    @Test fun `group replies chronological merge copies and keep subject matches separate`() {
        val parent = mail("<root@example.com>", time = 1)
        val reply = mail("<reply@example.com>", "<root@example.com>", time = 2)
        val threads = assembleConversations(listOf(located(reply, 2), located(parent, 1),
            located(MimeMessage(parent), 8, "Sent"), located(mail("<separate@example.com>", time = 3), 3)))
        assertEquals(2, threads.size)
        assertEquals(listOf("<root@example.com>", "<reply@example.com>"), threads.first().messages.map { it.message.messageID })
        assertEquals(2, threads.first().messages.first().copies.size)
    }

    @Test fun `missing IDs collisions and different accounts are never lost`() {
        val threads = assembleConversations(listOf(located(mail(null), 1), located(mail(null), 2),
            located(mail("<collision@example.com>", body = "A"), 3),
            located(mail("<collision@example.com>", body = "B"), 4),
            located(mail("<collision@example.com>", body = "A"), 5, account = "other")))
        assertEquals(5, threads.sumOf { it.messages.size })
        assertEquals(4, threads.size)
    }

    @Test fun `discovers ancestors and changed-participant replies regardless of scan order`() = runBlocking {
        val fixture = MessagePagesTest.Fixture()
        fixture.rows[1] = mail("<grandchild@example.com>", "<child@example.com>", "other@example.com")
        fixture.rows[2] = mail("<ancestor@example.com>", sender = "other@example.com")
        fixture.rows[3] = mail("<child@example.com>", "<root@example.com>", "other@example.com")
        fixture.rows[4] = mail("<root@example.com>", "<ancestor@example.com>")
        fixture.rows[5] = mail("<unrelated@example.com>", sender = "else@example.com")
        val history = readConversations(fixture, listOf("INBOX", "Sent"), setOf("contact@example.com"))
        assertEquals(1, history.threads.size)
        assertEquals(4, history.threads.single().messages.size)
        assertTrue(history.threads.single().messages.all { it.copies.size == 2 })
        assertEquals(0, fixture.opened)
        assertTrue(fixture.windows.all { it.last - it.first < 100 })
        assertTrue(history.threads.single().messages.all { it.message.folder == null })
    }

    @Test fun `truncation seed validation and limits are explicit`() = runBlocking<Unit> {
        val fixture = MessagePagesTest.Fixture()
        fixture.rows[1] = mail("<old@example.com>")
        fixture.rows[6] = mail("<new@example.com>")
        val result = readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(maxUidPositionsPerFolder = 2))
        assertTrue(result.folders.single().olderHistoryAvailable)
        assertEquals("<new@example.com>", result.threads.single().messages.single().message.messageID)
        assertFailsWith<IllegalArgumentException> { readConversations(fixture, listOf("INBOX"), emptySet()) }
        assertFailsWith<IllegalStateException> { readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(maxMatchedMessages = 1)) }
        assertFailsWith<IllegalStateException> { readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(maxTotalBytes = 1)) }
        assertEquals(0, fixture.opened)
    }

    @Test fun `older windows discover referenced ancestors and merge with latest flags`() = runBlocking {
        val fixture = MessagePagesTest.Fixture()
        fixture.rows[1] = mail("<old@example.com>", sender = "other@example.com", time = 1)
        fixture.rows[6] = mail("<new@example.com>", "<old@example.com>", time = 2)
        val recent = readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            options = ConversationReadOptions(maxUidPositionsPerFolder = 2))
        val older = readConversations(fixture, listOf("INBOX"), setOf("contact@example.com"),
            threadMessageIds = recent.threads.single().relatedMessageIds,
            options = ConversationReadOptions(beforeUidByFolder = mapOf("INBOX" to recent.folders.single().lowerUid)))
        assertFalse(older.folders.single().olderHistoryAvailable)
        assertEquals("<old@example.com>", older.threads.single().messages.single().message.messageID)
        val copies = (recent.threads + older.threads).flatMap { it.messages }.flatMap { it.copies }
        val fresh = located(MimeMessage(fixture.rows.getValue(6)).apply { setFlag(jakarta.mail.Flags.Flag.SEEN, true) }, 6)
        val merged = assembleConversations(copies + fresh)
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().messages.size)
        assertFalse(merged.single().messages.last().unread)
    }
}
