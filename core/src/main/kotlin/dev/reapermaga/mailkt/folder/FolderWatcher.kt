package dev.reapermaga.mailkt.folder

import com.sun.mail.imap.IdleManager
import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Handle returned by [watchFolder] that keeps the watched [folder] and [idleManager] alive.
 * Call [close] to stop listening for new messages and release the underlying resources.
 */
data class FolderWatchHandle(val folder: Folder, val idleManager: IdleManager) {
    fun close() {
        idleManager.stop()
        if (folder.isOpen) {
            folder.close(false)
        }
    }
}

/**
 * Opens the given folder in the provided [session], registers an IMAP IDLE listener that invokes [receive]
 * for each newly added message, and returns a [FolderWatchHandle] for lifecycle management.
 *
 * @param session Active mail session containing the target store.
 * @param name Folder name to watch.
 * @param threadPool Executor used by [IdleManager]; defaults to a single-thread executor.
 * @param folderMode Mode used when opening the folder (e.g., [Folder.READ_ONLY]).
 * @param receive Callback invoked with the latest message whenever the server pushes updates.
 * @return A [FolderWatchHandle] that can be closed to stop watching.
 */
fun watchFolder(
    session: MailSession,
    name: String,
    threadPool: ExecutorService = Executors.newSingleThreadExecutor(),
    folderMode: Int = Folder.READ_ONLY,
    receive: (message: Message) -> Unit,
): FolderWatchHandle {
    val idleManager = IdleManager(session.currentSession, threadPool)
    val folder = session.currentStore.getFolder(name)
    folder.open(folderMode)
    val listener = object : MessageCountAdapter() {
        override fun messagesAdded(event: MessageCountEvent) {
            val source = event.source as Folder
            val latestMessage = source.getMessage(source.messageCount)
            try {
                receive(latestMessage)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            idleManager.watch(source)
        }
    }
    folder.addMessageCountListener(listener)
    idleManager.watch(folder)
    return FolderWatchHandle(folder, idleManager)
}
