package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.FileTokenPersistenceStorage
import dev.reapermaga.mailkt.outlook.OutlookMailSession
import dev.reapermaga.mailkt.outlook.OutlookOAuth2Config
import dev.reapermaga.mailkt.outlook.OutlookOAuth2MailAuth
import dev.reapermaga.mailkt.session.MailCredentials
import dev.reapermaga.mailkt.session.MailSessionManager
import io.github.cdimascio.dotenv.Dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlin.time.Duration.Companion.seconds

/** Keeps an Outlook session connected until the user presses Enter. */
suspend fun main() {
    val dotenv = Dotenv.load()
    val clientId = requireNotNull(dotenv.get("OUTLOOK_CLIENT_ID"))
    val testUser = requireNotNull(dotenv.get("OUTLOOK_TEST_USER"))
    val oauth =
        OutlookOAuth2MailAuth(
            OutlookOAuth2Config.consumer(clientId),
            FileTokenPersistenceStorage(testUser),
        )
    if (!oauth.hasToken()) {
        oauth.deviceLogin { println("Open ${it.verificationUri} and enter code ${it.code}") }
    }

    val manager = MailSessionManager(keepAliveInterval = 3.seconds)
    val session = OutlookMailSession()
    try {
        manager.manage(session) {
            val credentials = oauth.login()
            it.connect(MailCredentials.oauth2(credentials.username, credentials.accessToken))
        }
        println("Managed Outlook session established. Press Enter to stop.")
        runInterruptible(Dispatchers.IO) { readln() }
    } finally {
        manager.stop()
    }
}
