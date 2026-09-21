package dev.reapermaga.mailkt.gmail

import dev.reapermaga.mailkt.client.AuthorizationSessionStore
import dev.reapermaga.mailkt.client.AuthorizationSessions
import dev.reapermaga.mailkt.client.DefaultMailboxConnector
import dev.reapermaga.mailkt.client.ImapEndpoint
import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.client.MailboxConnector
import dev.reapermaga.mailkt.client.MailboxOptions
import dev.reapermaga.mailkt.client.TokenStore
import dev.reapermaga.mailkt.model.AuthorizationCallback
import dev.reapermaga.mailkt.model.AuthorizationFailure
import dev.reapermaga.mailkt.model.AuthorizationRequest
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.TokenKey
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.time.Clock

/**
 * Backend-oriented Gmail client using Google's hosted authorization-code flow (Web application credentials).
 * MailKT never opens a browser or a listener: the backend hands [AuthorizationRequest.authorizationUrl]
 * to the frontend and feeds the provider redirect back through [completeAuthorization].
 */
class Gmail internal constructor(
    private val config: GmailConfig,
    private val tokenStore: TokenStore,
    authorizationSessionStore: AuthorizationSessionStore,
    private val connector: MailboxConnector,
    private val endpoint: GoogleTokenEndpoint,
    private val clock: Clock,
) {
    constructor(
        config: GmailConfig,
        tokenStore: TokenStore,
        authorizationSessionStore: AuthorizationSessionStore,
        connector: MailboxConnector = DefaultMailboxConnector,
    ) : this(config, tokenStore, authorizationSessionStore, connector, HttpGoogleTokenEndpoint(config), Clock.systemUTC())

    private val sessions = AuthorizationSessions(
        GMAIL_PROVIDER, config.clientId, config.allowedRedirectUris, authorizationSessionStore, clock,
    )

    /** Creates a pending authorization and returns the Google URL the frontend should navigate to. */
    suspend fun beginAuthorization(expectedEmail: MailAddress, redirectUri: URI): AuthorizationRequest {
        val pending = sessions.begin(expectedEmail, redirectUri)
        val params = linkedMapOf(
            "client_id" to config.clientId,
            "redirect_uri" to redirectUri.toString(),
            "response_type" to "code",
            "scope" to SCOPES,
            "state" to pending.state,
            "code_challenge" to AuthorizationSessions.challenge(pending.pkceVerifier),
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent",
            "login_hint" to expectedEmail.value,
        )
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        return AuthorizationRequest(URI("$AUTH_ENDPOINT?$query"), pending.state, pending.expiresAt)
    }

    /** Validates the callback, exchanges the code, rejects the wrong account, then stores the refresh token. */
    suspend fun completeAuthorization(callback: AuthorizationCallback): MailboxId {
        val pending = sessions.consume(callback)
        val tokens = try {
            endpoint.exchangeCode(callback.code!!, pending.redirectUri, pending.pkceVerifier)
        } catch (e: GoogleTokenException) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.EXCHANGE_FAILED, e)
        } catch (e: IOException) {
            throw MailException.ConnectionFailed(dev.reapermaga.mailkt.model.RecoveryReason.NETWORK, e)
        }
        val claims = GmailIdentity.claims(tokens.idToken)
        val authenticated = claims?.email?.let { runCatching { MailAddress(it) }.getOrNull() }
        if (claims == null || authenticated == null || config.clientId !in claims.audiences) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.EXCHANGE_FAILED)
        }
        val canonical = GmailIdentity.canonical(authenticated)
        if (canonical != GmailIdentity.canonical(pending.expectedEmail)) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.WRONG_ACCOUNT)
        }
        val id = MailboxId.of(GMAIL_PROVIDER, canonical)
        val key = tokenKey(id)
        try {
            val refresh = tokens.refreshToken ?: tokenStore.load(key)?.let(GmailTokenPayload::decode)
                ?: throw MailException.AuthorizationFailed(AuthorizationFailure.EXCHANGE_FAILED)
            tokenStore.save(key, GmailTokenPayload.encode(refresh))
        } catch (e: MailException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.STORE_FAILED, e)
        }
        return id
    }

    /**
     * Opens the mailbox for [email] using stored tokens and silent refresh only.
     * Throws [MailException.AuthenticationRequired] when the application must run authorization again.
     */
    suspend fun open(email: MailAddress, options: MailboxOptions = MailboxOptions()): Mailbox {
        val canonical = GmailIdentity.canonical(email)
        val id = MailboxId.of(GMAIL_PROVIDER, canonical)
        val key = tokenKey(id)
        if (tokenStore.load(key) == null) throw MailException.AuthenticationRequired()
        val tokens = GmailAccessTokens(key, tokenStore, endpoint, clock)
        return connector.connect(id, canonical, ENDPOINT, tokens, options)
    }

    private fun tokenKey(id: MailboxId) = TokenKey(GMAIL_PROVIDER, config.clientId, id)

    private companion object {
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val SCOPES = "https://mail.google.com/ openid email"
        val ENDPOINT = ImapEndpoint("imap.gmail.com", 993, "smtp.gmail.com", 465)
    }
}
