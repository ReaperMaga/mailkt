package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Reads messages at the one-based positions in [range], counting from the latest message, and
 * leaves the folder open until the result is closed.
 */
suspend fun readMessages(
    session: MailSession,
    folderName: String,
    range: IntRange = 1..100,
): ReadMessagesResult {
    require(folderName.isNotBlank()) { "folderName must not be blank" }
    require(!range.isEmpty() && range.first > 0) { "range must be non-empty and positive" }

    return runInterruptible(Dispatchers.IO) {
        val connection = requireNotNull(session.currentConnection) { "Mail session is not connected" }
        val folder = connection.store.getFolder(folderName)
        try {
            folder.open(Folder.READ_ONLY)
            val count = folder.messageCount
            val messages =
                if (count == 0 || range.first > count) {
                    emptyList()
                } else {
                    val oldestMessageNumber = maxOf(1, count - range.last + 1)
                    val newestMessageNumber = count - range.first + 1
                    folder.getMessages(oldestMessageNumber, newestMessageNumber).toList().asReversed()
                }
            ReadMessagesResult(messages, folder)
        } catch (exception: Exception) {
            runCatching { if (folder.isOpen) folder.close(false) }
            throw exception
        }
    }
}

class ReadMessagesResult internal constructor(
    val messages: List<Message>,
    val folder: Folder,
) {
    suspend fun close() =
        withContext(NonCancellable) {
            runInterruptible(Dispatchers.IO) {
                if (folder.isOpen) folder.close(false)
            }
        }
}
