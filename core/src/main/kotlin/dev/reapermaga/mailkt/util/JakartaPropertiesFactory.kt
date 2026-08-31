package dev.reapermaga.mailkt.util

import java.util.*

object JakartaPropertiesFactory {

    fun oauth2(host: String): Properties = base(host).apply {
        this["mail.imap.auth.mechanisms"] = "XOAUTH2"
        this["mail.imap.auth.login.disable"] = "true"
        this["mail.imap.auth.plain.disable"] = "true"
    }

    fun plain(host: String): Properties = base(host)

    private fun base(host: String): Properties {
        val props = Properties()
        props["mail.store.protocol"] = "imap"
        props["mail.imap.host"] = host
        props["mail.imap.port"] = "993"
        props["mail.imap.ssl.enable"] = "true"
        props["mail.imap.ssl.checkserveridentity"] = "true"
        props["mail.imap.usesocketchannels"] = "true"
        props["mail.imap.connectiontimeout"] = "15000"
        props["mail.imap.timeout"] = "30000"
        return props
    }
}
