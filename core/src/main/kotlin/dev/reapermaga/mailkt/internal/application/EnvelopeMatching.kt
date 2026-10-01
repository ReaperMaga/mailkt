package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.model.MessageEnvelope
import dev.reapermaga.mailkt.model.MessageQuery

/** Evaluates a [MessageQuery] against envelope data only; never touches MIME structure or content. */
internal fun MessageEnvelope.matches(q: MessageQuery): Boolean {
    if (q.folder != null && q.folder != location.folder) return false
    if (q.receivedFrom != null && (receivedAt == null || receivedAt < q.receivedFrom)) return false
    if (q.receivedBefore != null && (receivedAt == null || receivedAt >= q.receivedBefore)) return false
    if (q.sentFrom != null && (sentAt == null || sentAt < q.sentFrom)) return false
    if (q.sentBefore != null && (sentAt == null || sentAt >= q.sentBefore)) return false
    if (q.from.isNotEmpty()) {
        val wanted = q.from.map { it.normalized }.toSet()
        if (from.none { it.address.normalized in wanted }) return false
    }
    if (q.to.isNotEmpty()) {
        val wanted = q.to.map { it.normalized }.toSet()
        if ((to + cc).none { it.address.normalized in wanted }) return false
    }
    if (q.subjectContains != null && subject?.contains(q.subjectContains, ignoreCase = true) != true) return false
    if (q.seen != null && flags.seen != q.seen) return false
    if (q.flagged != null && flags.flagged != q.flagged) return false
    if (!flags.keywords.containsAll(q.requiredFlags)) return false
    for ((name, value) in q.headerEquals) {
        val values = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
        if (values.none { it.trim() == value.trim() }) return false
    }
    if (q.threadIds.isNotEmpty() && threadKeys().none { it in q.threadIds } && threadId !in q.threadIds) return false
    // Unknown advertised size never excludes a message; the download limit still applies.
    if (q.minSize != null && advertisedSize != null && advertisedSize < q.minSize) return false
    if (q.maxSize != null && advertisedSize != null && advertisedSize > q.maxSize) return false
    return true
}

/** Message-ID plus reply relationships; the only basis of conversation grouping. */
internal fun MessageEnvelope.threadKeys(): Set<String> =
    buildSet {
        messageId?.let { add(it) }
        inReplyTo?.let { add(it) }
        addAll(references)
    }
