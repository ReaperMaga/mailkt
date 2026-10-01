package dev.reapermaga.mailkt.gmail

import java.net.URI

/**
 * Google "Web application" OAuth credentials for the hosted authorization-code flow.
 *
 * @property allowedRedirectUris exact backend callback URIs registered with Google for this environment
 * (HTTPS in production, HTTP loopback such as `http://localhost:8080/oauth/gmail/callback` for development).
 */
data class GmailConfig(
    val clientId: String,
    val clientSecret: String,
    val allowedRedirectUris: Set<URI>,
) {
    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(clientSecret.isNotBlank()) { "clientSecret must not be blank" }
        require(allowedRedirectUris.isNotEmpty()) { "allowedRedirectUris must not be empty" }
    }

    override fun toString(): String = "GmailConfig(clientId=$clientId)"
}
