package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.FileTokenPersistenceStorage
import dev.reapermaga.mailkt.auth.MailAuthMethod
import dev.reapermaga.mailkt.folder.FolderWatchHandle
import dev.reapermaga.mailkt.folder.watchFolder
import dev.reapermaga.mailkt.outlook.OutlookMailSession
import dev.reapermaga.mailkt.outlook.OutlookOAuth2Config
import dev.reapermaga.mailkt.outlook.OutlookOAuth2MailAuth
import dev.reapermaga.mailkt.session.MailSessionManager
import io.github.cdimascio.dotenv.Dotenv
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Demonstrates using MailSessionManager to keep an Outlook session alive with OAuth2 credentials
 * and listen for mailbox activity through a managed connection.
 */
fun main() {
    val dotenv = Dotenv.load()
    val clientId = dotenv.get("OUTLOOK_CLIENT_ID")
    val testUser = dotenv.get("OUTLOOK_TEST_USER")

    val manager = MailSessionManager(keepAliveInterval = 3000)

    val store = FileTokenPersistenceStorage(testUser)
    val oauth = OutlookOAuth2MailAuth(OutlookOAuth2Config.consumer(clientId), store)
    if (!oauth.hasToken().join()) {
        oauth.deviceLogin {
            println(
                "To sign in, use a web browser to open the page ${it.verificationUri} and enter the code ${it.code}"
            )
        }
            .join()
    }
    val pool = Executors.newCachedThreadPool()
    val session = OutlookMailSession()
    val folderWatchHandles = mutableListOf<FolderWatchHandle>()
    val managed = manager
        .manage(session) {
            val user = oauth.login().join()
            if (!user.success) {
                println("Failed to authenticate user: ${user.error?.message}")
                return@manage CompletableFuture.failedFuture(user.error!!)
            }
            session.connect(
                method = MailAuthMethod.OAUTH2,
                username = user.username!!,
                password = user.accessToken!!,
            ).thenApply { conn ->
                val handle = watchFolder(session, "INBOX", threadPool = pool) {
                    println("New message received: ${it.subject}")
                }
                folderWatchHandles.add(handle)
                conn
            }
        }.join()
    println("Managed session established, listening for new messages...")
    managed.lifecycle.keepAlive.add {
        if (!managed.session.isConnected && folderWatchHandles.isNotEmpty()) {
            println("Session disconnected, closing folder watchers...")
            folderWatchHandles.forEach { it.close() }
            folderWatchHandles.clear()
        }
    }
    if (!managed.lastConnection.success) {
        println("Failed to connect to mailbox: ${managed.lastConnection.error?.message}")
        return
    }
    readln()
}
