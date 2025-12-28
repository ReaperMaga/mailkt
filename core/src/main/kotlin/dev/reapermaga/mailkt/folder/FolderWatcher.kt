package dev.reapermaga.mailkt.folder

import com.sun.mail.imap.IdleManager
import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class FolderWatchHandle(val folder: Folder, val idleManager: IdleManager) {
    fun close() {
        idleManager.stop()
        if (folder.isOpen) {
            folder.close(false)
        }
    }
}

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
