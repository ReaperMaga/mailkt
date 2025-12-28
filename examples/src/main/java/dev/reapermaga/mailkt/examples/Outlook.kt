package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.FileTokenPersistenceStorage
import dev.reapermaga.mailkt.session.MailAuthMethod
import dev.reapermaga.mailkt.outlook.OutlookMailSession
import dev.reapermaga.mailkt.outlook.OutlookOAuth2Config
import dev.reapermaga.mailkt.outlook.OutlookOAuth2MailAuth
import io.github.cdimascio.dotenv.Dotenv

/**
 * Demonstrates authenticating against Outlook with OAuth2 using a plain JSON token store and
 * printing the total messages in the INBOX folder.
 */
fun main() {
    val dotenv = Dotenv.load()
    val clientId = dotenv.get("OUTLOOK_CLIENT_ID")
    val testUser = dotenv.get("OUTLOOK_TEST_USER")

    val store = FileTokenPersistenceStorage(testUser)
    val oauth = OutlookOAuth2MailAuth(OutlookOAuth2Config.consumer(clientId), store)
    if (!oauth.hasToken().join()) {
        oauth
            .deviceLogin {
                println(
                    "To sign in, use a web browser to open the page ${it.verificationUri} and enter the code ${it.code}"
                )
            }
            .join()
    }
    val user = oauth.login().join()
    if (!user.success) {
        println("Failed to authenticate user: ${user.error?.message}")
        return
    }
    val session = OutlookMailSession()
    val connection =
        session
            .connect(
                method = MailAuthMethod.OAUTH2,
                username = user.username!!,
                password = user.accessToken!!,
            )
            .join()
    if (!connection.success) {
        println("Failed to connect to mailbox: ${connection.error?.message}")
        return
    }
    val folder = session.currentStore.getFolder("INBOX")
    folder.open(jakarta.mail.Folder.READ_ONLY)
    println("Connected to mailbox, total messages: ${folder.messageCount}")
}
