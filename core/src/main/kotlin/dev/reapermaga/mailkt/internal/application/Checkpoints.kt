package dev.reapermaga.mailkt.internal.application

import dev.reapermaga.mailkt.model.*

/** Validates caller-supplied checkpoints. Checkpoints are only ever returned, never stored, by MailKT. */
internal object Checkpoints {
    fun validate(cp: Checkpoint, key: String, folder: FolderPath) {
        if (cp.formatVersion != CHECKPOINT_FORMAT_VERSION) throw MailException.InvalidCheckpoint("Unsupported checkpoint version")
        if (cp.mailboxKey != key || cp.folder != folder.value) throw MailException.InvalidCheckpoint("Checkpoint belongs to another mailbox or folder")
    }

    fun uidValidity(expected: Long, actual: Long) {
        if (expected != actual) throw MailException.IntegrityViolation(IntegrityKind.UID_VALIDITY_CHANGED)
    }
}
