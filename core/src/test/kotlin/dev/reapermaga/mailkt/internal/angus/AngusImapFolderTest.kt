package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.internal.mime.FailureClass
import dev.reapermaga.mailkt.internal.mime.RecoveryClassifier
import dev.reapermaga.mailkt.model.FolderPath
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.RecoveryReason
import jakarta.mail.FolderClosedException
import jakarta.mail.Session
import jakarta.mail.URLName
import kotlinx.coroutines.runBlocking
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AngusImapFolderTest {
    private val session = Session.getInstance(Properties())

    /** A store that offers IDLE without connecting, so the watcher takes the IDLE path. */
    private class IdleStore(session: Session) : IMAPStore(session, URLName("imap://localhost")) {
        override fun hasCapability(capability: String) = capability == "IDLE"
    }

    /** INBOX as a reconnect leaves it behind: closed, so Angus refuses IDLE with an IllegalStateException. */
    private class ClosedInbox(store: IMAPStore) : IMAPFolder("INBOX", '/', store, false) {
        override fun getUIDValidity() = 1L
        override fun getUIDNext() = 1L
    }

    @Test fun `waiting on a folder a reconnect closed is a recoverable folder closure`() = runBlocking {
        val folder = AngusImapFolder(ClosedInbox(IdleStore(session)), session, MailboxId("test", "a@example.com"), FolderPath("INBOX"))

        val failure = assertFailsWith<FolderClosedException> { folder.awaitChange(1_000) }

        assertEquals(FailureClass.Recoverable(RecoveryReason.FOLDER_CLOSED), RecoveryClassifier.classify(failure))
    }
}
