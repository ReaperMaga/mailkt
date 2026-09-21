package dev.reapermaga.mailkt.outlook

import java.net.URI

/**
 * Microsoft Entra web/confidential-client configuration for the hosted authorization-code flow.
 * The client secret is held by the backend only.
 *
 * @property allowedRedirectUris exact backend callback URIs registered in Entra for this environment.
 * @property authority e.g. `https://login.microsoftonline.com/common/` or a tenant-specific authority.
 */
data class OutlookConfig(
    val clientId: String,
    val clientSecret: String,
    val allowedRedirectUris: Set<URI>,
    val authority: String = "https://login.microsoftonline.com/common/",
    val enableSending: Boolean = false,
) {
    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(clientSecret.isNotBlank()) { "clientSecret must not be blank" }
        require(allowedRedirectUris.isNotEmpty()) { "allowedRedirectUris must not be empty" }
        require(authority.startsWith("https://")) { "authority must use HTTPS" }
    }

    /** Delegated Exchange IMAP/SMTP scopes. `offline_access` is added by MSAL4J for refresh tokens. */
    val scopes: Set<String>
        get() = buildSet {
            add("https://outlook.office.com/IMAP.AccessAsUser.All")
            if (enableSending) add("https://outlook.office.com/SMTP.Send")
        }

    override fun toString(): String = "OutlookConfig(clientId=$clientId)"
}
