package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.use
import dev.reapermaga.mailkt.model.AuthorizationCallback
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.SpecialUse
import io.github.cdimascio.dotenv.Dotenv
import java.net.URI

/**
 * Local end-to-end run against Gmail using a loopback callback. Register
 * `http://localhost:8080/oauth/gmail/callback` with Google, put GMAIL_CLIENT_ID, GMAIL_CLIENT_SECRET
 * and GMAIL_ADDRESS into `.env`, open the printed URL and paste the `code` and `state` query values.
 */
suspend fun main() {
    val env = Dotenv.load()
    val email = MailAddress(requireNotNull(env["GMAIL_ADDRESS"]))
    val redirect = URI("http://localhost:8080/oauth/gmail/callback")
    val config = dev.reapermaga.mailkt.gmail.GmailConfig(
        requireNotNull(env["GMAIL_CLIENT_ID"]), requireNotNull(env["GMAIL_CLIENT_SECRET"]), setOf(redirect),
    )
    val gmail = dev.reapermaga.mailkt.gmail.Gmail(config, InMemoryTokenStore(), InMemoryAuthorizationSessionStore())
    println("Open: ${gmail.beginAuthorization(email, redirect).authorizationUrl}")
    print("code: ")
    val code = readln()
    print("state: ")
    val state = readln()
    gmail.completeAuthorization(AuthorizationCallback(code, state))
    gmail.open(email).use { mailbox ->
        val inbox = mailbox.folders.special(SpecialUse.INBOX)
        println("Inbox found: ${inbox != null}")
    }
}
