package dev.reapermaga.mailkt.folder

import dev.reapermaga.mailkt.session.MailSession
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.util.Locale

/** Exact contact addresses OR explicit RFC Message-ID relationships. No domain/subject matching. */
data class MessageFilter(
    val addresses: Set<String> = emptySet(),
    val threadMessageIds: Set<String> = emptySet(),
) {
    init {
        addresses.forEach {
            require('\r' !in it && '\n' !in it)
            InternetAddress(it, true).validate()
        }
        threadMessageIds.forEach { require(it.matches(Regex("<[^<>\\s]+>"))) }
    }

    fun matches(message: Message): Boolean {
        if (addresses.isEmpty() && threadMessageIds.isEmpty()) return true
        val wanted = addresses.map { InternetAddress(it, true).address.lowercase(Locale.ROOT) }.toSet()
        val participants = message.from.orEmpty().toList() + message.allRecipients.orEmpty().toList()
        if (participants.any { (it as? InternetAddress)?.address?.lowercase(Locale.ROOT) in wanted }) return true
        return listOf("Message-ID", "In-Reply-To", "References").any { name ->
            message.getHeader(name).orEmpty().any { value ->
                Regex("<[^<>\\s]+>").findAll(value).any { it.value in threadMessageIds }
            }
        }
    }
}

/** Folder/account scoped cursor; persist UIDVALIDITY alongside any synchronization checkpoint. */
data class MessagePageCursor(
    val sessionId: String,
    val folderName: String,
    val uidValidity: Long,
    val lowerUid: Long,
    val upperUid: Long,
    val newestFirst: Boolean,
    val snapshotUpperUid: Long = upperUid,
)

data class MessagePage(
    val messages: List<HistoricalMessage>,
    val nextCursor: MessagePageCursor?,
    val uidValidity: Long,
    /** Snapshot boundary, safe as a checkpoint only after every page has been processed. */
    val snapshotUpperUid: Long,
)

/**
 * Downloads at most [uidWindowSize] UIDs per call, including ordinary messages and sent mail.
 * A sparse/filtered page can be empty with a non-null cursor: continue until nextCursor is null.
 * Initial history is newest-first; [afterUid] selects incremental arrivals oldest-first.
 * Pass [expectedUidValidity] with a persisted checkpoint; changes fail rather than silently skip mail.
 * Cursors fix the snapshot boundary; new arrivals are read in the next incremental scan.
 * MIME copies are detached; HTML and remote resources are deliberately not rendered here.
 */
suspend fun readMessagePage(
    session: MailSession,
    folderName: String,
    filter: MessageFilter = MessageFilter(),
    cursor: MessagePageCursor? = null,
    afterUid: Long? = null,
    expectedUidValidity: Long? = null,
    uidWindowSize: Int = 100,
    maxMessageBytes: Long = 25L * 1024 * 1024,
    maxPageBytes: Long = 50L * 1024 * 1024,
): MessagePage {
    require(folderName.isNotBlank())
    require(uidWindowSize in 1..500)
    require(maxMessageBytes in 1..Int.MAX_VALUE.toLong())
    require(maxPageBytes > 0)
    require(afterUid == null || afterUid in 0 until Long.MAX_VALUE)
    require(cursor == null || afterUid == null)
    require(afterUid == null || expectedUidValidity != null) { "Incremental reads require UIDVALIDITY" }
    cursor?.let {
        require(it.sessionId == session.id && it.folderName == folderName) { "Cursor belongs to another mailbox" }
        require(it.lowerUid > 0 && it.upperUid >= it.lowerUid && it.upperUid < Long.MAX_VALUE)
        require(it.snapshotUpperUid >= it.upperUid)
    }
    return runInterruptible(Dispatchers.IO) {
        val connection = checkNotNull(session.currentConnection) { "Mail session is not connected" }
        val folder = connection.store.getFolder(folderName)
        try {
            folder.open(Folder.READ_ONLY)
            val uidFolder = folder as? UIDFolder ?: error("Folder does not support UIDs")
            check((folder as? org.eclipse.angus.mail.imap.IMAPFolder)?.uidNotSticky != true) { "Folder does not provide persistent UIDs" }
            val validity = uidFolder.uidValidity
            check(validity > 0)
            check((cursor?.uidValidity ?: expectedUidValidity ?: validity) == validity) { "UIDVALIDITY changed; resynchronize folder" }
            if (expectedUidValidity != null) check(expectedUidValidity == validity) { "UIDVALIDITY changed" }
            // UIDNEXT includes expunged positions, so empty windows still make bounded progress.
            val upper = cursor?.upperUid ?: (uidFolder.uidNext - 1).also { check(it >= 0) { "UIDNEXT unavailable" } }
            val snapshotUpper = cursor?.snapshotUpperUid ?: upper
            val lower = cursor?.lowerUid ?: ((afterUid ?: 0) + 1)
            val newest = cursor?.newestFirst ?: (afterUid == null)
            if (lower > upper) return@runInterruptible MessagePage(emptyList(), null, validity, snapshotUpper)
            val start = if (newest) maxOf(lower, upper - uidWindowSize + 1) else lower
            val end = if (newest) upper else minOf(upper, lower + uidWindowSize - 1)
            val candidates = uidFolder.getMessagesByUID(start, end).filterNotNull().toTypedArray()
            if (candidates.isNotEmpty()) folder.fetch(candidates, FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(UIDFolder.FetchProfileItem.UID)
                add("Message-ID")
                add("In-Reply-To")
                add("References")
            })
            val ordered = candidates.sortedBy { uidFolder.getUID(it) }.let { if (newest) it.asReversed() else it }
            var pageBytes = 0L
            val messages = ordered.filter { filter.matches(it) }.map { source ->
                check(!source.isExpunged) { "Message expunged during download" }
                val bytes = ByteArrayOutputStream()
                val byteLimit = minOf(maxMessageBytes, maxPageBytes - pageBytes)
                val limited = object : FilterOutputStream(bytes) {
                    var count = 0L
                    override fun write(value: Int) {
                        check(count < byteLimit) { "Message/page exceeds byte limit; reduce uidWindowSize" }
                        out.write(value); count++
                    }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        check(count + length <= byteLimit) { "Message/page exceeds byte limit; reduce uidWindowSize" }
                        out.write(buffer, offset, length); count += length
                    }
                }
                source.writeTo(limited)
                pageBytes += bytes.size()
                val detached = MimeMessage(connection.session, ByteArrayInputStream(bytes.toByteArray()))
                detached.setFlags(source.flags, true)
                HistoricalMessage(detached,
                    uidFolder.getUID(source), validity, source.receivedDate?.toInstant())
            }
            val nextLower = if (newest) lower else end + 1
            check(uidFolder.uidValidity == validity) { "UIDVALIDITY changed during download; resynchronize folder" }
            val nextUpper = if (newest) start - 1 else upper
            val next = if (nextLower <= nextUpper) MessagePageCursor(session.id, folderName, validity,
                nextLower, nextUpper, newest, snapshotUpper) else null
            MessagePage(messages, next, validity, snapshotUpper)
        } finally {
            runCatching { if (folder.isOpen) folder.close(false) }
        }
    }
}

data class MailFolderInfo(val name: String, val holdsMessages: Boolean, val attributes: Set<String>)

/** A disconnected/reconnecting read fails explicitly; the caller can repeat the same cursor. */
suspend fun readMessagePage(
    session: dev.reapermaga.mailkt.session.ManagedMailSession,
    folderName: String,
    filter: MessageFilter = MessageFilter(),
    cursor: MessagePageCursor? = null,
    afterUid: Long? = null,
    expectedUidValidity: Long? = null,
    uidWindowSize: Int = 100,
    maxMessageBytes: Long = 25L * 1024 * 1024,
    maxPageBytes: Long = 50L * 1024 * 1024,
): MessagePage = readMessagePage(session.session, folderName, filter, cursor, afterUid,
    expectedUidValidity, uidWindowSize, maxMessageBytes, maxPageBytes)

/** IMAP SPECIAL-USE attributes identify Sent folders without assuming localized folder names. */
suspend fun listMailFolders(session: MailSession): List<MailFolderInfo> = runInterruptible(Dispatchers.IO) {
    checkNotNull(session.currentConnection).store.defaultFolder.list("*").map { folder ->
        MailFolderInfo(folder.fullName, folder.type and Folder.HOLDS_MESSAGES != 0,
            (folder as? org.eclipse.angus.mail.imap.IMAPFolder)?.attributes?.toSet().orEmpty())
    }
}
