package dev.reapermaga.mailkt.folder

import jakarta.mail.Folder
import jakarta.mail.FolderClosedException

internal interface TerminalFolderFailure

/**
 * Angus can expose a folder-close race as [IllegalStateException] while reading a message body.
 * Only normalize when the owning folder can still be observed and is already closed.
 */
internal fun normalizeClosedFolderFailure(folder: Folder?, failure: Throwable): Throwable {
    if (failure !is IllegalStateException || failure is TerminalFolderFailure || folder == null) {
        return failure
    }
    val observablyClosed = try {
        !folder.isOpen
    } catch (_: Exception) {
        false
    }
    return if (observablyClosed) {
        FolderClosedException(folder, failure.message, failure)
    } else {
        failure
    }
}
