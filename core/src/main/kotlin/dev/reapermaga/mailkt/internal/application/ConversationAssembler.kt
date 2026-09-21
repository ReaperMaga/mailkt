package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.model.*
import java.time.Instant

/**
 * Groups envelopes by explicit reply relationships (Message-ID, In-Reply-To, References), never by
 * subject or domain. Copies of the same message (same Message-ID and identical envelope
 * fingerprint, e.g. Inbox and All Mail) merge into one logical message; messages without IDs stay
 * separate. Pure function: no I/O.
 */
internal object ConversationAssembler {
    fun assemble(envelopes: List<MessageEnvelope>): List<Conversation> {
        val unique = envelopes.distinctBy { it.location }
        val parents = HashMap<String, String>()
        fun root(k: String): String {
            val p = parents.getOrPut(k) { k }
            if (p == k) return k
            return root(p).also { parents[k] = it }
        }
        fun unite(a: String, b: String) {
            val l = root(a)
            val r = root(b)
            if (l != r) parents[maxOf(l, r)] = minOf(l, r)
        }
        fun key(e: MessageEnvelope) = e.threadKeys().minOrNull() ?: "uid:${e.location.folder.value}:${e.location.uid}"
        unique.forEach { e -> e.threadKeys().forEach { unite(key(e), it) } }

        return unique.groupBy { root(key(it)) }.map { (id, rows) ->
            val logical = rows.groupBy { if (it.messageId == null) listOf(it.location) else fingerprint(it) }
                .values.map { copies -> copies.minWith(COPY_ORDER) }
                .sortedWith(compareBy<MessageEnvelope> { it.timestamp() ?: Instant.EPOCH }.thenBy { it.location.folder.value }.thenBy { it.location.uid })
            Conversation(
                id = id,
                subject = logical.firstNotNullOfOrNull { it.subject },
                messages = logical,
                participants = logical.flatMap { it.from + it.to + it.cc }.map { MailAddress(it.address.normalized) }.toSet(),
                lastActivity = logical.mapNotNull { it.timestamp() }.maxOrNull(),
            )
        }.sortedWith(compareBy<Conversation> { it.lastActivity ?: Instant.EPOCH }.thenBy { it.id })
    }

    /** Server flags and provider-added headers differ between copies and never split them. */
    private fun fingerprint(e: MessageEnvelope): List<Any?> = listOf(
        e.messageId, e.from.map { it.address.normalized }.sorted(), e.to.map { it.address.normalized }.sorted(),
        e.cc.map { it.address.normalized }.sorted(), e.subject, e.sentAt, e.inReplyTo, e.references, e.contentType,
    )

    private val COPY_ORDER = compareBy<MessageEnvelope>({ it.location.folder.value }, { it.location.uid })
}

internal fun MessageEnvelope.timestamp(): Instant? = sentAt ?: receivedAt

/** Expands [seed] transitively through related envelopes of [pool] (fixed point, order independent). */
internal fun relatedTo(seed: Collection<MessageEnvelope>, pool: List<MessageEnvelope>): Set<MessageEnvelope> {
    val ids = seed.flatMap { it.threadKeys() }.toMutableSet()
    val selected = LinkedHashSet<MessageEnvelope>(seed)
    do {
        var changed = false
        for (e in pool) {
            if (e !in selected && e.threadKeys().any { it in ids }) {
                selected += e
                ids += e.threadKeys()
                changed = true
            }
        }
    } while (changed)
    return selected
}
