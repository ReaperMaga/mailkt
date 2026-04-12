package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message

/**
 * Reads a specified number of messages from a folder in the connected mail session.
 *
 * @param session Active mail session containing the target store.
 * @param folderName Name of the folder from which to read messages.
 * @param limit Maximum number of messages to retrieve, starting from the most recent. Defaults to 100.
 * @return A [ReadMessagesResult] containing the messages retrieved and the folder instance.
 */
fun readMessages(session: MailSession, folderName: String, limit: Int = 100): ReadMessagesResult {
    val folder = session.currentStore.getFolder(folderName)
    folder.open(Folder.READ_ONLY)
    val count = folder.messageCount
    val start = maxOf(1, count - limit + 1)
    val messages = folder.getMessages(start, count).toList()
    return ReadMessagesResult(messages, folder)
}

data class ReadMessagesResult(val messages: List<Message>, val folder: Folder)