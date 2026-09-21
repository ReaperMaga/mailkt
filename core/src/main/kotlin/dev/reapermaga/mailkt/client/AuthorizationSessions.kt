package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.model.AuthorizationCallback
import dev.reapermaga.mailkt.model.AuthorizationFailure
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.PendingAuthorization
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

/**
 * Provider-neutral bookkeeping for hosted authorization-code flows: redirect allowlist, one-time
 * state, PKCE verifier, ten-minute expiry and atomic consumption through [AuthorizationSessionStore].
 * Used by the Gmail and Outlook clients; holds no state of its own.
 */
class AuthorizationSessions(
    private val provider: String,
    private val clientRegistration: String,
    allowedRedirectUris: Set<URI>,
    private val store: AuthorizationSessionStore,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val allowed: Set<URI> = allowedRedirectUris.onEach { requireSupported(it) }.toSet()

    init {
        require(allowed.isNotEmpty()) { "At least one redirect URI must be allowed" }
    }

    /** Validates [redirectUri] against the allowlist, then stores a fresh pending authorization. */
    suspend fun begin(expectedEmail: MailAddress, redirectUri: URI): PendingAuthorization {
        if (redirectUri !in allowed) throw MailException.AuthorizationFailed(AuthorizationFailure.REDIRECT_NOT_ALLOWED)
        val now = clock.instant()
        val pending = PendingAuthorization(
            state = token(),
            provider = provider,
            clientRegistration = clientRegistration,
            expectedEmail = expectedEmail,
            redirectUri = redirectUri,
            pkceVerifier = token(),
            createdAt = now,
            expiresAt = now.plus(Duration.ofMinutes(PendingAuthorization.TTL_MINUTES)),
        )
        try {
            store.save(pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.STORE_FAILED, e)
        }
        return pending
    }

    /** Atomically consumes the pending session for [callback] and validates it. Replays fail as unknown state. */
    suspend fun consume(callback: AuthorizationCallback): PendingAuthorization {
        val state = callback.state?.takeIf { it.isNotBlank() }
            ?: throw MailException.AuthorizationFailed(AuthorizationFailure.UNKNOWN_STATE)
        val stored = try {
            store.consume(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.STORE_FAILED, e)
        }
        val pending = stored ?: throw MailException.AuthorizationFailed(AuthorizationFailure.UNKNOWN_STATE)
        val failure = when {
            pending.state != state || pending.provider != provider || pending.clientRegistration != clientRegistration ->
                AuthorizationFailure.UNKNOWN_STATE
            pending.isExpired(clock.instant()) -> AuthorizationFailure.EXPIRED
            pending.redirectUri !in allowed -> AuthorizationFailure.REDIRECT_NOT_ALLOWED
            callback.error != null || callback.code.isNullOrBlank() -> AuthorizationFailure.PROVIDER_ERROR
            else -> null
        }
        if (failure != null) throw MailException.AuthorizationFailed(failure)
        return pending
    }

    private fun token(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** PKCE S256 code challenge for [verifier]. */
        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun requireSupported(uri: URI) {
            val host = uri.host?.lowercase()
            val loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
            val ok = uri.fragment == null && uri.query == null &&
                (uri.scheme == "https" || (uri.scheme == "http" && loopback))
            require(ok) { "Redirect URIs must be https, or http on loopback, without query or fragment" }
        }
    }
}
