package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Reads up to [limit] recent messages and leaves the folder open until the result is closed. */
suspend fun readMessages(
    session: MailSession,
    folderName: String,
    limit: Int = 100,
): ReadMessagesResult {
    require(folderName.isNotBlank()) { "folderName must not be blank" }
    require(limit > 0) { "limit must be positive" }

    return runInterruptible(Dispatchers.IO) {
        val connection = requireNotNull(session.currentConnection) { "Mail session is not connected" }
        val folder = connection.store.getFolder(folderName)
        try {
            folder.open(Folder.READ_ONLY)
            val count = folder.messageCount
            val messages =
                if (count == 0) emptyList()
                else folder.getMessages(maxOf(1, count - limit + 1), count).toList()
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
