package dev.reapermaga.mailkt.outlook

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
import dev.reapermaga.mailkt.model.RecoveryReason
import dev.reapermaga.mailkt.model.TokenKey
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI
import java.time.Clock

internal const val OUTLOOK_PROVIDER = "outlook"

/**
 * Backend-oriented Outlook client: MSAL4J confidential client with the hosted authorization-code flow.
 * MailKT never opens a browser, starts a listener or uses device-code login.
 */
class Outlook internal constructor(
    private val config: OutlookConfig,
    private val tokenStore: TokenStore,
    authorizationSessionStore: AuthorizationSessionStore,
    private val connector: MailboxConnector,
    private val backend: MsalBackend,
    private val clock: Clock,
) {
    constructor(
        config: OutlookConfig,
        tokenStore: TokenStore,
        authorizationSessionStore: AuthorizationSessionStore,
        connector: MailboxConnector = DefaultMailboxConnector,
    ) : this(config, tokenStore, authorizationSessionStore, connector, MsalJBackend(config), Clock.systemUTC())

    private val sessions = AuthorizationSessions(
        OUTLOOK_PROVIDER, config.clientId, config.allowedRedirectUris, authorizationSessionStore, clock,
    )

    /** Creates a pending authorization and returns the Microsoft URL the frontend should navigate to. */
    suspend fun beginAuthorization(expectedEmail: MailAddress, redirectUri: URI): AuthorizationRequest {
        val pending = sessions.begin(expectedEmail, redirectUri)
        val url = backend.authorizationUrl(
            redirectUri, pending.state, AuthorizationSessions.challenge(pending.pkceVerifier), expectedEmail.value,
        )
        return AuthorizationRequest(url, pending.state, pending.expiresAt)
    }

    /** Validates the callback, exchanges the code, rejects the wrong account, then stores the MSAL cache. */
    suspend fun completeAuthorization(callback: AuthorizationCallback): MailboxId {
        val pending = sessions.consume(callback)
        val account = try {
            backend.exchange(callback.code!!, pending.redirectUri, pending.pkceVerifier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw MailException.ConnectionFailed(RecoveryReason.NETWORK, e)
        } catch (e: Exception) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.EXCHANGE_FAILED, e)
        }
        val authenticated = account.username?.let { runCatching { MailAddress(it) }.getOrNull() }
            ?: throw MailException.AuthorizationFailed(AuthorizationFailure.EXCHANGE_FAILED)
        if (authenticated.normalized != pending.expectedEmail.normalized) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.WRONG_ACCOUNT)
        }
        val id = MailboxId.of(OUTLOOK_PROVIDER, authenticated)
        try {
            tokenStore.save(tokenKey(id), account.cache)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MailException.AuthorizationFailed(AuthorizationFailure.STORE_FAILED, e)
        }
        return id
    }

    /**
     * Opens the mailbox for [email] using its cached identity and silent renewal only.
     * Throws [MailException.AuthenticationRequired] when the application must run authorization again.
     */
    suspend fun open(email: MailAddress, options: MailboxOptions = MailboxOptions()): Mailbox {
        val id = MailboxId.of(OUTLOOK_PROVIDER, email)
        val key = tokenKey(id)
        if (tokenStore.load(key) == null) throw MailException.AuthenticationRequired()
        val tokens = OutlookAccessTokens(key, email, tokenStore, backend, clock)
        val endpoint = if (config.enableSending) ENDPOINT else ENDPOINT.copy(smtpHost = null)
        return connector.connect(id, email, endpoint, tokens, options)
    }

    private fun tokenKey(id: MailboxId) = TokenKey(OUTLOOK_PROVIDER, config.clientId, id)

    private companion object {
        val ENDPOINT = ImapEndpoint("outlook.office365.com", 993, "smtp.office365.com", 587, smtpStartTls = true)
    }
}
