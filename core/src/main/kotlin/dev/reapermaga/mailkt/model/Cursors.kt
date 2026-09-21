package dev.reapermaga.mailkt.model

/** Current checkpoint format. Bump when serialized semantics change. */
const val CHECKPOINT_FORMAT_VERSION: Int = 1

/**
 * Checkpoints are immutable primitives-only values. MailKT returns them but never stores or
 * acknowledges them: the caller persists them when its processing is durable.
 */
sealed interface Checkpoint {
    val formatVersion: Int
    val mailboxKey: String
    val folder: String
}

/** Continuation of a historical scan (also usable as page cursor). Frozen membership is captured. */
data class ScanCheckpoint(
    override val mailboxKey: String,
    override val folder: String,
    val uidValidity: Long,
    /** Highest UID processed in the scan direction. */
    val lastUid: Long,
    /** Upper UID bound frozen at scan start. */
    val snapshotMaxUid: Long,
    val newestFirst: Boolean = false,
    val processedCount: Long = 0,
    override val formatVersion: Int = CHECKPOINT_FORMAT_VERSION,
) : Checkpoint

/** Watcher position: at-least-once delivery from [lastUid] onward. */
data class WatchCheckpoint(
    override val mailboxKey: String,
    override val folder: String,
    val uidValidity: Long,
    val lastUid: Long,
    override val formatVersion: Int = CHECKPOINT_FORMAT_VERSION,
) : Checkpoint

/** Incremental conversation synchronization position. */
data class ConversationCheckpoint(
    override val mailboxKey: String,
    override val folder: String,
    val uidValidity: Long,
    val lastUid: Long,
    override val formatVersion: Int = CHECKPOINT_FORMAT_VERSION,
) : Checkpoint

/** One page of envelopes. Sparse pages may be empty yet still carry a [next] continuation. */
data class EnvelopePage(
    val envelopes: List<MessageEnvelope>,
    /** Null when the scan is complete. */
    val next: ScanCheckpoint?,
    val scanned: Int,
)
