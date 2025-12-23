package dev.reapermaga.mailkt.outlook

import dev.reapermaga.mailkt.auth.FileOAuth2TokenPersistenceStorage
import dev.reapermaga.mailkt.auth.MailAuthMethod

fun main() {
    val clientId = "<your_client_id>"
    val oauth = OutlookOAuth2MailAuth(clientId, FileOAuth2TokenPersistenceStorage("<your_email>@outlook.com")) {
        println("To sign in, use a web browser to open the page ${it.verificationUri} and enter the code ${it.code}")
    }
    val user = oauth.login().join()
    if (!user.success) {
        println("Failed to authenticate user: ${user.error?.message}")
        return
    }
    val session = OutlookMailSession()
    val connection = session.connect(
        method = MailAuthMethod.OAUTH2,
        username = user.username!!,
        password = user.accessToken!!
    ).join()
    if (!connection.success) {
        println("Failed to connect to mailbox: ${connection.error?.message}")
        return
    }
    val folder = session.currentStore.getFolder("INBOX")
    folder.open(jakarta.mail.Folder.READ_ONLY)
    println("Connected to mailbox, total messages: ${folder.messageCount}")
}