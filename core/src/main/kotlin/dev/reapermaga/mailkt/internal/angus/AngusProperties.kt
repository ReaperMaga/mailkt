package dev.reapermaga.mailkt.internal.angus

import dev.reapermaga.mailkt.client.ImapEndpoint
import java.util.Properties

/** Jakarta/Angus session properties: TLS with hostname verification, XOAUTH2 only, bounded timeouts. */
internal object AngusProperties {
    fun imap(endpoint: ImapEndpoint): Properties = Properties().apply {
        setProperty("mail.store.protocol", "imap")
        setProperty("mail.imap.host", endpoint.imapHost)
        setProperty("mail.imap.port", endpoint.imapPort.toString())
        setProperty("mail.imap.ssl.enable", "true")
        setProperty("mail.imap.ssl.checkserveridentity", "true")
        setProperty("mail.imap.usesocketchannels", "true")
        setProperty("mail.imap.connectiontimeout", "15000")
        setProperty("mail.imap.timeout", "30000")
        setProperty("mail.imap.writetimeout", "30000")
        setProperty("mail.imap.peek", "true") // reading never sets \Seen
        setProperty("mail.imap.partialfetch", "true")
        setProperty("mail.imap.auth.mechanisms", "XOAUTH2")
        setProperty("mail.imap.auth.login.disable", "true")
        setProperty("mail.imap.auth.plain.disable", "true")
    }

    fun smtp(endpoint: ImapEndpoint): Properties = Properties().apply {
        setProperty("mail.smtp.host", requireNotNull(endpoint.smtpHost))
        setProperty("mail.smtp.port", endpoint.smtpPort.toString())
        setProperty("mail.smtp.auth", "true")
        if (endpoint.smtpStartTls) {
            setProperty("mail.smtp.starttls.enable", "true")
            setProperty("mail.smtp.starttls.required", "true")
        } else {
            setProperty("mail.smtp.ssl.enable", "true")
        }
        setProperty("mail.smtp.ssl.checkserveridentity", "true")
        setProperty("mail.smtp.connectiontimeout", "15000")
        setProperty("mail.smtp.timeout", "30000")
        setProperty("mail.smtp.writetimeout", "30000")
        setProperty("mail.smtp.sendpartial", "false")
        setProperty("mail.smtp.auth.mechanisms", "XOAUTH2")
        setProperty("mail.smtp.auth.login.disable", "true")
        setProperty("mail.smtp.auth.plain.disable", "true")
    }
}
