package dev.reapermaga.mailkt.outlook

import com.sun.mail.imap.IMAPStore
import dev.reapermaga.mailkt.auth.MailAuthMethod
import dev.reapermaga.mailkt.session.MailConnection
import dev.reapermaga.mailkt.session.MailSession
import dev.reapermaga.mailkt.util.JakartaPropertiesFactory
import jakarta.mail.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import java.util.concurrent.CompletableFuture

/**
 * MailSession implementation that opens IMAP connections against Outlook/Office 365 using OAuth2 credentials.
 * Manages the underlying Jakarta Mail session and store lifecycle on a background coroutine.
 */
class OutlookMailSession : MailSession {

    override val currentSession: Session get() = session ?: error("No session")
    override val currentStore: IMAPStore get() = store ?: error("No store")
    override val isConnected: Boolean get() = if (store != null) store!!.isConnected else false

    private var session: Session? = null
    private var store: IMAPStore? = null

    private var currentJob: Deferred<MailConnection>? = null
    private val host = "outlook.office365.com"

    /**
     * Asynchronously creates a Jakarta Mail session configured for OAuth2 and connects to the Outlook IMAP store.
     * Returns a [MailConnection] future carrying either the live session/store pair or the captured exception.
     */
    override fun connect(
        method: MailAuthMethod,
        username: String,
        password: String
    ): CompletableFuture<MailConnection> {
        val props = when (method) {
            MailAuthMethod.OAUTH2 -> JakartaPropertiesFactory.oauth2(host)
            else -> throw NotImplementedError()
        }
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.IO).async {
            try {
                session = Session.getInstance(props)
                store = session!!.getStore("imap") as IMAPStore
                store!!.connect(host, username, password)
                MailConnection(
                    session = session,
                    store = store
                )
            } catch (ex: Exception) {
                MailConnection(
                    error = ex
                )
            }
        }
        return currentJob!!.asCompletableFuture()
    }

    /**
     * Cancels the active connection job (if any), closes the IMAP store, and clears cached session references.
     */
    override fun disconnect() {
        currentJob?.cancel()
        store?.close()
        store = null
        session = null
    }
}