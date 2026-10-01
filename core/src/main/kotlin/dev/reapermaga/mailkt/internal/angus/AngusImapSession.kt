package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.internal.transport.ImapFolderPort
import dev.reapermaga.mailkt.internal.transport.ImapSessionPort
import dev.reapermaga.mailkt.model.*
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.StoreClosedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore

internal class AngusImapSession(
    private val store: IMAPStore,
    private val session: Session,
    private val id: MailboxId,
) : ImapSessionPort {

    override suspend fun listFolders(): List<FolderInfo> = runInterruptible(Dispatchers.IO) {
        store.defaultFolder.list("*").map { f ->
            val attrs = (f as? IMAPFolder)?.attributes?.toSet().orEmpty()
            FolderInfo(
                path = FolderPath(f.fullName),
                name = f.name,
                specialUse = specialUse(f.fullName, attrs),
                selectable = (f.type and Folder.HOLDS_MESSAGES) != 0 && "\\Noselect" !in attrs,
            )
        }
    }

    override suspend fun <T> withFolder(path: FolderPath, readOnly: Boolean, block: suspend (ImapFolderPort) -> T): T {
        val folder = store.getFolder(path.value) as IMAPFolder
        val opened = runInterruptible(Dispatchers.IO) {
            if (!folder.exists()) throw MailException.FolderNotFound(path)
            folder.open(if (readOnly) Folder.READ_ONLY else Folder.READ_WRITE)
            check(!folder.uidNotSticky) { "Folder does not provide persistent UIDs" }
            AngusImapFolder(folder, session, id, path)
        }
        try {
            return block(opened)
        } finally {
            withContext(NonCancellable) {
                runCatching { runInterruptible(Dispatchers.IO) { if (folder.isOpen) folder.close(false) } }
            }
        }
    }

    override suspend fun noop() = runInterruptible(Dispatchers.IO) {
        // IMAPStore.isConnected() pings the server.
        if (!store.isConnected) throw StoreClosedException(store, "Store is not connected")
    }

    override suspend fun isConnected(): Boolean = runInterruptible(Dispatchers.IO) { store.isConnected }

    override suspend fun close() {
        withContext(NonCancellable) {
            runCatching { runInterruptible(Dispatchers.IO) { if (store.isConnected) store.close() } }
        }
    }

    private fun specialUse(fullName: String, attrs: Set<String>): SpecialUse? = when {
        fullName.equals("INBOX", ignoreCase = true) -> SpecialUse.INBOX
        "\\Sent" in attrs -> SpecialUse.SENT
        "\\Drafts" in attrs -> SpecialUse.DRAFTS
        "\\Trash" in attrs -> SpecialUse.TRASH
        "\\Junk" in attrs -> SpecialUse.JUNK
        "\\Archive" in attrs -> SpecialUse.ARCHIVE
        "\\All" in attrs -> SpecialUse.ALL
        "\\Flagged" in attrs -> SpecialUse.FLAGGED
        else -> null
    }
}
