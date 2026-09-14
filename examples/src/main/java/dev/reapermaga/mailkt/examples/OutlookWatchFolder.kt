package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.FileTokenPersistenceStorage
import dev.reapermaga.mailkt.folder.watchFolder
import dev.reapermaga.mailkt.outlook.OutlookMailSession
import dev.reapermaga.mailkt.outlook.OutlookOAuth2Config
import dev.reapermaga.mailkt.outlook.OutlookOAuth2MailAuth
import dev.reapermaga.mailkt.session.MailCredentials
import dev.reapermaga.mailkt.session.MailSessionManager
import io.github.cdimascio.dotenv.Dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlin.time.Duration.Companion.seconds

/** Streams new Outlook messages and re-subscribes after managed reconnections. */
suspend fun main() = coroutineScope {
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

    val manager = MailSessionManager(keepAliveInterval = 3.seconds, parentScope = this)
    val session = OutlookMailSession()
    try {
        val managed =
            manager.manage(session) {
                val credentials = oauth.login()
                it.connect(MailCredentials.oauth2(credentials.username, credentials.accessToken))
            }
        val watcher =
            launch {
                watchFolder(managed, "INBOX")
                    .catch { failure ->
                        println(
                            "INBOX watcher stopped: " +
                                (failure.message ?: failure::class.simpleName)
                        )
                    }
                    .collect { println("New message received: ${it.subject}") }
            }
        println("Listening for Outlook messages. Press Enter to stop.")
        runInterruptible(Dispatchers.IO) { readln() }
        watcher.cancelAndJoin()
    } finally {
        manager.stop()
    }
}
