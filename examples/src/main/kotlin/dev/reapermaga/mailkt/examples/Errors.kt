package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.model.FolderPath
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MessageLocation
import dev.reapermaga.mailkt.model.MessageSelection

// region errors
suspend fun readSafely(mailbox: Mailbox, location: MessageLocation) {
    try {
        val message = mailbox.messages.get(location)
        println("Read message with ${message.attachments.size} attachments")
    } catch (e: MailException.MessageUnavailable) {
        println("Expunged or moved; skip it")
    } catch (e: MailException.IntegrityViolation) {
        println("UIDVALIDITY or part changed (${e.kind}); rescan the folder")
    } catch (e: MailException.LimitExceeded) {
        println("Larger than ${e.limitBytes} bytes")
    } catch (e: MailException.NotConnected) {
        println("Reconnecting (${e.state}); retry the operation later")
    } catch (e: MailException.AuthenticationRequired) {
        println("Send the user through the authorization flow again")
    } catch (e: MailException.MailboxClosed) {
        println("Mailbox was closed")
    }
}
// endregion

// region folder-errors
suspend fun listOrExplain(mailbox: Mailbox, folder: FolderPath) {
    try {
        mailbox.messages.page(MessageSelection(folder))
    } catch (e: MailException.FolderNotFound) {
        println("No such folder")
    }
}
// endregion
