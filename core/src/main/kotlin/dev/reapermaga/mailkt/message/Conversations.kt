package dev.reapermaga.mailkt.message

import dev.reapermaga.mailkt.folder.*
import dev.reapermaga.mailkt.session.*
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.OutputStream
import java.time.Instant

data class MessageLocation(val accountId: String, val folderName: String, val uidValidity: Long, val uid: Long)

/** One logical message with every mailbox copy retained for flags and synchronization. */
data class ConversationMessage(val copies: List<LocatedMessage>) {
    val message: MimeMessage get() = copies.first().item.message
    val locations: List<MessageLocation> get() = copies.map { it.location }
    val timestamp: Instant? get() = message.sentDate?.toInstant() ?: copies.first().item.receivedAt
    val unread: Boolean get() = copies.any { !it.item.message.isSet(Flags.Flag.SEEN) }
}

data class LocatedMessage(val location: MessageLocation, val item: HistoricalMessage)
data class EmailConversation(val id: String, val messages: List<ConversationMessage>) {
    /** Includes referenced ancestors even when their bodies have not been loaded yet. */
    val relatedMessageIds: Set<String> get() = messages.flatMap { messageThreadIds(it.message) }.toSet()
}
data class ConversationFolderSnapshot(val folderName: String, val uidValidity: Long, val upperUid: Long,
    val olderHistoryAvailable: Boolean, val lowerUid: Long = 1)
data class ConversationHistory(val threads: List<EmailConversation>, val folders: List<ConversationFolderSnapshot>)

data class ConversationReadOptions(
    val maxUidPositionsPerFolder: Int = 2000,
    val maxMatchedMessages: Int = 500,
    val maxMessageBytes: Long = 25L * 1024 * 1024,
    val maxTotalBytes: Long = 50L * 1024 * 1024,
    /** Exclusive per-folder boundary for progressive older history loading. */
    val beforeUidByFolder: Map<String, Long> = emptyMap(),
) {
    init {
        require(maxUidPositionsPerFolder > 0 && maxMatchedMessages > 0)
        require(maxMessageBytes in 1..Int.MAX_VALUE.toLong() && maxTotalBytes > 0)
        require(beforeUidByFolder.all { (folder, uid) -> folder.isNotBlank() && uid > 0 })
    }
}

private val idPattern = Regex("<[^<>\\s]+>")
internal fun messageThreadIds(message: Message): Set<String> =
    listOf("Message-ID", "In-Reply-To", "References").flatMap { name ->
        message.getHeader(name).orEmpty().flatMap { idPattern.findAll(it).map { match -> match.value }.toList() }
    }.toSet()

/**
 * Groups by explicit reply relationships, never by subject or email domain. Copies sharing a
 * Message-ID are merged only when their sender, recipients, subject, date and bodies agree.
 * Missing IDs remain separate messages. Thread IDs may change when older ancestors are added.
 * Can also merge persisted history with subsequent reads; pass existing and new located messages.
 */
fun assembleConversations(messages: List<LocatedMessage>): List<EmailConversation> {
    val unique = messages.associateBy { it.location }.values.toList()
    val parents = mutableMapOf<String, String>()
    fun root(key: String): String {
        val parent = parents.getOrPut(key) { key }
        if (parent == key) return key
        return root(parent).also { parents[key] = it }
    }
    fun unite(a: String, b: String) {
        val left = root(a); val right = root(b)
        if (left != right) parents[maxOf(left, right)] = minOf(left, right)
    }
    fun key(item: LocatedMessage) = "${item.location.accountId}:" +
        (messageThreadIds(item.item.message).sorted().firstOrNull() ?: "uid:${item.location}")
    unique.forEach { item ->
        val ids = messageThreadIds(item.item.message).map { "${item.location.accountId}:$it" }
        ids.forEach { unite(key(item), it) }
    }
    // Provider-added Received/Delivered-To headers and differing flags do not split identical copies.
    fun fingerprint(message: MimeMessage): List<Any?> = listOf(
        message.messageID, message.from?.map { it.toString() }?.sorted(),
        listOf(Message.RecipientType.TO, Message.RecipientType.CC).map { type ->
            message.getRecipients(type)?.map { it.toString() }?.sorted()
        }, message.subject, message.sentDate, message.getHeader("In-Reply-To", " "),
        message.getHeader("References", " "), message.contentType,
        message.inputStream.use { stream -> java.security.MessageDigest.getInstance("SHA-256").let { digest ->
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().toList()
        } },
    )
    return unique.groupBy { root(key(it)) }.map { (threadId, rows) ->
        val logical = rows.groupBy { item ->
            if (item.item.message.messageID == null) listOf(item.location)
            else listOf(item.location.accountId, fingerprint(item.item.message))
        }.values.map { ConversationMessage(it) }
            .sortedWith(compareBy<ConversationMessage> { it.timestamp ?: Instant.EPOCH }.thenBy { it.locations.first().toString() })
        EmailConversation(threadId, logical)
    }.sortedWith(compareBy<EmailConversation> { it.messages.lastOrNull()?.timestamp ?: Instant.EPOCH }.thenBy { it.id })
}

internal data class ConversationEnvelope(val location: MessageLocation, val participants: Set<String>, val ids: Set<String>)
internal data class ConversationIndex(val envelopes: List<ConversationEnvelope>, val snapshots: List<ConversationFolderSnapshot>)
internal interface ConversationTransport {
    suspend fun index(session: MailSession, folders: List<String>, options: ConversationReadOptions): ConversationIndex
    suspend fun download(session: MailSession, location: MessageLocation, maxBytes: Long): HistoricalMessage
}

/**
 * Finds contact messages AND all transitively related ancestors/replies within the bounded history
 * window, across caller-selected folders (normally Inbox, Sent and any relevant archive folders).
 * Only envelopes are indexed for unrelated mail; bodies are downloaded only after thread discovery.
 * olderHistoryAvailable explicitly signals that this is not the entire mailbox. Increase the
 * window to include older history. Missing folders/provider failures/limit violations throw; no
 * partial success is returned. Folder snapshots are NOT whole-mailbox sync checkpoints when truncated.
 */
suspend fun readConversations(
    session: MailSession,
    folderNames: List<String>,
    contacts: Set<String>,
    threadMessageIds: Set<String> = emptySet(),
    options: ConversationReadOptions = ConversationReadOptions(),
): ConversationHistory = readConversations(session, folderNames, contacts, threadMessageIds, options, ImapConversationTransport)

suspend fun readConversations(
    session: ManagedMailSession,
    folderNames: List<String>,
    contacts: Set<String>,
    threadMessageIds: Set<String> = emptySet(),
    options: ConversationReadOptions = ConversationReadOptions(),
): ConversationHistory = readConversations(session.session, folderNames, contacts, threadMessageIds, options)

internal suspend fun readConversations(session: MailSession, folderNames: List<String>, contacts: Set<String>,
    threadMessageIds: Set<String>, options: ConversationReadOptions, transport: ConversationTransport): ConversationHistory {
    require(folderNames.isNotEmpty() && folderNames.all { it.isNotBlank() })
    require(contacts.isNotEmpty() || threadMessageIds.isNotEmpty()) { "A contact address or thread ID is required" }
    MessageFilter(contacts, threadMessageIds) // Validate without allowing an accidental unfiltered scan.
    val addresses = contacts.map { InternetAddress(it, true).address.lowercase(java.util.Locale.ROOT) }.toSet()
    val index = transport.index(session, folderNames.distinct(), options)
    val ids = threadMessageIds.toMutableSet()
    val selected = linkedSetOf<ConversationEnvelope>()
    // Fixed point, independent of folder order or direction of replies.
    do {
        var changed = false
        index.envelopes.forEach { envelope ->
            if (envelope !in selected && (envelope.participants.any { it in addresses } || envelope.ids.any { it in ids })) {
                selected += envelope
                ids += envelope.ids
                changed = true
            }
        }
    } while (changed)
    check(selected.size <= options.maxMatchedMessages) { "Conversation message limit exceeded; narrow history window" }
    var bytes = 0L
    val messages = selected.map { envelope ->
        val limit = minOf(options.maxMessageBytes, options.maxTotalBytes - bytes)
        check(limit > 0) { "Conversation byte limit exceeded; narrow history window" }
        val item = transport.download(session, envelope.location, limit)
        item.message.writeTo(object : OutputStream() {
            override fun write(value: Int) { bytes++; check(bytes <= options.maxTotalBytes) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes += length; check(bytes <= options.maxTotalBytes) }
        })
        check(item.uid == envelope.location.uid && item.uidValidity == envelope.location.uidValidity)
        LocatedMessage(envelope.location, item)
    }
    return ConversationHistory(assembleConversations(messages), index.snapshots)
}

private object ImapConversationTransport : ConversationTransport {
    override suspend fun index(session: MailSession, folders: List<String>, options: ConversationReadOptions): ConversationIndex =
        runInterruptible(Dispatchers.IO) {
            val connection = checkNotNull(session.currentConnection) { "Mail session is not connected" }
            val envelopes = mutableListOf<ConversationEnvelope>()
            val snapshots = folders.map { name ->
                val folder = connection.store.getFolder(name)
                try {
                    folder.open(Folder.READ_ONLY)
                    val uids = folder as? UIDFolder ?: error("Folder does not support UIDs")
                    check((folder as? org.eclipse.angus.mail.imap.IMAPFolder)?.uidNotSticky != true)
                    val validity = uids.uidValidity
                    val serverUpper = uids.uidNext - 1
                    check(validity > 0 && serverUpper >= 0)
                    val upper = minOf(serverUpper, options.beforeUidByFolder[name]?.minus(1) ?: serverUpper)
                    val lower = maxOf(1, upper - options.maxUidPositionsPerFolder + 1)
                    var start = lower
                    while (start <= upper) {
                        val end = minOf(upper, start + 99)
                        val rows = uids.getMessagesByUID(start, end).filterNotNull().toTypedArray()
                        if (rows.isNotEmpty()) folder.fetch(rows, FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE); add(UIDFolder.FetchProfileItem.UID)
                            add("Message-ID"); add("In-Reply-To"); add("References")
                        })
                        rows.forEach { message ->
                            val participants = (message.from.orEmpty().toList() + message.allRecipients.orEmpty().toList())
                                .mapNotNull { (it as? InternetAddress)?.address?.lowercase(java.util.Locale.ROOT) }.toSet()
                            envelopes += ConversationEnvelope(MessageLocation(session.id, name, validity, uids.getUID(message)),
                                participants, messageThreadIds(message))
                        }
                        start = end + 1
                    }
                    check(uids.uidValidity == validity) { "UIDVALIDITY changed during conversation indexing" }
                    ConversationFolderSnapshot(name, validity, upper, lower > 1, lower)
                } finally { runCatching { if (folder.isOpen) folder.close(false) } }
            }
            ConversationIndex(envelopes, snapshots)
        }

    override suspend fun download(session: MailSession, location: MessageLocation, maxBytes: Long): HistoricalMessage {
        val page = readMessagePage(session, location.folderName, cursor = MessagePageCursor(session.id,
            location.folderName, location.uidValidity, location.uid, location.uid, true),
            maxMessageBytes = maxBytes, maxPageBytes = maxBytes)
        return page.messages.singleOrNull() ?: error("Conversation message expunged during download")
    }
}
