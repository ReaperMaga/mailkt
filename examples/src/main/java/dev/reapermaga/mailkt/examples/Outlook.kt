package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.FileTokenPersistenceStorage
import dev.reapermaga.mailkt.auth.OAuth2Credentials
import dev.reapermaga.mailkt.folder.readMessages
import dev.reapermaga.mailkt.outlook.OutlookMailSession
import dev.reapermaga.mailkt.outlook.OutlookOAuth2Config
import dev.reapermaga.mailkt.outlook.OutlookOAuth2MailAuth
import dev.reapermaga.mailkt.session.MailCredentials
import io.github.cdimascio.dotenv.Dotenv

/** Coroutine-first Outlook OAuth2 and IMAP example. */
suspend fun main() {
    val dotenv = Dotenv.load()
    val clientId = requireNotNull(dotenv.get("OUTLOOK_CLIENT_ID"))
    val testUser = requireNotNull(dotenv.get("OUTLOOK_TEST_USER"))
    val oauth =
        OutlookOAuth2MailAuth(
            OutlookOAuth2Config.consumer(clientId),
            FileTokenPersistenceStorage(testUser),
        )
    val credentials = oauth.credentials()
    val session = OutlookMailSession()

    try {
        session.connect(MailCredentials.oauth2(credentials.username, credentials.accessToken))
        val inbox = readMessages(session, "INBOX", range = 1..1)
        try {
            println("Connected to Outlook, total messages: ${inbox.folder.messageCount}")
        } finally {
            inbox.close()
        }
    } finally {
        session.disconnect()
    }
}

private suspend fun OutlookOAuth2MailAuth.credentials(): OAuth2Credentials =
    if (hasToken()) login()
    else
        deviceLogin {
            println("Open ${it.verificationUri} and enter code ${it.code}")
        }
