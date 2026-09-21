package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.client.Folders
import dev.reapermaga.mailkt.model.*

internal class FoldersImpl(private val rt: MailboxRuntime) : Folders {
    override suspend fun list(): List<FolderInfo> =
        rt.retrying("folders.list") { rt.manager.withConnection { it.connection.imap.listFolders() } }

    override suspend fun special(use: SpecialUse): FolderInfo? {
        val all = list()
        return all.firstOrNull { it.specialUse == use }
            ?: if (use == SpecialUse.INBOX) all.firstOrNull { it.path.value.equals("INBOX", ignoreCase = true) } else null
    }

    /** APPEND is not idempotent, so it is attempted once and never retried. */
    override suspend fun append(folder: FolderPath, draft: Draft, flags: MessageFlags): MessageLocation? {
        val built = rt.codec.build(draft)
        return rt.withFolder(folder, readOnly = false) { f ->
            f.append(built.raw, flags)?.let { MessageLocation(rt.id, folder, f.uidValidity, it) }
        }
    }
}
