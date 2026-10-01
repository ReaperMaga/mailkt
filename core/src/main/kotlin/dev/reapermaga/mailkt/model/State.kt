package dev.reapermaga.mailkt.model

/** Why a mailbox is recovering. Never carries provider exception text. */
enum class RecoveryReason {
    NETWORK,
    SOCKET,
    TLS,
    TIMEOUT,
    STORE_CLOSED,
    FOLDER_CLOSED,
    HEALTH_CHECK,
    REQUESTED,
}

/** Authoritative lifecycle snapshot of a mailbox. */
sealed interface MailboxState {
    data object Connected : MailboxState
    data class Reconnecting(val attempt: Int, val reason: RecoveryReason) : MailboxState
    data object AuthenticationRequired : MailboxState
    data class Failed(val cause: MailException, val recoverable: Boolean) : MailboxState
    data object Closed : MailboxState
}

/** Bounded, best-effort diagnostic events. Never needed to reconstruct [MailboxState]. */
sealed interface MailboxEvent {
    val at: java.time.Instant

    data class StateChanged(val state: MailboxState, override val at: java.time.Instant) : MailboxEvent
    data class Reconnected(val generation: Long, override val at: java.time.Instant) : MailboxEvent
    data class RecoveryStarted(
        val reason: RecoveryReason,
        val generation: Long,
        override val at: java.time.Instant,
    ) : MailboxEvent
    data class WatcherRestarted(val folder: FolderPath, override val at: java.time.Instant) : MailboxEvent
    data class EventsDropped(val count: Long, override val at: java.time.Instant) : MailboxEvent
}
