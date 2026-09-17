package dev.reapermaga.mailkt.message

import dev.reapermaga.mailkt.session.MailAuthMethod
import dev.reapermaga.mailkt.session.MailCredentials
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.SendFailedException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.util.Date
import java.util.Properties

/** Authenticated SMTP submission with mandatory STARTTLS and certificate hostname verification. */
data class SmtpConfig(val host: String, val port: Int = 587) {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
    }

    internal fun properties(credentials: MailCredentials) = Properties().apply {
        setProperty("mail.smtp.host", host)
        setProperty("mail.smtp.port", port.toString())
        setProperty("mail.smtp.auth", "true")
        setProperty("mail.smtp.starttls.enable", "true")
        setProperty("mail.smtp.starttls.required", "true")
        setProperty("mail.smtp.ssl.checkserveridentity", "true")
        setProperty("mail.smtp.connectiontimeout", "15000")
        setProperty("mail.smtp.timeout", "30000")
        setProperty("mail.smtp.writetimeout", "30000")
        setProperty("mail.smtp.sendpartial", "false")
        if (credentials.method == MailAuthMethod.OAUTH2) {
            setProperty("mail.smtp.auth.mechanisms", "XOAUTH2")
            setProperty("mail.smtp.auth.login.disable", "true")
            setProperty("mail.smtp.auth.plain.disable", "true")
        }
    }
}

enum class SendStatus { ACCEPTED, FAILED, UNKNOWN }

/** ACCEPTED means the SMTP service accepted submission, never proof of recipient delivery. */
data class SendResult(val messageId: String, val status: SendStatus, val cause: Exception? = null)

/** SMTP is configured, not a guarantee that OAuth consent or provider policy permits submission. */
val dev.reapermaga.mailkt.session.MailSession.supportsSending: Boolean
    get() = (this as? dev.reapermaga.mailkt.session.ImapMailSession)?.smtpConfig != null

suspend fun dev.reapermaga.mailkt.session.MailSession.sendMessage(message: MimeMessage): SendResult =
    (this as? dev.reapermaga.mailkt.session.ImapMailSession
        ?: error("This session does not support SMTP sending")).sendMessage(message)

/** Uses the managed session's current credentials, including those replaced during reconnect. */
suspend fun dev.reapermaga.mailkt.session.ManagedMailSession.sendMessage(message: MimeMessage): SendResult =
    session.sendMessage(message)

/** Caller-owned draft: prepare once and persist its Message-ID before submission. */
fun composeMessage(
    from: String,
    to: List<String>,
    subject: String,
    text: String,
    cc: List<String> = emptyList(),
    bcc: List<String> = emptyList(),
): MimeMessage {
    require(to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty()) { "At least one recipient is required" }
    require('\r' !in subject && '\n' !in subject) { "Subject must be a single line" }
    return MimeMessage(Session.getInstance(Properties())).apply {
        setFrom(mailAddress(from))
        setRecipients(Message.RecipientType.TO, to.map(::mailAddress).toTypedArray())
        setRecipients(Message.RecipientType.CC, cc.map(::mailAddress).toTypedArray())
        setRecipients(Message.RecipientType.BCC, bcc.map(::mailAddress).toTypedArray())
        setSubject(subject, "UTF-8")
        setText(text, "UTF-8")
        sentDate = Date()
        saveChanges()
    }
}

internal fun mailAddress(value: String): InternetAddress {
    require('\r' !in value && '\n' !in value)
    return InternetAddress(value, true).also { it.validate() }
}

/** Reply-To is respected; reply-all excludes the selected mailbox and never exposes Bcc. */
fun composeReply(original: MimeMessage, from: String, text: String, replyAll: Boolean = false): MimeMessage {
    val self = mailAddress(from).address
    fun addresses(values: Array<out jakarta.mail.Address>?) =
        values.orEmpty().mapNotNull { (it as? InternetAddress)?.address }
            .filterNot { it.equals(self, ignoreCase = true) }.distinctBy { it.lowercase(java.util.Locale.ROOT) }
    val primary = addresses(original.replyTo).ifEmpty { addresses(original.getRecipients(Message.RecipientType.TO)) }
    val to = (primary + if (replyAll) addresses(original.getRecipients(Message.RecipientType.TO)) else emptyList())
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }
    val cc = if (replyAll) addresses(original.getRecipients(Message.RecipientType.CC))
        .filterNot { candidate -> to.any { it.equals(candidate, true) } } else emptyList()
    val subject = original.subject.orEmpty().let { if (it.startsWith("Re:", true)) it else "Re: $it" }
    return composeMessage(from, to, subject, text, cc).apply {
        val parent = original.messageID
        if (parent != null) {
            require('\r' !in parent && '\n' !in parent)
            setHeader("In-Reply-To", parent)
            val references = original.getHeader("References", " ").orEmpty()
                .replace(Regex("[\\r\\n]+[ \\t]*"), " ")
            setHeader("References", (references + " " + parent).trim())
        }
    }
}

internal suspend fun submitMessage(
    config: SmtpConfig,
    credentials: MailCredentials,
    message: MimeMessage,
    dispatcher: CoroutineDispatcher,
    transportFactory: (Session) -> jakarta.mail.Transport = { it.getTransport("smtp") },
): SendResult {
    val from = message.from.orEmpty()
    require(from.size == 1 && (from.single() as? InternetAddress)?.address.equals(credentials.username, true)) {
        "Sender must match the authenticated mailbox"
    }
    require(!message.allRecipients.isNullOrEmpty()) { "At least one recipient is required" }
    val existingId = message.messageID
    message.saveChanges()
    if (existingId != null) message.setHeader("Message-ID", existingId)
    val id = requireNotNull(message.messageID)
    var submitting = false
    var accepted = false
    try {
        return runInterruptible(dispatcher) {
            val transport = transportFactory(Session.getInstance(config.properties(credentials)))
            try {
                transport.connect(config.host, config.port, credentials.username, credentials.secret)
                submitting = true
                transport.sendMessage(message, message.allRecipients)
                accepted = true
                SendResult(id, SendStatus.ACCEPTED)
            } finally {
                // QUIT failure does not invalidate a successful DATA acknowledgement.
                runCatching { transport.close() }
            }
        }
    } catch (exception: CancellationException) {
        // Cancellation can occur after DATA: persist UNKNOWN before calling, reconcile by Message-ID.
        throw exception
    } catch (exception: Exception) {
        val status = when {
            accepted -> SendStatus.ACCEPTED
            !submitting -> SendStatus.FAILED
            exception is SendFailedException && !exception.validSentAddresses.isNullOrEmpty() -> SendStatus.UNKNOWN
            exception is org.eclipse.angus.mail.smtp.SMTPAddressFailedException -> SendStatus.FAILED
            exception is org.eclipse.angus.mail.smtp.SMTPSendFailedException && exception.returnCode in 400..599 -> SendStatus.FAILED
            else -> SendStatus.UNKNOWN
        }
        return SendResult(id, status, exception)
    }
}
