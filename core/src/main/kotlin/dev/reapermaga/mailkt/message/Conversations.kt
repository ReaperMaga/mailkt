package dev.reapermaga.mailkt.message

import dev.reapermaga.mailkt.folder.*
import dev.reapermaga.mailkt.session.*
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class MessageLocation(val accountId: String, val folderName: String, val uidValidity: Long, val uid: Long)

/** One logical message with every mailbox copy retained for flags and synchronization. */
data class ConversationMessage(val copies: List<LocatedMessage>) {
    val message: MimeMessage get() = copies.first().item.message
    val locations: List<MessageLocation> get() = copies.map { it.location }
    val timestamp: Instant? get() = message.sentDate?.toInstant() ?: copies.first().item.receivedAt
    val unread: Boolean get() = copies.any { !it.item.message.isSet(Flags.Flag.SEEN) }
}

data class LocatedMessage(val location: MessageLocation, val item: HistoricalMessage)
data class EmailConversation(val id: String, val messages: List<ConversationMessage>) {
    /** Includes referenced ancestors even when their bodies have not been loaded yet. */
    val relatedMessageIds: Set<String> get() = messages.flatMap { messageThreadIds(it.message) }.toSet()
}
data class ConversationFolderSnapshot(val folderName: String, val uidValidity: Long, val upperUid: Long,
    val olderHistoryAvailable: Boolean, val lowerUid: Long = 1)
data class ConversationHistory(val threads: List<EmailConversation>, val folders: List<ConversationFolderSnapshot>)

data class ConversationFolderCursor(val uidValidity: Long, val highestProcessedUid: Long)
data class ConversationSyncCursor(val accountId: String, val folders: Map<String, ConversationFolderCursor>)
data class ConversationSyncMetrics(
    val folderIndexNanos: Long,
    val envelopeFetchNanos: Long,
    val fullMessageDownloadNanos: Long,
    val conversationAssemblyNanos: Long,
    val folderIndexCommands: Int,
    val envelopeFetchCommands: Int,
    val messageLookupCommands: Int,
    val fullMessageFetchCommands: Int,
    val downloadedBytes: Long,
    val recoveryAttempts: Int = 0,
    val successfulRecoveries: Int = 0,
) {
    val totalFetchCommands: Int get() = folderIndexCommands + envelopeFetchCommands +
        messageLookupCommands + fullMessageFetchCommands
}
data class ConversationSyncResult(
    val history: ConversationHistory,
    val cursor: ConversationSyncCursor,
    val metrics: ConversationSyncMetrics,
    val uidValidityResetFolders: Set<String>,
)

class ConversationSyncException(
    message: String,
    val retryCursor: ConversationSyncCursor,
    val failedLocations: List<MessageLocation>,
    cause: Throwable,
) : IllegalStateException(message, cause)

data class ConversationReadOptions(
    val maxUidPositionsPerFolder: Int = 2000,
    val maxMatchedMessages: Int = 500,
    val maxMessageBytes: Long = 25L * 1024 * 1024,
    val maxTotalBytes: Long = 50L * 1024 * 1024,
    val envelopeBatchSize: Int = 100,
    val fullMessageBatchSize: Int = 25,
    /** Exclusive per-folder boundary for progressive older history loading. */
    val beforeUidByFolder: Map<String, Long> = emptyMap(),
    /** Number of managed-session reconnects allowed for one synchronization call. */
    val maxRecoveryAttempts: Int = 2,
    /** Delay between invalidating a failed connection and retrying on its replacement. */
    val recoveryBackoff: Duration = 100.milliseconds,
    /** Maximum time to wait for each replacement connection. */
    val recoveryTimeout: Duration = 30.seconds,
) {
    init {
        require(maxUidPositionsPerFolder > 0 && maxMatchedMessages > 0)
        require(envelopeBatchSize in 1..500 && fullMessageBatchSize in 1..100)
        require(maxMessageBytes in 1..Int.MAX_VALUE.toLong() && maxTotalBytes > 0)
        require(maxRecoveryAttempts >= 0)
        require(!recoveryBackoff.isNegative() && recoveryTimeout.isPositive())
        require(beforeUidByFolder.all { (folder, uid) -> folder.isNotBlank() && uid > 0 })
    }
}

private val idPattern = Regex("<[^<>\\s]+>")
internal fun messageThreadIds(message: Message): Set<String> =
    listOf("Message-ID", "In-Reply-To", "References").flatMap { name ->
        message.getHeader(name).orEmpty().flatMap { idPattern.findAll(it).map { match -> match.value }.toList() }
    }.toSet()

/**
 * Groups by explicit reply relationships, never by subject or email domain. Copies sharing a
 * Message-ID are merged only when their sender, recipients, subject, date and bodies agree.
 * Missing IDs remain separate messages. Thread IDs may change when older ancestors are added.
 * Can also merge persisted history with subsequent reads; pass existing and new located messages.
 */
fun assembleConversations(messages: List<LocatedMessage>): List<EmailConversation> {
    val unique = messages.associateBy { it.location }.values.toList()
    val parents = mutableMapOf<String, String>()
    fun root(key: String): String {
        val parent = parents.getOrPut(key) { key }
        if (parent == key) return key
        return root(parent).also { parents[key] = it }
    }
    fun unite(a: String, b: String) {
        val left = root(a); val right = root(b)
        if (left != right) parents[maxOf(left, right)] = minOf(left, right)
    }
    fun key(item: LocatedMessage) = "${item.location.accountId}:" +
        (messageThreadIds(item.item.message).sorted().firstOrNull() ?: "uid:${item.location}")
    unique.forEach { item ->
        val ids = messageThreadIds(item.item.message).map { "${item.location.accountId}:$it" }
        ids.forEach { unite(key(item), it) }
    }
    // Provider-added Received/Delivered-To headers and differing flags do not split identical copies.
    fun fingerprint(message: MimeMessage): List<Any?> = listOf(
        message.messageID, message.from?.map { it.toString() }?.sorted(),
        listOf(Message.RecipientType.TO, Message.RecipientType.CC).map { type ->
            message.getRecipients(type)?.map { it.toString() }?.sorted()
        }, message.subject, message.sentDate, message.getHeader("In-Reply-To", " "),
        message.getHeader("References", " "), message.contentType,
        message.inputStream.use { stream -> java.security.MessageDigest.getInstance("SHA-256").let { digest ->
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().toList()
        } },
    )
    return unique.groupBy { root(key(it)) }.map { (threadId, rows) ->
        val logical = rows.groupBy { item ->
            if (item.item.message.messageID == null) listOf(item.location)
            else listOf(item.location.accountId, fingerprint(item.item.message))
        }.values.map { ConversationMessage(it) }
            .sortedWith(compareBy<ConversationMessage> { it.timestamp ?: Instant.EPOCH }.thenBy { it.locations.first().toString() })
        EmailConversation(threadId, logical)
    }.sortedWith(compareBy<EmailConversation> { it.messages.lastOrNull()?.timestamp ?: Instant.EPOCH }.thenBy { it.id })
}

internal data class ConversationEnvelope(
    val location: MessageLocation,
    val participants: Set<String>,
    val ids: Set<String>,
    val advertisedBytes: Int = -1,
)
internal data class ConversationIndex(
    val envelopes: List<ConversationEnvelope>,
    val snapshots: List<ConversationFolderSnapshot>,
    val nextFolders: Map<String, ConversationFolderCursor>,
    val resetFolders: Set<String> = emptySet(),
    val folderIndexNanos: Long = 0,
    val envelopeFetchNanos: Long = 0,
    val folderIndexCommands: Int = 0,
    val envelopeFetchCommands: Int = 0,
)
internal data class ConversationDownload(
    val messages: List<LocatedMessage>,
    val bytes: Long,
    val nanos: Long = 0,
    val lookupCommands: Int = 0,
    val fetchCommands: Int = 0,
)
internal interface ConversationTransport {
    suspend fun index(session: MailSession, folders: List<String>, options: ConversationReadOptions,
        cursor: ConversationSyncCursor?): ConversationIndex
    suspend fun download(session: MailSession, envelopes: List<ConversationEnvelope>,
        options: ConversationReadOptions): ConversationDownload
}

/**
 * Finds contact messages AND all transitively related ancestors/replies within the bounded history
 * window, across caller-selected folders (normally Inbox, Sent and any relevant archive folders).
 * Only envelopes are indexed for unrelated mail; bodies are downloaded only after thread discovery.
 * olderHistoryAvailable explicitly signals that this is not the entire mailbox. Increase the
 * window to include older history. Missing folders/provider failures/limit violations throw; no
 * partial success is returned. Folder snapshots are NOT whole-mailbox sync checkpoints when truncated.
 */
suspend fun readConversations(
    session: MailSession,
    folderNames: List<String>,
    contacts: Set<String>,
    threadMessageIds: Set<String> = emptySet(),
    options: ConversationReadOptions = ConversationReadOptions(),
): ConversationHistory = readConversations(session, folderNames, contacts, threadMessageIds, options, ImapConversationTransport)

suspend fun readConversations(
    session: ManagedMailSession,
    folderNames: List<String>,
    contacts: Set<String>,
    threadMessageIds: Set<String> = emptySet(),
    options: ConversationReadOptions = ConversationReadOptions(),
): ConversationHistory = synchronizeConversations(session, folderNames, contacts, threadMessageIds,
    options = options).history

suspend fun synchronizeConversations(
    session: MailSession,
    folderNames: List<String>,
    contacts: Set<String>,
    threadMessageIds: Set<String> = emptySet(),
    cursor: ConversationSyncCursor? = null,
    options: ConversationReadOptions = ConversationReadOptions(),
): ConversationSyncResult = synchronizeConversations(session, folderNames, contacts, threadMessageIds,
    cursor, options, ImapConversationTransport)

suspend fun synchronizeConversations(
    session: ManagedMailSession,
    folderNames: List<String>,
    contacts: Set<String>,
    threadMessageIds: Set<String> = emptySet(),
    cursor: ConversationSyncCursor? = null,
    options: ConversationReadOptions = ConversationReadOptions(),
): ConversationSyncResult = synchronizeConversations(session, folderNames, contacts,
    threadMessageIds, cursor, options, ImapConversationTransport)

private val conversationLogger = LoggerFactory.getLogger("dev.reapermaga.mailkt.message.Conversations")

private class PinnedMailSession(
    private val delegate: MailSession,
    override val currentConnection: MailConnection,
) : MailSession {
    override val id: String get() = delegate.id
    override val isConnected: Boolean get() = currentConnection.store.isConnected
    override suspend fun connect(credentials: MailCredentials): MailConnection =
        error("A pinned managed-session generation cannot connect")
    override suspend fun disconnect() = Unit
}

private class ConversationTransportException(
    val folderName: String,
    val phase: String,
    cause: Throwable,
) : RuntimeException(cause)

private fun conversationExceptionChain(failure: Throwable): List<Throwable> {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    fun walk(current: Throwable?) {
        if (current == null || !visited.add(current) || current is CancellationException) return
        walk(current.cause)
        if (current is MessagingException) walk(current.nextException)
    }
    walk(failure)
    return visited.toList()
}

private fun conversationRecoveryReason(failure: Throwable): ManagedMailSession.RecoveryReason? {
    val chain = conversationExceptionChain(failure)
    return when {
        chain.any { it is StoreClosedException } -> ManagedMailSession.RecoveryReason.STORE_CLOSED
        chain.any { it is FolderClosedException } -> ManagedMailSession.RecoveryReason.FOLDER_CLOSED
        chain.any { it is SSLException || it is SocketException || it is SocketTimeoutException } ->
            ManagedMailSession.RecoveryReason.SOCKET_FAILURE
        else -> null
    }
}

private fun conversationFailureContext(failure: Throwable): Pair<String, String> {
    val contextual = conversationExceptionChain(failure).filterIsInstance<ConversationTransportException>().firstOrNull()
    return (contextual?.folderName ?: "<unknown>") to (contextual?.phase ?: "synchronization")
}

internal suspend fun synchronizeConversations(
    session: ManagedMailSession,
    folderNames: List<String>,
    contacts: Set<String>,
    threadMessageIds: Set<String>,
    cursor: ConversationSyncCursor?,
    options: ConversationReadOptions,
    transport: ConversationTransport,
): ConversationSyncResult {
    require(cursor == null || cursor.accountId == session.session.id) { "Conversation cursor belongs to another account" }
    var recoveries = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val generation = session.generation()
        val pinned = PinnedMailSession(session.session, generation.connection)
        try {
            val result = synchronizeConversations(pinned, folderNames, contacts, threadMessageIds,
                cursor, options, transport)
            if (recoveries > 0) {
                conversationLogger.info(
                    "Conversation recovery session={} attempts={} outcome=succeeded generation={}",
                    session.session.id, recoveries, generation.number,
                )
            }
            return result.copy(metrics = result.metrics.copy(
                recoveryAttempts = recoveries,
                successfulRecoveries = recoveries,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            val reason = conversationRecoveryReason(e)
            val (reportedFolder, phase) = conversationFailureContext(e)
            val folder = if (reportedFolder == "<unknown>" && folderNames.size == 1) folderNames.single()
                else reportedFolder
            if (reason == null) throw e
            val nextRecovery = recoveries + 1
            val accepted = session.requestRecovery(generation, reason)
            conversationLogger.warn(
                "Conversation recovery session={} folder={} phase={} attempt={} generation={} reason={} outcome={}",
                session.session.id, folder, phase, nextRecovery, generation.number, reason,
                if (accepted) "requested" else "coalesced-or-stale",
            )
            try {
                runInterruptible(Dispatchers.IO) { generation.connection.store.close() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (closeFailure: Throwable) {
                conversationLogger.debug(
                    "Conversation recovery session={} generation={} failed-store-close={}",
                    session.session.id, generation.number, closeFailure.javaClass.name,
                )
            }
            if (recoveries >= options.maxRecoveryAttempts) {
                val failed = (e as? ConversationSyncException)?.failedLocations.orEmpty()
                throw ConversationSyncException(
                    "Conversation synchronization failed for folder '$folder' during $phase after $recoveries retries",
                    cursor ?: ConversationSyncCursor(session.session.id, emptyMap()), failed, e,
                )
            }
            try {
                delay(options.recoveryBackoff)
                withTimeout(options.recoveryTimeout) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        when (val state = session.state.value) {
                            is ManagedMailSessionState.Connected ->
                                if (session.generation() !== generation) return@withTimeout
                            is ManagedMailSessionState.Stopped ->
                                throw IllegalStateException("Managed session stopped during conversation recovery", state.cause)
                            else -> Unit
                        }
                        delay(options.recoveryBackoff.coerceAtLeast(1.milliseconds))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (recoveryFailure: Throwable) {
                recoveryFailure.addSuppressed(e)
                throw ConversationSyncException(
                    "Conversation synchronization failed for folder '$folder' during $phase while waiting for retry $nextRecovery",
                    cursor ?: ConversationSyncCursor(session.session.id, emptyMap()),
                    (e as? ConversationSyncException)?.failedLocations.orEmpty(), recoveryFailure,
                )
            }
            recoveries = nextRecovery
        }
    }
}

internal suspend fun readConversations(session: MailSession, folderNames: List<String>, contacts: Set<String>,
    threadMessageIds: Set<String>, options: ConversationReadOptions, transport: ConversationTransport): ConversationHistory =
    synchronizeConversations(session, folderNames, contacts, threadMessageIds, null, options, transport).history

internal suspend fun synchronizeConversations(session: MailSession, folderNames: List<String>, contacts: Set<String>,
    threadMessageIds: Set<String>, cursor: ConversationSyncCursor?, options: ConversationReadOptions,
    transport: ConversationTransport): ConversationSyncResult {
    require(folderNames.isNotEmpty() && folderNames.all { it.isNotBlank() })
    require(contacts.isNotEmpty() || threadMessageIds.isNotEmpty()) { "A contact address or thread ID is required" }
    require(cursor == null || cursor.accountId == session.id) { "Conversation cursor belongs to another account" }
    MessageFilter(contacts, threadMessageIds) // Validate without allowing an accidental unfiltered scan.
    val addresses = contacts.map { InternetAddress(it, true).address.lowercase(java.util.Locale.ROOT) }.toSet()
    val distinctFolders = folderNames.distinct()
    val index = transport.index(session, distinctFolders, options, cursor)
    val ids = threadMessageIds.toMutableSet()
    val selected = linkedSetOf<ConversationEnvelope>()
    // Fixed point, independent of folder order or direction of replies.
    do {
        var changed = false
        index.envelopes.forEach { envelope ->
            if (envelope !in selected && (envelope.participants.any { it in addresses } || envelope.ids.any { it in ids })) {
                selected += envelope
                ids += envelope.ids
                changed = true
            }
        }
    } while (changed)
    check(selected.size <= options.maxMatchedMessages) { "Conversation message limit exceeded; narrow history window" }
    selected.forEach { envelope ->
        if (envelope.advertisedBytes >= 0) {
            check(envelope.advertisedBytes.toLong() <= options.maxMessageBytes) {
                "Conversation message byte limit exceeded at ${envelope.location}"
            }
        }
    }
    val advertisedTotal = selected.sumOf { maxOf(0, it.advertisedBytes).toLong() }
    check(advertisedTotal <= options.maxTotalBytes) { "Conversation byte limit exceeded; narrow history window" }
    val retryCursor = cursor ?: ConversationSyncCursor(session.id, emptyMap())
    val download = try {
        transport.download(session, selected.toList(), options)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val batchFailure = conversationExceptionChain(e).filterIsInstance<ConversationBatchException>().firstOrNull()
        throw ConversationSyncException("Conversation batch download failed; retry from the supplied cursor",
            retryCursor, batchFailure?.failedLocations ?: selected.map { it.location }, e)
    }
    val assemblyStarted = System.nanoTime()
    val threads = assembleConversations(download.messages)
    val assemblyNanos = System.nanoTime() - assemblyStarted
    val nextCursor = ConversationSyncCursor(session.id, index.nextFolders)
    return ConversationSyncResult(
        ConversationHistory(threads, index.snapshots), nextCursor,
        ConversationSyncMetrics(index.folderIndexNanos, index.envelopeFetchNanos, download.nanos,
            assemblyNanos, index.folderIndexCommands, index.envelopeFetchCommands,
            download.lookupCommands, download.fetchCommands, download.bytes),
        index.resetFolders,
    )
}

private class ConversationBatchException(
    val failedLocations: List<MessageLocation>,
    cause: Throwable,
) : RuntimeException(cause)

private object ImapConversationTransport : ConversationTransport {
    override suspend fun index(session: MailSession, folders: List<String>, options: ConversationReadOptions,
        cursor: ConversationSyncCursor?): ConversationIndex =
        runInterruptible(Dispatchers.IO) {
            val connection = checkNotNull(session.currentConnection) { "Mail session is not connected" }
            val envelopes = mutableListOf<ConversationEnvelope>()
            val nextFolders = mutableMapOf<String, ConversationFolderCursor>()
            val resetFolders = mutableSetOf<String>()
            var folderNanos = 0L
            var envelopeNanos = 0L
            var folderCommands = 0
            var envelopeCommands = 0
            val snapshots = folders.map { name ->
                val folder = connection.store.getFolder(name)
                try {
                    val folderStarted = System.nanoTime()
                    folder.open(Folder.READ_ONLY)
                    val uids = folder as? UIDFolder ?: error("Folder does not support UIDs")
                    check((folder as? org.eclipse.angus.mail.imap.IMAPFolder)?.uidNotSticky != true)
                    val validity = uids.uidValidity
                    val serverUpper = uids.uidNext - 1
                    check(validity > 0 && serverUpper >= 0)
                    folderCommands++ // SELECT/open and its UID metadata.
                    val previous = cursor?.folders?.get(name)
                    val incremental = previous != null && previous.uidValidity == validity
                    if (previous != null && !incremental) resetFolders += name
                    val requestedUpper = options.beforeUidByFolder[name]?.minus(1)
                    val lower: Long
                    val upper: Long
                    if (incremental) {
                        lower = previous.highestProcessedUid + 1
                        upper = minOf(serverUpper, lower + options.maxUidPositionsPerFolder - 1)
                    } else {
                        upper = minOf(serverUpper, requestedUpper ?: serverUpper)
                        lower = maxOf(1, upper - options.maxUidPositionsPerFolder + 1)
                    }
                    folderNanos += System.nanoTime() - folderStarted
                    var start = lower
                    while (start <= upper) {
                        val end = minOf(upper, start + options.envelopeBatchSize - 1)
                        val lookupStarted = System.nanoTime()
                        val rows = uids.getMessagesByUID(start, end).filterNotNull().toTypedArray()
                        folderCommands++
                        folderNanos += System.nanoTime() - lookupStarted
                        if (rows.isNotEmpty()) {
                            val envelopeStarted = System.nanoTime()
                            folder.fetch(rows, FetchProfile().apply {
                                add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.SIZE)
                                add(UIDFolder.FetchProfileItem.UID)
                                add("Message-ID"); add("In-Reply-To"); add("References")
                            })
                            envelopeCommands++
                            envelopeNanos += System.nanoTime() - envelopeStarted
                        }
                        rows.forEach { message ->
                            val participants = (message.from.orEmpty().toList() + message.allRecipients.orEmpty().toList())
                                .mapNotNull { (it as? InternetAddress)?.address?.lowercase(java.util.Locale.ROOT) }.toSet()
                            envelopes += ConversationEnvelope(MessageLocation(session.id, name, validity, uids.getUID(message)),
                                participants, messageThreadIds(message), message.size)
                        }
                        start = end + 1
                    }
                    check(uids.uidValidity == validity) { "UIDVALIDITY changed during conversation indexing" }
                    val processedUpper = if (upper >= lower) upper else previous?.highestProcessedUid ?: serverUpper
                    nextFolders[name] = ConversationFolderCursor(validity, processedUpper)
                    ConversationFolderSnapshot(name, validity, maxOf(0, upper), !incremental && lower > 1, maxOf(1, lower))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (Thread.currentThread().isInterrupted) throw e
                    throw ConversationTransportException(name, "header indexing", e)
                } finally { runCatching { if (folder.isOpen) folder.close(false) } }
            }
            ConversationIndex(envelopes, snapshots, nextFolders, resetFolders, folderNanos,
                envelopeNanos, folderCommands, envelopeCommands)
        }

    override suspend fun download(session: MailSession, envelopes: List<ConversationEnvelope>,
        options: ConversationReadOptions): ConversationDownload = runInterruptible(Dispatchers.IO) {
        val connection = checkNotNull(session.currentConnection) { "Mail session is not connected" }
        val downloadStarted = System.nanoTime()
        val downloaded = mutableMapOf<MessageLocation, HistoricalMessage>()
        var totalBytes = 0L
        var lookupCommands = 0
        var fetchCommands = 0
        envelopes.groupBy { it.location.folderName }.forEach { (folderName, folderEnvelopes) ->
            val folder = connection.store.getFolder(folderName)
            try {
                folder.open(Folder.READ_ONLY)
                val uids = folder as? UIDFolder ?: error("Folder does not support UIDs")
                val validity = uids.uidValidity
                check(folderEnvelopes.all { it.location.uidValidity == validity }) {
                    "UIDVALIDITY changed during conversation download"
                }
                for (batch in folderEnvelopes.chunked(options.fullMessageBatchSize)) {
                    try {
                        val rows = uids.getMessagesByUID(batch.map { it.location.uid }.toLongArray())
                            .filterNotNull().toTypedArray()
                        lookupCommands++
                        val byUid = rows.associateBy { uids.getUID(it) }
                        check(batch.all { it.location.uid in byUid }) { "Conversation message expunged during batch download" }
                        folder.fetch(rows, FetchProfile().apply {
                            add(org.eclipse.angus.mail.imap.IMAPFolder.FetchProfileItem.MESSAGE)
                            add(UIDFolder.FetchProfileItem.UID)
                        })
                        fetchCommands++
                        batch.forEach { envelope ->
                            val source = byUid.getValue(envelope.location.uid)
                            val bytes = ByteArrayOutputStream()
                            val remainingTotal = options.maxTotalBytes - totalBytes
                            val limit = minOf(options.maxMessageBytes, remainingTotal)
                            check(limit > 0) { "Conversation byte limit exceeded; narrow history window" }
                            val limited = object : FilterOutputStream(bytes) {
                                var count = 0L
                                override fun write(value: Int) {
                                    check(count < limit) { "Conversation message/total byte limit exceeded" }
                                    out.write(value); count++
                                }
                                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                    check(count + length <= limit) { "Conversation message/total byte limit exceeded" }
                                    out.write(buffer, offset, length); count += length
                                }
                            }
                            source.writeTo(limited)
                            totalBytes += bytes.size()
                            val detached = MimeMessage(connection.session, ByteArrayInputStream(bytes.toByteArray()))
                            detached.setFlags(source.flags, true)
                            downloaded[envelope.location] = HistoricalMessage(detached, envelope.location.uid,
                                validity, source.receivedDate?.toInstant())
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (Thread.currentThread().isInterrupted) throw e
                        throw ConversationBatchException(batch.map { it.location }, e)
                    }
                }
                check(uids.uidValidity == validity) { "UIDVALIDITY changed during conversation download" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (Thread.currentThread().isInterrupted) throw e
                throw ConversationTransportException(folderName, "full-message fetching", e)
            } finally { runCatching { if (folder.isOpen) folder.close(false) } }
        }
        ConversationDownload(envelopes.map { envelope ->
            LocatedMessage(envelope.location, downloaded.getValue(envelope.location))
        }, totalBytes, System.nanoTime() - downloadStarted, lookupCommands, fetchCommands)
    }
}
