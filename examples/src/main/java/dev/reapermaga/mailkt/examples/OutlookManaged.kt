package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.FileOAuth2TokenPersistenceStorage
import dev.reapermaga.mailkt.auth.MailAuthMethod
import dev.reapermaga.mailkt.outlook.OutlookMailSession
import dev.reapermaga.mailkt.outlook.OutlookOAuth2MailAuth
import dev.reapermaga.mailkt.session.MailSessionManager
import io.github.cdimascio.dotenv.Dotenv
import jakarta.mail.Folder
import java.util.concurrent.CompletableFuture

fun main() {
    val dotenv = Dotenv.load()
    val clientId = dotenv.get("OUTLOOK_CLIENT_ID")
    val testUser = dotenv.get("OUTLOOK_TEST_USER")

    val manager = MailSessionManager(keepAliveInterval = 3000)

    val session = OutlookMailSession()
    val conn = manager.manage(session) {
        val store = FileOAuth2TokenPersistenceStorage(testUser)
        val oauth = OutlookOAuth2MailAuth(clientId, store) {
            println("To sign in, use a web browser to open the page ${it.verificationUri} and enter the code ${it.code}")
        }
        val user = oauth.login().join()
        if (!user.success) {
            println("Failed to authenticate user: ${user.error?.message}")
            return@manage CompletableFuture.failedFuture(user.error!!)
        }
        session.connect(
            method = MailAuthMethod.OAUTH2,
            username = user.username!!,
            password = user.accessToken!!
        )
    }.join()
    if (!conn.success) {
        println("Failed to connect to mailbox: ${conn.error?.message}")
        return
    }
    val folder = session.currentStore.getFolder("INBOX")
    folder.open(Folder.READ_ONLY)
    println("Connected to mailbox, total messages: ${folder.messageCount}")
}