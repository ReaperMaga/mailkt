package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MessageQuery
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.search.*
import java.util.Date

/**
 * Server-evaluable subset of a query (addresses, subject, flags). Date, header, size and thread
 * predicates are always re-checked client-side on the fetched envelope.
 */
internal object AngusSearch {
    fun term(q: MessageQuery): SearchTerm? {
        val terms = mutableListOf<SearchTerm>()
        any(q.from) { FromStringTerm(it.normalized) }?.let { terms += it }
        any(q.to) { RecipientStringTerm(Message.RecipientType.TO, it.normalized) }?.let { terms += it }
        q.subjectContains?.let { terms += SubjectTerm(it) }
        q.seen?.let { terms += FlagTerm(Flags(Flags.Flag.SEEN), it) }
        q.flagged?.let { terms += FlagTerm(Flags(Flags.Flag.FLAGGED), it) }
        return when (terms.size) {
            0 -> null
            1 -> terms.single()
            else -> AndTerm(terms.toTypedArray())
        }
    }

    private fun any(addresses: Set<MailAddress>, make: (MailAddress) -> SearchTerm): SearchTerm? {
        val terms = addresses.map(make)
        return when (terms.size) {
            0 -> null
            1 -> terms.single()
            else -> OrTerm(terms.toTypedArray())
        }
    }

    /** Superset of a received-date lower bound (IMAP SINCE has day granularity). */
    fun receivedSince(from: Date): SearchTerm = ReceivedDateTerm(ComparisonTerm.GE, from)
}
