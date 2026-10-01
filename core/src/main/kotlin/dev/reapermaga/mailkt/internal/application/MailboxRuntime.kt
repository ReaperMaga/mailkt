package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.internal.connection.ConnectionManager
import dev.reapermaga.mailkt.internal.connection.Delayer
import dev.reapermaga.mailkt.internal.mime.LogCategory
import dev.reapermaga.mailkt.internal.mime.MailLog
import dev.reapermaga.mailkt.internal.transport.ImapFolderPort
import dev.reapermaga.mailkt.internal.transport.MimeCodec
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Tunables of feature-level retry; connection recovery itself lives in the [ConnectionManager]. */
internal data class RetryPolicy(
    val maxAttempts: Int = 5,
    val retryDelayMillis: Long = 100,
    val recoveryTimeoutMillis: Long = 120_000,
    /** Longest single IDLE wait before the watcher re-checks the folder. */
    val idleTimeoutMillis: Long = 9 * 60_000L,
)

/**
 * Everything application features share for one mailbox: connection access pinned to a generation,
 * scoped folder access and the feature-owned retry loop. Holds no global state.
 */
internal class MailboxRuntime(
    val id: MailboxId,
    val email: MailAddress,
    val manager: ConnectionManager,
    val codec: MimeCodec,
    val delayer: Delayer = Delayer.System,
    val retry: RetryPolicy = RetryPolicy(),
) {
    val key: String get() = id.storageKey
    val log = MailLog.forMailbox(LogCategory.IMAP, id)

    /** One attempt with a scoped folder; failures are classified and reported by the manager. */
    suspend fun <T> withFolder(path: FolderPath, readOnly: Boolean = true, block: suspend (ImapFolderPort) -> T): T =
        manager.withConnection { lease -> lease.connection.imap.withFolder(path, readOnly, block) }

    /**
     * Repeats an idempotent read after connection failures, waiting for the replacement generation.
     * Never wrap SMTP submission, APPEND or consumer code in this.
     */
    suspend fun <T> retrying(operation: String, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MailException) {
                if (!isTransient(e) || ++attempt >= retry.maxAttempts) throw e
                log.debug("retry", "attempt" to attempt, "reason" to (e as? MailException.ConnectionFailed)?.recovery)
                val ready = withTimeoutOrNull(retry.recoveryTimeoutMillis) { awaitConnected(); true }
                if (ready != true) throw e
            }
        }
    }

    /** Suspends until the mailbox is connected again; throws when it is closed or unrecoverable. */
    suspend fun awaitConnected() {
        delayer.delay(retry.retryDelayMillis)
        when (val s = manager.state.first { it.isSettled() }) {
            MailboxState.Closed -> throw MailException.MailboxClosed()
            is MailboxState.Failed -> throw s.cause
            else -> Unit
        }
    }

    /** Worth waiting for: a connection generation failed or recovery is in progress. */
    fun isTransient(e: MailException): Boolean =
        e is MailException.ConnectionFailed ||
            (e is MailException.NotConnected && (e.state is MailboxState.Reconnecting || e.state is MailboxState.Connected))

    /** Any loss of connection, including waiting for reauthorization; used by long-lived watchers. */
    fun isConnectionLoss(e: MailException): Boolean =
        e is MailException.ConnectionFailed || e is MailException.NotConnected

    private fun MailboxState.isSettled(): Boolean =
        this is MailboxState.Connected || this is MailboxState.Closed || (this is MailboxState.Failed && !recoverable)

    fun requireOwn(location: MessageLocation) {
        if (location.mailboxId != id) throw MailException.MessageUnavailable(location)
    }
}
