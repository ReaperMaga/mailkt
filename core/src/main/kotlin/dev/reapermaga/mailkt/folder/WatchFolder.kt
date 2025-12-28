package dev.reapermaga.mailkt.folder

import com.sun.mail.imap.IdleManager
import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.event.MessageCountListener

data class WatchFolder(val folder: Folder, val listener: MessageCountListener) {
    fun close() {
        folder.removeMessageCountListener(listener)
        if (folder.isOpen) {
            folder.close(false)
        }
    }
}

fun watchFolder(
    session: MailSession,
    idleManager: IdleManager,
    name: String,
    folderMode: Int = Folder.READ_ONLY,
    receive: (message: Message) -> Unit,
): WatchFolder {
    val folder = session.currentStore.getFolder(name)
    folder.open(folderMode)
    val listener =
        object : MessageCountAdapter() {
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
    return WatchFolder(folder, listener)
}
