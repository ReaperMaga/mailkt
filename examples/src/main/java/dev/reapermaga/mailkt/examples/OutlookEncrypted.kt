package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.AESEncryptedTokenPersistenceStorage
import dev.reapermaga.mailkt.auth.FileTokenPersistenceStorage
import dev.reapermaga.mailkt.folder.readMessages
import dev.reapermaga.mailkt.outlook.OutlookMailSession
import dev.reapermaga.mailkt.outlook.OutlookOAuth2Config
import dev.reapermaga.mailkt.outlook.OutlookOAuth2MailAuth
import dev.reapermaga.mailkt.session.MailCredentials
import io.github.cdimascio.dotenv.Dotenv

/** Outlook example with an AES-GCM encrypted token cache. */
suspend fun main() {
    val dotenv = Dotenv.load()
    val clientId = requireNotNull(dotenv.get("OUTLOOK_CLIENT_ID"))
    val testUser = requireNotNull(dotenv.get("OUTLOOK_TEST_USER"))
    val aesKey = requireNotNull(dotenv.get("AES_KEY")) { "AES_KEY must be persisted between runs" }
    val storage =
        AESEncryptedTokenPersistenceStorage(
            aesKey,
            FileTokenPersistenceStorage(testUser, "oauth2_encrypted.json"),
        )
    val oauth = OutlookOAuth2MailAuth(OutlookOAuth2Config.consumer(clientId), storage)
    val credentials =
        if (oauth.hasToken()) oauth.login()
        else oauth.deviceLogin { println("Open ${it.verificationUri} and enter code ${it.code}") }
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
