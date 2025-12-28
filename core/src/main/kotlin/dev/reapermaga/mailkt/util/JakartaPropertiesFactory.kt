package dev.reapermaga.mailkt.util

import java.util.*

object JakartaPropertiesFactory {

    fun oauth2(host: String): Properties {
        val props = Properties()
        props["mail.store.protocol"] = "imap"
        props["mail.imap.host"] = host
        props["mail.imap.port"] = "993"
        props["mail.imap.ssl.enable"] = "true"
        props["mail.imap.ssl.trust"] = "*"
        props["mail.imap.starttls.enable"] = "true"
        props["mail.imap.usesocketchannels"] = "true"

        // OAuth2 settings
        props["mail.imap.auth.mechanisms"] = "XOAUTH2"
        props["mail.imap.auth.login.disable"] = "true"
        props["mail.imap.auth.plain.disable"] = "true"
        return props
    }
}
