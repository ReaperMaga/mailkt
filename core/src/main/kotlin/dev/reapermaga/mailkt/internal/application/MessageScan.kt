package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.model.*

/** Membership of a scan frozen at start: UIDs in delivery order plus the validity they belong to. */
internal class FrozenScan(val validity: Long, val snapshotMaxUid: Long, val uids: List<Long>)

/**
 * Phase 1 of every read: freeze UID membership (bounded by the snapshot UID so later arrivals never
 * shift a resumed scan). Phase 2 fetches envelope batches from that frozen list.
 */
internal suspend fun MailboxRuntime.freeze(selection: MessageSelection): FrozenScan {
    val cp = selection.checkpoint
    if (cp != null) {
        Checkpoints.validate(cp, key, selection.folder)
        if (cp.newestFirst != selection.newestFirst) throw MailException.InvalidCheckpoint("Checkpoint direction mismatch")
    }
    return retrying("scan.freeze") {
        withFolder(selection.folder) { f ->
            if (cp != null) Checkpoints.uidValidity(cp.uidValidity, f.uidValidity)
            val max = cp?.snapshotMaxUid ?: (f.uidNext - 1)
            var uids = f.search(selection.range, selection.query).filter { it <= max }.sorted()
            if (selection.newestFirst) uids = uids.asReversed()
            if (cp != null) uids = uids.filter { if (selection.newestFirst) it < cp.lastUid else it > cp.lastUid }
            FrozenScan(f.uidValidity, max, uids)
        }
    }
}

/** Fetches envelopes for [uids] in the given order; vanished (expunged) UIDs are omitted. */
internal suspend fun MailboxRuntime.fetchEnvelopes(folder: FolderPath, validity: Long, uids: List<Long>): List<MessageEnvelope> {
    if (uids.isEmpty()) return emptyList()
    val fetched = retrying("scan.envelopes") {
        withFolder(folder) { f ->
            Checkpoints.uidValidity(validity, f.uidValidity)
            f.fetchEnvelopes(uids)
        }
    }
    val order = uids.withIndex().associate { it.value to it.index }
    return fetched.sortedBy { order[it.location.uid] ?: Int.MAX_VALUE }
}

internal fun MailboxRuntime.scanCheckpoint(
    selection: MessageSelection,
    scan: FrozenScan,
    lastUid: Long,
    processed: Long,
) = ScanCheckpoint(
    mailboxKey = key,
    folder = selection.folder.value,
    uidValidity = scan.validity,
    lastUid = lastUid,
    snapshotMaxUid = scan.snapshotMaxUid,
    newestFirst = selection.newestFirst,
    processedCount = processed,
)
