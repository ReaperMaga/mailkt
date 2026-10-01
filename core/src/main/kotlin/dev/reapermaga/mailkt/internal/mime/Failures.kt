package dev.reapermaga.mailkt.internal.mime

import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.RecoveryReason
import kotlinx.coroutines.CancellationException

/** Cause chain including the throwable itself; cycle-safe and bounded. */
internal fun Throwable.failureChain(maxDepth: Int = 16): Sequence<Throwable> = sequence {
    val seen = java.util.IdentityHashMap<Throwable, Boolean>()
    var current: Throwable? = this@failureChain
    while (current != null && seen.put(current, true) == null && seen.size <= maxDepth) {
        yield(current)
        current = current.cause
        // Jakarta MessagingException exposes nested exceptions via getNextException() - handled by name.
        if (current == null) break
    }
}

internal sealed interface FailureClass {
    /** Owner cancellation; must propagate and never be classified as transport failure. */
    data object Cancelled : FailureClass
    data object Authentication : FailureClass
    data class Recoverable(val reason: RecoveryReason) : FailureClass
    data class Fatal(val error: Throwable) : FailureClass
}

/**
 * Single place deciding whether a failure is authentication, recoverable transport, or fatal.
 * Matches by class name so no Jakarta/Angus import is needed. Never inspects messages for content.
 */
internal object RecoveryClassifier {
    fun classify(t: Throwable): FailureClass {
        if (t is CancellationException) return FailureClass.Cancelled
        if (t is MailException.AuthenticationRequired) return FailureClass.Authentication
        if (t is MailException.ConnectionFailed) return FailureClass.Recoverable(t.recovery)
        val names = t.failureChain().map { it.javaClass.name }.toList()
        fun any(vararg s: String) = names.any { n -> s.any { n.endsWith(it) } }
        return when {
            any("AuthenticationFailedException") -> FailureClass.Authentication
            any("SSLException", "SSLHandshakeException", "SSLProtocolException") ->
                FailureClass.Recoverable(RecoveryReason.TLS)
            any("SocketTimeoutException", "TimeoutCancellationException", "ConnectTimeoutException") ->
                FailureClass.Recoverable(RecoveryReason.TIMEOUT)
            any("StoreClosedException") -> FailureClass.Recoverable(RecoveryReason.STORE_CLOSED)
            any("FolderClosedException") -> FailureClass.Recoverable(RecoveryReason.FOLDER_CLOSED)
            any("SocketException", "ConnectException", "UnknownHostException", "EOFException", "ClosedChannelException") ->
                FailureClass.Recoverable(RecoveryReason.SOCKET)
            any("java.io.IOException") -> FailureClass.Recoverable(RecoveryReason.NETWORK)
            else -> FailureClass.Fatal(t)
        }
    }

    /** Maps a raw failure to a sanitized typed exception. Cancellation is rethrown by callers, not mapped. */
    fun toMailException(t: Throwable): MailException = when (t) {
        is MailException -> t
        else -> when (val c = classify(t)) {
            FailureClass.Authentication -> MailException.AuthenticationRequired(cause = t)
            is FailureClass.Recoverable -> MailException.ConnectionFailed(c.reason, t)
            else -> MailException.Unexpected(t)
        }
    }
}
