package dev.reapermaga.mailkt.model

import java.net.URI
import java.time.Instant

/** Sensitive one-time state stored by [dev.reapermaga.mailkt.client.AuthorizationSessionStore]. */
data class PendingAuthorization(
    val state: String,
    val provider: String,
    val clientRegistration: String,
    val expectedEmail: MailAddress,
    val redirectUri: URI,
    val pkceVerifier: String,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    override fun toString(): String = "PendingAuthorization(provider=$provider)"

    companion object {
        const val TTL_MINUTES: Long = 10
    }
}

/** Returned to the backend so it can hand the URL to the frontend. */
data class AuthorizationRequest(val authorizationUrl: URI, val state: String, val expiresAt: Instant) {
    override fun toString(): String = "AuthorizationRequest(expiresAt=$expiresAt)"
}

/** Query parameters of the backend callback. */
data class AuthorizationCallback(val code: String?, val state: String?, val error: String? = null) {
    override fun toString(): String = "AuthorizationCallback(hasCode=${code != null}, error=$error)"
}
