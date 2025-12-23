package dev.reapermaga.mailkt.outlook

import dev.reapermaga.mailkt.provider.MailAuthMethod

fun main() {
    val clientId = "YOUR_CLIENT_ID"
    val oauth = OutlookOAuth2MailAuth(clientId)
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