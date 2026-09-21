package dev.reapermaga.mailkt.model

/**
 * Root of all typed MailKT failures. Messages are sanitized: they never contain credentials,
 * message content, addresses or provider-supplied text.
 */
sealed class MailException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Credentials rejected or refresh impossible; the application must run authorization again. */
    class AuthenticationRequired(message: String = "Authentication required", cause: Throwable? = null) :
        MailException(message, cause)

    /** Authorization callback validation failed (state, expiry, redirect, PKCE, account mismatch...). */
    class AuthorizationFailed(val reason: AuthorizationFailure, cause: Throwable? = null) :
        MailException("Authorization failed: $reason", cause)

    /** Connecting or transport failure. */
    class ConnectionFailed(val recovery: RecoveryReason, cause: Throwable? = null) :
        MailException("Connection failed: $recovery", cause)

    /** The mailbox is closed or closing. */
    class MailboxClosed(message: String = "Mailbox is closed") : MailException(message)

    /** The mailbox is not currently connected (reconnecting or failed). */
    class NotConnected(val state: MailboxState) : MailException("Mailbox not connected")

    /** Folder does not exist or is not selectable. */
    class FolderNotFound(val folder: FolderPath) : MailException("Folder not found")

    /** Message/part expunged or otherwise gone. */
    class MessageUnavailable(val location: MessageLocation) : MailException("Message unavailable")

    /** UIDVALIDITY changed or a MIME part changed between selection and download. */
    class IntegrityViolation(val kind: IntegrityKind, cause: Throwable? = null) :
        MailException("Integrity violation: $kind", cause)

    /** A byte or count limit was exceeded. */
    class LimitExceeded(val limitBytes: Long) : MailException("Limit of $limitBytes bytes exceeded")

    /** Message could not be parsed or built. */
    class MalformedMessage(message: String = "Malformed message", cause: Throwable? = null) :
        MailException(message, cause)

    /** A checkpoint had an unsupported version or does not belong to this mailbox/folder. */
    class InvalidCheckpoint(message: String = "Invalid checkpoint") : MailException(message)

    /** Outbox is not configured for this mailbox. */
    class OutboxUnavailable : MailException("Outbox unavailable")

    /** A mailbox with the same id is already registered. */
    class DuplicateMailbox(val id: MailboxId) : MailException("Mailbox already open")

    /** Unclassified failure. */
    class Unexpected(cause: Throwable? = null) :
        MailException("Unexpected failure: ${cause?.javaClass?.simpleName ?: "unknown"}", cause)
}

enum class IntegrityKind { UID_VALIDITY_CHANGED, MESSAGE_EXPUNGED, PART_CHANGED, MEMBERSHIP_CHANGED }

enum class AuthorizationFailure {
    UNKNOWN_STATE,
    EXPIRED,
    REDIRECT_NOT_ALLOWED,
    PROVIDER_ERROR,
    EXCHANGE_FAILED,
    WRONG_ACCOUNT,
    STORE_FAILED,
}
