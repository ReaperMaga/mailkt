package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message

/**
 * Reads a specified number of messages from a folder within the given mail session.
 *
 * @param session The active mail session containing the target mail store.
 * @param folderName The name of the folder to read messages from.
 * @param limit The maximum number of messages to retrieve, starting from the newest ones. Defaults to 100.
 * @return A list of messages retrieved from the folder.
 */
fun readMessages(session: MailSession, folderName: String, limit: Int = 100): List<Message> {
    val folder = session.currentStore.getFolder(folderName)
    folder.open(Folder.READ_ONLY)
    val count = folder.messageCount
    val start = maxOf(1, count - limit + 1)
    val messages = folder.getMessages(start, count).toList()
    folder.close()
    return messages
}