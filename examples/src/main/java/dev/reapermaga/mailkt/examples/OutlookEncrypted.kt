package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.auth.AESEncryptedTokenPersistenceStorage
import dev.reapermaga.mailkt.auth.FileTokenPersistenceStorage
import dev.reapermaga.mailkt.auth.MailAuthMethod
import dev.reapermaga.mailkt.outlook.OutlookMailSession
import dev.reapermaga.mailkt.outlook.OutlookOAuth2MailAuth
import dev.reapermaga.mailkt.util.generateAESKey
import io.github.cdimascio.dotenv.Dotenv
import jakarta.mail.Folder

fun main() {
    val dotenv = Dotenv.load()
    val clientId = dotenv.get("OUTLOOK_CLIENT_ID")
    val testUser = dotenv.get("OUTLOOK_TEST_USER")
    val aesKey = dotenv.get("AES_KEY") ?: generateAESKey()
    println("Using AES Key: $aesKey")
    val storage = AESEncryptedTokenPersistenceStorage(
        aesKey,
        FileTokenPersistenceStorage(testUser, "oauth2_encrypted.json")
    )
    val oauth = OutlookOAuth2MailAuth(clientId, storage) {
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
    folder.open(Folder.READ_ONLY)
    println("Connected to mailbox, total messages: ${folder.messageCount}")
}