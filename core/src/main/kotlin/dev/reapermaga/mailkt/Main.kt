package dev.reapermaga.mailkt

import com.microsoft.aad.msal4j.InteractiveRequestParameters
import com.microsoft.aad.msal4j.PublicClientApplication
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.IdleManager
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import java.net.URI
import java.util.Properties
import java.util.concurrent.Executors


fun main() {
    val host = "outlook.office365.com"
    val clientId = "<client-id>"
    val app = PublicClientApplication
        .builder(clientId)
        .authority("https://login.microsoftonline.com/consumers")
        .build()
    val params = InteractiveRequestParameters
        .builder(URI("http://localhost:8080/"))
        .scopes(setOf("https://outlook.office.com/IMAP.AccessAsUser.All"))
        .build()
    val result = app.acquireToken(params).join()
    val username = result.account().username()
    val token = result.accessToken()

    val props = Properties()
    props["mail.store.protocol"] = "imap";
    props["mail.imap.host"] = host;
    props["mail.imap.port"] = "993";
    props["mail.imap.ssl.enable"] = "true";
    props["mail.imap.ssl.trust"] = "*";
    props["mail.imap.starttls.enable"] = "true"
    props["mail.imap.usesocketchannels"] = "true";

    // OAuth2 settings
    props["mail.imap.auth.mechanisms"] = "XOAUTH2";
    props["mail.imap.auth.login.disable"] = "true";
    props["mail.imap.auth.plain.disable"] = "true";

    val session = Session.getInstance(props)
    val store = session.getStore("imap") as IMAPStore
    store.connect(host, username, token)
    val idleManager = IdleManager(session, Executors.newCachedThreadPool())
    val inbox = store.getFolder("INBOX") as IMAPFolder
    inbox.open(Folder.READ_ONLY)

    println("Connected to mailbox, waiting for new messages...")
    inbox.addMessageCountListener(object : MessageCountAdapter() {
        override fun messagesAdded(event: MessageCountEvent) {
            val source = event.source as Folder
            println("New message received! Total messages: ${source.messageCount}")
            val latestMessage = source.getMessage(source.messageCount)
            println("Subject: ${latestMessage.subject}")
            println("From: ${latestMessage.from.joinToString()}")
            try {
                idleManager.watch(source)
            } catch (ex: MessagingException) {
               ex.printStackTrace()
            }
        }
    })
    idleManager.watch(inbox)
}
