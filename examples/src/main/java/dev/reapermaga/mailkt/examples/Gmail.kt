package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.FileTokenPersistenceStorage
import dev.reapermaga.mailkt.folder.readMessages
import dev.reapermaga.mailkt.gmail.GmailMailSession
import dev.reapermaga.mailkt.gmail.GmailOAuth2Config
import dev.reapermaga.mailkt.gmail.GmailOAuth2MailAuth
import dev.reapermaga.mailkt.session.MailCredentials
import io.github.cdimascio.dotenv.Dotenv

/** Coroutine-first browser OAuth2 and Gmail IMAP example. */
suspend fun main() {
    val dotenv = Dotenv.load()
    val config =
        GmailOAuth2Config.installedApp(
            clientId = requireNotNull(dotenv.get("GMAIL_CLIENT_ID")),
            clientSecret = requireNotNull(dotenv.get("GMAIL_CLIENT_SECRET")),
        )
    val oauth = GmailOAuth2MailAuth(config, FileTokenPersistenceStorage("gmail"))
    val credentials = oauth.login()
    val session = GmailMailSession()

    try {
        session.connect(MailCredentials.oauth2(credentials.username, credentials.accessToken))
        val inbox = readMessages(session, "INBOX", limit = 1)
        try {
            println("Connected to Gmail, total messages: ${inbox.folder.messageCount}")
        } finally {
            inbox.close()
        }
    } finally {
        session.disconnect()
    }
}
