package dev.reapermaga.mailkt.outlook

import dev.reapermaga.mailkt.client.AccessTokenSource
import dev.reapermaga.mailkt.client.TokenStore
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.RecoveryReason
import dev.reapermaga.mailkt.model.TokenKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Instant

/**
 * Silent renewal for exactly one mailbox from its own cached MSAL identity. Never opens a browser;
 * unusable credentials become [MailException.AuthenticationRequired].
 */
internal class OutlookAccessTokens(
    private val key: TokenKey,
    private val email: MailAddress,
    private val store: TokenStore,
    private val backend: MsalBackend,
    private val clock: Clock,
) : AccessTokenSource {
    private val mutex = Mutex()
    private var cached: String? = null
    private var expiresAt: Instant = Instant.MIN

    override suspend fun accessToken(): String = mutex.withLock {
        cached?.takeIf { clock.instant().isBefore(expiresAt.minusSeconds(60)) }?.let { return it }
        val cache = store.load(key) ?: throw MailException.AuthenticationRequired()
        val result = try {
            backend.silent(email.normalized, cache)
        } catch (e: MsalInteractionRequired) {
            throw MailException.AuthenticationRequired(cause = e)
        } catch (e: IOException) {
            throw MailException.ConnectionFailed(RecoveryReason.NETWORK, e)
        }
        result.cache?.let { store.save(key, it) }
        cached = result.accessToken
        expiresAt = result.expiresAt
        result.accessToken
    }
}
