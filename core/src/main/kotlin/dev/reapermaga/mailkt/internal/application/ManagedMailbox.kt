package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.client.*
import dev.reapermaga.mailkt.internal.connection.ConnectionManager
import dev.reapermaga.mailkt.internal.transport.MimeCodec
import dev.reapermaga.mailkt.internal.transport.TransportFactory
import dev.reapermaga.mailkt.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The [Mailbox] handed to applications: capability facades over one [ConnectionManager]. */
internal class ManagedMailbox private constructor(
    override val id: MailboxId,
    override val email: MailAddress,
    private val manager: ConnectionManager,
    outboxEnabled: Boolean,
    runtime: MailboxRuntime,
) : Mailbox {
    override val state: StateFlow<MailboxState> get() = manager.state
    override val events: Flow<MailboxEvent> get() = manager.events

    private val messagesImpl = MessagesImpl(runtime)
    override val folders: Folders = FoldersImpl(runtime)
    override val messages: Messages = messagesImpl
    override val conversations: Conversations = ConversationsImpl(runtime, messagesImpl)
    override val outbox: Outbox? = if (outboxEnabled) OutboxImpl(runtime, folders as FoldersImpl) else null

    override suspend fun reconnect() = manager.reconnect()

    override suspend fun close() = manager.close()

    companion object {
        /** Connects once (typed failure, no partial mailbox), registers with an optional registry. */
        suspend fun open(
            id: MailboxId,
            email: MailAddress,
            factory: TransportFactory,
            codec: MimeCodec,
            options: MailboxOptions,
            hasSmtp: Boolean,
        ): Mailbox {
            val manager = ConnectionManager.open(id, factory, options.connectionPolicy)
            val runtime = MailboxRuntime(id, email, manager, codec)
            val mailbox = ManagedMailbox(id, email, manager, options.outboxEnabled && hasSmtp, runtime)
            try {
                options.registry?.register(mailbox)
            } catch (e: Throwable) {
                manager.close()
                throw e
            }
            return mailbox
        }
    }
}
