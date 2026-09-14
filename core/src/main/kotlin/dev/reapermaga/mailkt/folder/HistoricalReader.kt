package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailConnection
import dev.reapermaga.mailkt.session.ManagedMailSession
import dev.reapermaga.mailkt.session.ManagedMailSessionState
import jakarta.mail.*
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A fully downloaded message. Server metadata is preserved separately from MIME headers. */
data class HistoricalMessage(
    val message: MimeMessage,
    val uid: Long,
    val uidValidity: Long,
    val receivedAt: Instant?,
)

data class HistoricalReadOptions(
    val downloadTimeout: Duration = 30.seconds,
    val recoveryTimeout: Duration = 120.seconds,
    val maxRecoveryAttempts: Int = 5,
    val retryDelay: Duration = 250.milliseconds,
    val maxMessageBytes: Long = 25L * 1024 * 1024,
) {
    init {
        require(downloadTimeout.isPositive() && downloadTimeout.isFinite())
        require(recoveryTimeout.isPositive() && recoveryTimeout.isFinite())
        require(retryDelay.isPositive() && retryDelay.isFinite())
        require(maxRecoveryAttempts >= 0)
        require(maxMessageBytes in 1..Int.MAX_VALUE.toLong())
    }
}

class HistoricalReadException(
    val operation: String,
    val uid: Long?,
    val attempt: Int,
    cause: Throwable,
) : RuntimeException("Historical read failed: operation=$operation, uid=$uid, attempt=$attempt", cause)

/**
 * Cold historical scan, newest position first (1 is the newest message). Positions are resolved
 * once to UIDs before downloading bodies. Each collector owns an independent snapshot.
 * No prefetch or output buffer is added. See the date overload for delivery and memory guarantees.
 */
fun readMessagesFlow(
    session: ManagedMailSession,
    folderName: String,
    range: IntRange = 1..100,
    options: HistoricalReadOptions = HistoricalReadOptions(),
): Flow<HistoricalMessage> {
    require(!range.isEmpty() && range.first > 0)
    return historicalFlow(session, folderName, HistoricalRange.Positions(range), options)
}

/**
 * Inclusive server received-date scan, newest mailbox position first. Dates use the local time
 * zone, matching [readMessages]. Membership is fixed before the first body download.
 *
 * A successful collection emits each selected UID once. Transport retries never restart downstream
 * processing or discard queued detached values. Explicit caller buffers retain readable copies;
 * cancellation/failure is not a durable delivery acknowledgement. A new collection starts a new
 * scan. Expunged UIDs, changed UIDVALIDITY and missing UID support fail explicitly.
 *
 * Memory: O(selected UIDs) metadata plus O(maxMessageBytes) for one MIME download (serialization
 * and parsing can require several copies). Caller buffers add one full message per slot. Bodies
 * are never downloaded as a batch. [HistoricalReadOptions.maxMessageBytes] bounds serialized bytes,
 * not the memory allocated by a mail provider or subsequent MIME decoding by the consumer.
 */
fun readMessagesFlow(
    session: ManagedMailSession,
    folderName: String,
    range: ClosedRange<LocalDate>,
    options: HistoricalReadOptions = HistoricalReadOptions(),
): Flow<HistoricalMessage> {
    require(!range.isEmpty())
    return historicalFlow(session, folderName, HistoricalRange.Dates(range), options)
}

internal sealed interface HistoricalRange {
    data class Positions(val range: IntRange) : HistoricalRange
    data class Dates(val range: ClosedRange<LocalDate>) : HistoricalRange
}

internal data class HistoricalSnapshot(val validity: Long, val uids: List<Long>)

// Transport seam keeps deterministic tests on the actual scan/recovery algorithm.
internal interface HistoricalTransport {
    fun snapshot(connection: MailConnection, name: String, range: HistoricalRange): HistoricalSnapshot
    fun download(connection: MailConnection, name: String, validity: Long, uid: Long, limit: Long): HistoricalMessage
    fun connected(connection: MailConnection): Boolean = connection.store.isConnected
}

private val historicalLogger = LoggerFactory.getLogger("dev.reapermaga.mailkt.folder.HistoricalReader")
private const val MAX_FOLDER_REOPENS = 2
internal const val SNAPSHOT_UID_BATCH_SIZE = 500

// Only our own deadlines produce this failure; caller/provider cancellation stays cancellation.
private class SnapshotTimeoutException(budget: String, timeout: Duration) :
    TimeoutException("Range resolution exceeded $budget timeout $timeout")

internal fun historicalFlow(
    session: ManagedMailSession,
    name: String,
    range: HistoricalRange,
    options: HistoricalReadOptions,
    transport: HistoricalTransport = ImapHistoricalTransport,
): Flow<HistoricalMessage> {
    require(name.isNotBlank())
    return flow {
        fun requestRecovery(generation: ManagedMailSession.Generation, reason: ManagedMailSession.RecoveryReason): Boolean {
            val accepted = session.requestRecovery(generation, reason)
            historicalLogger.info(
                "Historical recovery session={} generation={} reason={} outcome={}",
                session.session.id, generation.number, reason, if (accepted) "requested" else "coalesced-or-stale",
            )
            return accepted
        }
        suspend fun connection(onCandidate: (ManagedMailSession.Generation) -> Unit = {}): ManagedMailSession.Generation {
            while (true) {
                currentCoroutineContext().ensureActive()
                when (val state = session.state.value) {
                    is ManagedMailSessionState.Connected -> {
                        val generation = session.generation()
                        if (generation.connection === state.connection) {
                            onCandidate(generation)
                            if (runInterruptible(Dispatchers.IO) { transport.connected(state.connection) }) {
                                if (session.state.value == state) return generation
                            } else {
                                requestRecovery(generation, ManagedMailSession.RecoveryReason.DISCONNECTED)
                            }
                        }
                    }
                    is ManagedMailSessionState.Stopped ->
                        throw IllegalStateException("Managed session stopped", state.cause)
                    else -> Unit
                }
                delay(options.retryDelay)
            }
        }

        // An interrupted position resolution cannot safely be repeated: the original membership
        // is unknowable. Fail the snapshot explicitly instead of silently shifting the range.
        val snapshotStarted = TimeSource.Monotonic.markNow()
        var snapshotGeneration: ManagedMailSession.Generation? = null
        val snapshot = try {
            withTimeoutOrNull(options.recoveryTimeout) {
                // Include the selected store's liveness probe in generation-aware recovery.
                val live = connection { snapshotGeneration = it }
                withTimeoutOrNull(options.downloadTimeout) {
                    runInterruptible(Dispatchers.IO) { transport.snapshot(live.connection, name, range) }
                } ?: throw SnapshotTimeoutException("download", options.downloadTimeout)
            } ?: throw SnapshotTimeoutException("recovery", options.recoveryTimeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val reason = if (e is SnapshotTimeoutException) ManagedMailSession.RecoveryReason.SNAPSHOT_TIMEOUT
                else historicalTransportRecoveryReason(e)
            val failed = snapshotGeneration
            val outcome = if (reason != null && failed != null) {
                if (requestRecovery(failed, reason)) "requested" else "coalesced-or-stale"
            } else "not-requested"
            historicalLogger.warn(
                "Historical read operation=resolve-range uid=null attempt=1 elapsed={} generation={} outcome=failed failureType={} recoveryReason={} recoveryOutcome={}",
                snapshotStarted.elapsedNow(), failed?.number, e.javaClass.name, reason, outcome,
            )
            throw HistoricalReadException("resolve-range", null, 1, e)
        }
        historicalLogger.debug(
            "Historical read operation=resolve-range uid=null attempt=1 elapsed={} generation={} outcome=resolved selectedCount={}",
            snapshotStarted.elapsedNow(), snapshotGeneration?.number, snapshot.uids.size,
        )

        for (uid in snapshot.uids) {
            var attempt = 0
            var lastFailure: Exception? = null
            var folderFailures = 0
            var previousGeneration: ManagedMailSession.Generation? = null
            val started = TimeSource.Monotonic.markNow()
            val detached = try {
                withTimeout(options.recoveryTimeout) {
                    var result: HistoricalMessage? = null
                    while (result == null) {
                        val live = connection()
                        if (previousGeneration !== live) folderFailures = 0
                        previousGeneration = live
                        attempt++
                        try {
                            result = withTimeout(options.downloadTimeout) {
                                runInterruptible(Dispatchers.IO) {
                                    transport.download(live.connection, name, snapshot.validity, uid, options.maxMessageBytes)
                                }
                            }
                        } catch (e: Exception) {
                            // The per-download timeout is retryable; parent cancellation is not.
                            currentCoroutineContext().ensureActive()
                            if (e is CancellationException && e !is TimeoutCancellationException) throw e
                            lastFailure = e
                            val chain = historicalExceptionChain(e)
                            val reason = when {
                                e is TimeoutCancellationException -> ManagedMailSession.RecoveryReason.DOWNLOAD_TIMEOUT
                                chain.any { it is StoreClosedException } -> ManagedMailSession.RecoveryReason.STORE_CLOSED
                                chain.any { it is java.net.SocketException || it is java.net.SocketTimeoutException } ->
                                    ManagedMailSession.RecoveryReason.SOCKET_FAILURE
                                chain.any { it is FolderClosedException } -> {
                                    folderFailures++
                                    if (folderFailures > MAX_FOLDER_REOPENS) ManagedMailSession.RecoveryReason.FOLDER_CLOSED else null
                                }
                                else -> null
                            }
                            val retryable = isHistoricalTransportFailure(e) || e is TimeoutCancellationException
                            historicalLogger.warn(
                                "Historical read operation=download uid={} attempt={} elapsed={} generation={} reason={} outcome={} failureType={}",
                                uid, attempt, started.elapsedNow(), live.number, reason ?: "folder-reopen-or-terminal",
                                if (retryable && attempt <= options.maxRecoveryAttempts) "retry" else "exhausted-or-terminal",
                                e.javaClass.name,
                            )
                            // Invalidate the shared generation even on the last attempt, so other
                            // readers/watchers can recover. This does not extend this UID's budget.
                            if (reason != null) requestRecovery(live, reason)
                            if (!retryable || attempt > options.maxRecoveryAttempts) throw e
                            delay(options.retryDelay)
                        }
                    }
                    result
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                historicalLogger.warn(
                    "Historical read operation=download uid={} attempt={} elapsed={} generation={} reason=recovery-deadline outcome=failed",
                    uid, attempt, started.elapsedNow(), previousGeneration?.number,
                )
                throw HistoricalReadException("download", uid, attempt, lastFailure ?: e).also {
                    if (lastFailure != null && lastFailure !== e) it.addSuppressed(e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw HistoricalReadException("download", uid, attempt, e)
            }
            historicalLogger.debug(
                "Historical read operation=download uid={} attempt={} elapsed={} generation={} outcome=downloaded",
                uid, attempt, started.elapsedNow(), previousGeneration?.number,
            )
            // Never include emit in a retry or connection-state child job. It may suspend for an
            // arbitrarily slow consumer; completion is recorded only after it returns.
            emit(detached)
        }
    }
}

internal fun isHistoricalTransportFailure(failure: Throwable): Boolean {
    return historicalTransportRecoveryReason(failure) != null
}

private fun historicalTransportRecoveryReason(failure: Throwable): ManagedMailSession.RecoveryReason? {
    val chain = historicalExceptionChain(failure)
    return when {
        chain.any { it is StoreClosedException } -> ManagedMailSession.RecoveryReason.STORE_CLOSED
        chain.any { it is java.net.SocketException || it is java.net.SocketTimeoutException } ->
            ManagedMailSession.RecoveryReason.SOCKET_FAILURE
        // Angus may preserve a TLS failure only in this exception's message, not its cause.
        // The folder closure itself is sufficient evidence for recovery; never parse the text.
        chain.any { it is FolderClosedException } -> ManagedMailSession.RecoveryReason.FOLDER_CLOSED
        else -> null
    }
}

private fun historicalExceptionChain(failure: Throwable): List<Throwable> {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    fun walk(t: Throwable?) {
        if (t == null || !visited.add(t) || t is CancellationException) return
        walk(t.cause)
        if (t is MessagingException) walk(t.nextException)
    }
    walk(failure)
    return visited.toList()
}

internal object ImapHistoricalTransport : HistoricalTransport {
    private fun <T> folder(connection: MailConnection, name: String, block: (Folder, UIDFolder) -> T): T {
        val folder = connection.store.getFolder(name)
        var failure: Throwable? = null
        try {
            folder.open(Folder.READ_ONLY)
            val uids = folder as? UIDFolder ?: error("Server folder does not support stable UIDs")
            check((folder as? org.eclipse.angus.mail.imap.IMAPFolder)?.uidNotSticky != true) {
                "Server does not preserve UIDs between folder opens"
            }
            return block(folder, uids)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                if (folder.isOpen) folder.close(false)
            } catch (e: Exception) {
                if (failure != null) failure.addSuppressed(e) else throw e
            }
        }
    }

    override fun snapshot(connection: MailConnection, name: String, range: HistoricalRange) =
        folder(connection, name) { folder, uids ->
            val validity = uids.uidValidity
            check(validity > 0) { "Server returned invalid UIDVALIDITY" }
            val messages = when (range) {
                is HistoricalRange.Positions -> {
                    val count = folder.messageCount
                    if (range.range.first > count) emptyArray()
                    else folder.getMessages(maxOf(1, count - range.range.last + 1), count - range.range.first + 1)
                }
                is HistoricalRange.Dates -> {
                    fun LocalDate.date() = Date.from(atStartOfDay(ZoneId.systemDefault()).toInstant())
                    folder.search(AndTerm(
                        ReceivedDateTerm(ComparisonTerm.GE, range.range.start.date()),
                        ReceivedDateTerm(ComparisonTerm.LT, range.range.endInclusive.plusDays(1).date()),
                    ))
                }
            }
            // Freeze membership and order before any fetch; never reselect positions after I/O.
            val ordered = messages.sortedByDescending { it.messageNumber }
            val profile = FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) }
            val selected = ArrayList<Long>(ordered.size)
            for (batch in ordered.asSequence().chunked(SNAPSHOT_UID_BATCH_SIZE)) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                check(uids.uidValidity == validity) { "UIDVALIDITY changed during range resolution" }
                check(batch.none { it.isExpunged }) { "Requested message was expunged during range resolution" }
                folder.fetch(batch.toTypedArray(), profile)
                for (message in batch) {
                    check(!message.isExpunged) { "Requested message was expunged during range resolution" }
                    val uid = uids.getUID(message)
                    check(uid > 0) { "Requested message has no UID" }
                    selected += uid
                }
            }
            check(ordered.none { it.isExpunged }) { "Requested message was expunged during range resolution" }
            check(uids.uidValidity == validity) { "UIDVALIDITY changed during range resolution" }
            HistoricalSnapshot(validity, selected)
        }

    override fun download(connection: MailConnection, name: String, validity: Long, uid: Long, limit: Long) =
        folder(connection, name) { _, uids ->
            check(uids.uidValidity == validity) { "UIDVALIDITY changed; requested identities are invalid" }
            val original = uids.getMessageByUID(uid) ?: error("Requested UID $uid was expunged")
            check(!original.isExpunged) { "Requested UID $uid was expunged" }
            val received = original.receivedDate?.toInstant()
            check(original.size.toLong() <= limit) { "Message exceeds maxMessageBytes=$limit" }
            val bytes = ByteArrayOutputStream()
            val bounded = object : OutputStream() {
                private var size = 0L
                override fun write(b: Int) { checkSize(1); bytes.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { checkSize(len); bytes.write(b, off, len) }
                private fun checkSize(count: Int) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    if (size + count > limit) throw IOException("Message exceeds maxMessageBytes=$limit")
                    size += count
                }
            }
            bounded.use { original.writeTo(it) }
            val copy = bytes.toByteArray().inputStream().use { MimeMessage(connection.session, it) }
            HistoricalMessage(copy, uid, validity, received)
        }
}
