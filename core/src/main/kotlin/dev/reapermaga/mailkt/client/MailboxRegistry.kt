package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.MailboxState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/** Outcome of [MailboxRegistry.close]: which mailboxes failed to close, keyed by id. No sensitive data. */
class RegistryCloseReport(val failures: Map<MailboxId, Throwable>) {
    val isClean: Boolean get() = failures.isEmpty()
}

/**
 * Optional helper enforcing identity uniqueness and bulk close. It holds no connection or recovery
 * state; each mailbox still recovers on its own.
 */
class MailboxRegistry {
    private val lock = Any()
    private val mailboxes = LinkedHashMap<MailboxId, Mailbox>()

    /** Registers [mailbox]; throws [MailException.DuplicateMailbox] if a live mailbox has the same id. */
    fun register(mailbox: Mailbox): Mailbox = synchronized(lock) {
        val existing = mailboxes[mailbox.id]
        if (existing != null && existing !== mailbox && existing.state.value != MailboxState.Closed) {
            throw MailException.DuplicateMailbox(mailbox.id)
        }
        mailboxes[mailbox.id] = mailbox
        mailbox
    }

    operator fun get(id: MailboxId): Mailbox? = synchronized(lock) { mailboxes[id] }

    fun unregister(id: MailboxId): Mailbox? = synchronized(lock) { mailboxes.remove(id) }

    fun ids(): Set<MailboxId> = synchronized(lock) { mailboxes.keys.toSet() }

    /** Closes every mailbox concurrently; one failure never prevents the others from closing. */
    suspend fun close(): RegistryCloseReport {
        val all = synchronized(lock) { mailboxes.values.toList().also { mailboxes.clear() } }
        val results = supervisorScope {
            all.map { m ->
                async {
                    try {
                        m.close()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        m.id to e
                    }
                }
            }.awaitAll()
        }
        return RegistryCloseReport(results.filterNotNull().toMap())
    }
}
