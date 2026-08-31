package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runInterruptible
import org.eclipse.angus.mail.imap.IdleManager
import java.util.concurrent.Executor

/**
 * Returns a cold stream of messages added to [name]. Every collector owns one folder and IDLE
 * manager; cancellation closes both resources.
 */
fun watchFolder(
    session: MailSession,
    name: String,
    folderMode: Int = Folder.READ_ONLY,
    bufferCapacity: Int = Channel.BUFFERED,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    executor: Executor = ioDispatcher.asExecutor(),
): Flow<Message> {
    require(name.isNotBlank()) { "name must not be blank" }
    require(bufferCapacity == Channel.BUFFERED || bufferCapacity > 0) {
        "bufferCapacity must be positive or Channel.BUFFERED"
    }

    return callbackFlow {
            var idleManager: IdleManager? = null
            var folder: Folder? = null
            val listener =
                object : MessageCountAdapter() {
                    override fun messagesAdded(event: MessageCountEvent) {
                        event.messages.forEach { trySend(it) }
                        val source = event.source as? Folder ?: return
                        runCatching { idleManager?.watch(source) }.onFailure { close(it) }
                    }
                }

            try {
                runInterruptible(ioDispatcher) {
                    val connection =
                        requireNotNull(session.currentConnection) { "Mail session is not connected" }
                    val createdIdleManager = IdleManager(connection.session, executor)
                    val openedFolder = connection.store.getFolder(name)
                    idleManager = createdIdleManager
                    folder = openedFolder
                    openedFolder.open(folderMode)
                    openedFolder.addMessageCountListener(listener)
                    createdIdleManager.watch(openedFolder)
                }
            } catch (exception: Exception) {
                runCatching { folder?.removeMessageCountListener(listener) }
                runCatching { idleManager?.stop() }
                runCatching { if (folder?.isOpen == true) folder?.close(false) }
                throw exception
            }

            awaitClose {
                runCatching { folder?.removeMessageCountListener(listener) }
                runCatching { idleManager?.stop() }
                runCatching { if (folder?.isOpen == true) folder?.close(false) }
            }
        }
        .buffer(bufferCapacity, BufferOverflow.DROP_OLDEST)
}
