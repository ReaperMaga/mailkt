package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailConnection
import dev.reapermaga.mailkt.session.MailSession
import dev.reapermaga.mailkt.session.ManagedMailSession
import dev.reapermaga.mailkt.session.ManagedMailSessionState
import jakarta.mail.Folder
import jakarta.mail.FolderClosedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.StoreClosedException
import jakarta.mail.UIDFolder
import jakarta.mail.event.ConnectionAdapter
import jakarta.mail.event.ConnectionEvent
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.internet.MimeMessage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.eclipse.angus.mail.imap.IdleManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A watcher failure for which starting against a live connection is safe when [recoverable]. */
class FolderWatchException(
    message: String,
    cause: Throwable? = null,
    val recoverable: Boolean = true,
) : MessagingException(message, cause as? Exception)

/**
 * Returns a cold stream of messages added to [name]. Every collector owns one folder and IDLE
 * manager; cancellation closes both resources.
 *
 * Messages are eagerly copied while the originating folder is open. Emitted values are detached
 * [MimeMessage]s, so bodies and attachments remain readable after cancellation or disconnection.
 * This overload cannot observe connection replacement; closure terminates with a recoverable
 * [FolderWatchException]. Use the [ManagedMailSession] overload for transparent reconnection.
 */
fun watchFolder(
    session: MailSession,
    name: String,
    folderMode: Int = Folder.READ_ONLY,
    bufferCapacity: Int = Channel.BUFFERED,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    executor: Executor = ioDispatcher.asExecutor(),
): Flow<Message> {
    validateArguments(name, bufferCapacity)
    return flow {
        val connection =
            requireNotNull(session.currentConnection) { "Mail session is not connected" }
        emitAll(
            watchConnection(
                connection,
                name,
                folderMode,
                bufferCapacity,
                ioDispatcher,
                executor,
                UidCheckpoint(catchUp = false),
            )
        )
    }
}

/**
 * Returns a cold, reconnecting stream of messages added to [name].
 *
 * Each collector follows [ManagedMailSession.state], disposes the old generation when reconnecting
 * starts, and opens a new folder/IDLE manager on the replacement connection. IMAP UIDs catch up
 * notifications across the reconnect window and suppress duplicates. Delivery is at-least-once
 * across reconnects and normally exactly once within a collection; consumers with persistent side
 * effects should still de-duplicate by account/folder/Message-ID. Servers without UID
 * support cannot provide reconnect catch-up.
 *
 * Every emitted value is an eagerly detached [MimeMessage], so downstream processing may suspend
 * and continue reading MIME parts after the source folder has been replaced. [executor] is
 * caller-owned and is never shut down by the watcher; the default executor owns no threads itself.
 */
fun watchFolder(
    managedSession: ManagedMailSession,
    name: String,
    folderMode: Int = Folder.READ_ONLY,
    bufferCapacity: Int = Channel.BUFFERED,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    executor: Executor = ioDispatcher.asExecutor(),
    recoveryDelay: Duration = 100.milliseconds,
): Flow<Message> {
    validateArguments(name, bufferCapacity)
    require(!recoveryDelay.isNegative()) { "recoveryDelay must not be negative" }
    return flow {
        val checkpoint = UidCheckpoint(catchUp = true)
        emitAll(
            watchManagedConnections(managedSession.state, name, recoveryDelay) { connection ->
                watchConnection(
                    connection,
                    name,
                    folderMode,
                    bufferCapacity,
                    ioDispatcher,
                    executor,
                    checkpoint,
                )
            }
        )
    }
        .buffer(bufferCapacity, BufferOverflow.SUSPEND)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun watchManagedConnections(
    states: Flow<ManagedMailSessionState>,
    name: String,
    recoveryDelay: Duration,
    watcher: (MailConnection) -> Flow<Message>,
): Flow<Message> =
    states.transformLatest { state ->
        when (state) {
            is ManagedMailSessionState.Connected -> {
                while (currentCoroutineContext().isActive) {
                    try {
                        watcher(state.connection).collect { emit(it) }
                        throw FolderWatchException("Folder watcher for '$name' ended unexpectedly")
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: FolderWatchException) {
                        if (!exception.recoverable) throw exception
                        delay(recoveryDelay)
                    }
                }
            }

            is ManagedMailSessionState.Reconnecting,
            is ManagedMailSessionState.ReconnectFailed -> Unit

            is ManagedMailSessionState.Stopped ->
                throw FolderWatchException(
                    "Managed mail session stopped while watching '$name'",
                    state.cause,
                    recoverable = false,
                )
        }
    }

private fun watchConnection(
    connection: MailConnection,
    name: String,
    folderMode: Int,
    bufferCapacity: Int,
    ioDispatcher: CoroutineDispatcher,
    executor: Executor,
    checkpoint: UidCheckpoint,
): Flow<Message> =
    callbackFlow {
            // Angus invokes listeners on its event thread, which must never be blocked. The
            // configured backpressure is applied after detachment; originals are not dropped.
            val pending = Channel<Message>(Channel.UNLIMITED)
            val lifecycle = WatchLifecycle()

            fun fail(cause: Throwable) {
                val failure =
                    cause as? FolderWatchException
                        ?: FolderWatchException(
                            "Folder '$name' or its store closed while watching",
                            cause,
                        )
                pending.close(failure)
                close(failure)
            }

            val connectionListener =
                object : ConnectionAdapter() {
                    override fun closed(event: ConnectionEvent) {
                        fail(FolderWatchException("Folder '$name' or its store was closed"))
                    }
                }
            val messageListener =
                object : MessageCountAdapter() {
                    override fun messagesAdded(event: MessageCountEvent) {
                        event.messages.forEach { message ->
                            pending.trySend(message)
                        }
                        val source = event.source as? Folder ?: return
                        try {
                            lifecycle.idleManager?.watch(source)
                        } catch (exception: Exception) {
                            fail(exception)
                        }
                    }
                }

            lifecycle.cleanupAction = {
                pending.close()
                lifecycle.folder?.let { opened ->
                    runCatching { opened.removeMessageCountListener(messageListener) }
                    runCatching { opened.removeConnectionListener(connectionListener) }
                }
                runCatching { connection.store.removeConnectionListener(connectionListener) }
                runCatching { lifecycle.idleManager?.stop() }
                runCatching {
                    lifecycle.folder?.let { opened -> if (opened.isOpen) opened.close(false) }
                }
            }

            val worker =
                launch(ioDispatcher) {
                    try {
                        for (message in pending) {
                            val sourceFolder = message.folder ?: lifecycle.folder
                            try {
                                val uid = (lifecycle.folder as? UIDFolder)?.getUID(message)
                                if (checkpoint.wasDelivered(uid)) continue
                                val detached = runInterruptible {
                                    detachWatcherMessage(message, sourceFolder)
                                }
                                send(detached)
                                checkpoint.markDelivered(uid)
                            } catch (exception: IllegalStateException) {
                                // Normalize while the worker can still observe the source folder;
                                // awaitClose cleanup may close it after this block exits.
                                throw normalizeClosedFolderFailure(sourceFolder, exception)
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: FolderClosedException) {
                        fail(exception)
                    } catch (exception: StoreClosedException) {
                        fail(exception)
                    } catch (exception: Exception) {
                        close(exception)
                    }
                }

            try {
                runInterruptible(ioDispatcher) {
                    val idleManager = IdleManager(connection.session, executor)
                    val folder = connection.store.getFolder(name)
                    lifecycle.idleManager = idleManager
                    lifecycle.folder = folder
                    folder.addConnectionListener(connectionListener)
                    connection.store.addConnectionListener(connectionListener)
                    folder.open(folderMode)

                    val uidFolder = folder as? UIDFolder
                    val firstUid = checkpoint.prepareGeneration(uidFolder)
                    folder.addMessageCountListener(messageListener)
                    idleManager.watch(folder)
                    if (firstUid != null) {
                        uidFolder
                            ?.getMessagesByUID(firstUid, UIDFolder.LASTUID)
                            ?.filterNotNull()
                            ?.forEach { message -> pending.trySend(message) }
                    }
                }
            } catch (exception: CancellationException) {
                lifecycle.cleanup()
                throw exception
            } catch (exception: Exception) {
                lifecycle.cleanup()
                throw when (exception) {
                    is FolderWatchException -> exception
                    is FolderClosedException,
                    is StoreClosedException ->
                        FolderWatchException("Folder '$name' closed while starting watcher", exception)
                    else -> exception
                }
            }

            worker.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) close(cause)
            }
            awaitClose {
                worker.cancel()
                lifecycle.cleanup()
            }
        }
        .buffer(bufferCapacity, BufferOverflow.SUSPEND)

internal class WatchLifecycle {
    private val cleaned = AtomicBoolean(false)
    var folder: Folder? = null
    var idleManager: IdleManager? = null
    var cleanupAction: () -> Unit = {}

    fun cleanup() {
        if (cleaned.compareAndSet(false, true)) cleanupAction()
    }
}

private class UidCheckpoint(private val catchUp: Boolean) {
    private var initialized = false
    private var initialUidFloor = UIDFolder.LASTUID
    private var uidValidity: Long? = null
    private val delivered = HashSet<Long>()

    @Synchronized
    fun prepareGeneration(folder: UIDFolder?): Long? {
        if (!catchUp || folder == null) return null
        val currentValidity = folder.uidValidity
        if (!initialized) {
            initialUidFloor = folder.uidNext - 1
            uidValidity = currentValidity
            initialized = true
        } else if (uidValidity != currentValidity) {
            // UIDs from the previous mailbox incarnation cannot be compared. Re-scan the mailbox;
            // this deliberately prefers duplicates over loss to retain at-least-once semantics.
            initialUidFloor = 0
            uidValidity = currentValidity
            delivered.clear()
        }
        return initialUidFloor + 1
    }

    @Synchronized
    fun wasDelivered(uid: Long?): Boolean =
        uid != null && (uid <= initialUidFloor || uid in delivered)

    @Synchronized
    fun markDelivered(uid: Long?) {
        if (uid != null && uid > initialUidFloor) delivered += uid
    }
}

internal fun detachMessage(message: Message): MimeMessage =
    when (message) {
        is MimeMessage -> MimeMessage(message)
        else -> throw MessagingException("Only MIME messages can be detached safely")
    }

internal fun detachWatcherMessage(message: Message, sourceFolder: Folder?): MimeMessage =
    try {
        detachMessage(message)
    } catch (exception: IllegalStateException) {
        val normalized = normalizeClosedFolderFailure(sourceFolder, exception)
        if (normalized is FolderClosedException) {
            throw FolderWatchException("Source folder closed while detaching message", normalized)
        }
        throw normalized
    }

private fun validateArguments(name: String, bufferCapacity: Int) {
    require(name.isNotBlank()) { "name must not be blank" }
    require(bufferCapacity == Channel.BUFFERED || bufferCapacity > 0) {
        "bufferCapacity must be positive or Channel.BUFFERED"
    }
}
