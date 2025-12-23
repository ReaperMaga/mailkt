package dev.reapermaga.mailkt.outlook

import dev.reapermaga.mailkt.auth.MailAuthMethod

fun main() {
    val clientId = "<your-client-id-here>"
    val oauth = OutlookOAuth2MailAuth(clientId) {
        println("To sign in, use a web browser to open the page ${it.verificationUri} and enter the code ${it.code}")
    }
    val user = oauth.login().join()

    val session = OutlookMailSession()
    session.connect(
        method = MailAuthMethod.OAUTH2,
        username = user.username ?: error("No username"),
        password = user.accessToken ?: error("No access token")
    ).join()
    val folder = session.currentStore.getFolder("INBOX")
    folder.open(jakarta.mail.Folder.READ_ONLY)
    println("Connected to mailbox, total messages: ${folder.messageCount}")
}