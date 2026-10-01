package dev.reapermaga.mailkt.outlook

import dev.reapermaga.mailkt.client.AuthorizationSessions
import dev.reapermaga.mailkt.model.AuthorizationCallback
import dev.reapermaga.mailkt.model.AuthorizationFailure
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.TokenKey
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OutlookAuthorizationTest {
    private val https = URI("https://app.example.com/oauth/outlook/callback")
    private val loopback = URI("http://localhost:8080/oauth/outlook/callback")
    private val config = OutlookConfig("client-1", "secret-1", setOf(https, loopback), enableSending = true)
    private val tokens = MemoryTokenStore()
    private val sessions = MemorySessions()
    private val msal = FakeMsal()
    private val connector = RecordingConnector()
    private val clock = TestClock()
    private val outlook = Outlook(config, tokens, sessions, connector, msal, clock)
    private val alice = MailAddress("alice@outlook.com")
    private val aliceId = MailboxId("outlook", "alice@outlook.com")
    private val aliceKey = TokenKey("outlook", "client-1", aliceId)

    private fun query(url: URI) = url.rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
    }

    private fun reason(e: Throwable?) = (e as MailException.AuthorizationFailed).reason

    private suspend fun authorize(redirect: URI = https, email: MailAddress = alice): Pair<AuthorizationCallback, String> {
        val request = outlook.beginAuthorization(email, redirect)
        return AuthorizationCallback("code-1", request.state) to query(request.authorizationUrl).getValue("code_challenge")
    }

    @Test
    fun configScopesAreExchangeImapAndOptionalSmtp() {
        assertEquals(setOf("https://outlook.office.com/IMAP.AccessAsUser.All", "https://outlook.office.com/SMTP.Send"), config.scopes)
        assertEquals(setOf("https://outlook.office.com/IMAP.AccessAsUser.All"),
            OutlookConfig("c", "s", setOf(https)).scopes)
    }

    @Test
    fun beginStoresPendingSessionWithPkceAndTenMinuteExpiry() = runBlocking {
        val request = outlook.beginAuthorization(alice, https)
        val q = query(request.authorizationUrl)
        val pending = sessions.map.getValue(request.state)
        assertEquals(AuthorizationSessions.challenge(pending.pkceVerifier), q["code_challenge"])
        assertEquals(request.state, q["state"])
        assertEquals(Duration.ofMinutes(10), Duration.between(pending.createdAt, request.expiresAt))
    }

    @Test
    fun redirectOutsideAllowlistIsRejected() = runBlocking {
        val e = runCatching { outlook.beginAuthorization(alice, URI("https://evil.example.com/cb")) }.exceptionOrNull()
        assertEquals(AuthorizationFailure.REDIRECT_NOT_ALLOWED, reason(e))
    }

    @Test
    fun httpsAndLoopbackCallbacksPersistOpaqueCacheUnderNamespacedKey() = runBlocking {
        for (redirect in listOf(https, loopback)) {
            tokens.map.clear()
            val (callback, challenge) = authorize(redirect)
            assertEquals(aliceId, outlook.completeAuthorization(callback))
            val (code, usedRedirect, verifier) = msal.exchanges.last()
            assertEquals("code-1", code)
            assertEquals(redirect, usedRedirect)
            assertEquals(challenge, AuthorizationSessions.challenge(verifier))
            assertEquals("cache:alice@outlook.com", String(tokens.map.getValue(aliceKey.storageKey)))
        }
    }

    @Test
    fun wrongAccountReplayErrorAndExpiryAreRejected() = runBlocking {
        msal.username = "mallory@outlook.com"
        val (wrong, _) = authorize()
        assertEquals(AuthorizationFailure.WRONG_ACCOUNT, reason(runCatching { outlook.completeAuthorization(wrong) }.exceptionOrNull()))
        assertTrue(tokens.map.isEmpty())
        assertEquals(AuthorizationFailure.UNKNOWN_STATE, reason(runCatching { outlook.completeAuthorization(wrong) }.exceptionOrNull()))
        val denied = outlook.beginAuthorization(alice, https)
        assertEquals(AuthorizationFailure.PROVIDER_ERROR, reason(runCatching {
            outlook.completeAuthorization(AuthorizationCallback(null, denied.state, "access_denied"))
        }.exceptionOrNull()))
        val late = outlook.beginAuthorization(alice, https)
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        assertEquals(AuthorizationFailure.EXPIRED, reason(runCatching {
            outlook.completeAuthorization(AuthorizationCallback("c", late.state))
        }.exceptionOrNull()))
    }

    @Test
    fun exchangeFailureIsTypedAndStoresNothing() = runBlocking {
        msal.exchangeFailure = IllegalStateException("provider text with alice@outlook.com")
        val e = runCatching { outlook.completeAuthorization(authorize().first) }.exceptionOrNull()
        assertEquals(AuthorizationFailure.EXCHANGE_FAILED, reason(e))
        assertTrue("alice" !in e!!.message.orEmpty())
        assertTrue(tokens.map.isEmpty())
    }

    @Test
    fun openUsesExactCachedIdentityAndSilentRenewalOnly() = runBlocking {
        assertFailsWith<MailException.AuthenticationRequired> { outlook.open(alice) }
        outlook.completeAuthorization(authorize().first)
        outlook.open(alice)
        val call = connector.calls.single()
        assertEquals("smtp.office365.com", call.endpoint.smtpHost)
        assertEquals("token-1", call.tokens.accessToken())
        assertEquals("token-1", call.tokens.accessToken(), "cached")
        assertEquals(listOf("alice@outlook.com"), msal.silentCalls)
        clock.now = clock.now.plus(Duration.ofMinutes(59))
        msal.changedCache = "cache:alice@outlook.com".toByteArray()
        assertEquals("token-2", call.tokens.accessToken())
    }

    @Test
    fun otherAccountsCacheIsNeverUsedAndFailuresMapToAuthenticationRequired() = runBlocking {
        val bob = MailAddress("bob@outlook.com")
        tokens.save(TokenKey("outlook", "client-1", MailboxId("outlook", "bob@outlook.com")), "cache:someone-else".toByteArray())
        outlook.open(bob)
        val source = connector.calls.single().tokens
        assertFailsWith<MailException.AuthenticationRequired> { source.accessToken() }
        msal.silentFailure = NETWORK_DOWN
        tokens.save(TokenKey("outlook", "client-1", MailboxId("outlook", "bob@outlook.com")), "cache:bob@outlook.com".toByteArray())
        assertFailsWith<MailException.ConnectionFailed> { source.accessToken() }
        msal.silentFailure = MsalInteractionRequired()
        assertFailsWith<MailException.AuthenticationRequired> { source.accessToken() }
        Unit
    }

    @Test
    fun accountsAreIsolatedByTokenKeyAndSessionState() = runBlocking {
        outlook.completeAuthorization(authorize().first)
        msal.username = "bob@outlook.com"
        outlook.completeAuthorization(authorize(email = MailAddress("bob@outlook.com")).first)
        assertEquals(2, tokens.map.size)
        val other = Outlook(OutlookConfig("client-2", "s", setOf(https)), tokens, sessions, connector, msal, clock)
        assertFailsWith<MailException.AuthenticationRequired> { other.open(alice) }
        assertNotEquals(outlook.beginAuthorization(alice, https).state, outlook.beginAuthorization(alice, https).state)
    }
}
