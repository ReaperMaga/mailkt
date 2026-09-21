package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.ConnectionPolicy
import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.client.MailboxOptions
import dev.reapermaga.mailkt.client.MailboxRegistry
import dev.reapermaga.mailkt.client.use
import dev.reapermaga.mailkt.gmail.Gmail
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MailboxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration.Companion.seconds

// region lifecycle-open
suspend fun openWithPolicy(gmail: Gmail, registry: MailboxRegistry): Mailbox {
    val options = MailboxOptions(
        connectionPolicy = ConnectionPolicy(keepAliveInterval = 30.seconds, maxReconnectAttempts = 5),
        registry = registry, // optional: rejects a second open mailbox with the same identity
    )
    return gmail.open(MailAddress("alice@gmail.com"), options)
}
// endregion

// region lifecycle-use
suspend fun withMailbox(gmail: Gmail) {
    gmail.open(MailAddress("alice@gmail.com")).use { mailbox ->
        println("Connected as mailbox of ${mailbox.id.provider}")
    } // always closed, even on failure or cancellation
}
// endregion

// region lifecycle-states
fun observeState(mailbox: Mailbox, scope: CoroutineScope) {
    mailbox.state.onEach { state ->
        when (state) {
            MailboxState.Connected -> println("Connected")
            is MailboxState.Reconnecting -> println("Reconnecting, attempt ${state.attempt} (${state.reason})")
            MailboxState.AuthenticationRequired -> println("Run the authorization flow again, then call reconnect()")
            is MailboxState.Failed -> println("Failed (recoverable=${state.recoverable}); call reconnect() to retry")
            MailboxState.Closed -> println("Closed")
        }
    }.launchIn(scope)
}
// endregion

// region lifecycle-reconnect
/** After the application completed a new hosted authorization, resume the same mailbox. */
suspend fun resumeAfterReauthorization(mailbox: Mailbox) {
    if (mailbox.state.value is MailboxState.AuthenticationRequired || mailbox.state.value is MailboxState.Failed) {
        try {
            mailbox.reconnect()
        } catch (e: MailException.AuthenticationRequired) {
            println("Still not authorized; send the user through the authorization flow again")
        }
    }
}
// endregion

// region lifecycle-registry
suspend fun shutdown(registry: MailboxRegistry) {
    val report = registry.close() // closes all mailboxes concurrently
    report.failures.forEach { (id, _) -> println("Failed to close mailbox of ${id.provider}") }
}
// endregion
