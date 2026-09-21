package dev.reapermaga.mailkt.gmail

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

class GmailAuthorizationTest {
    private val https = URI("https://app.example.com/oauth/gmail/callback")
    private val loopback = URI("http://localhost:8080/oauth/gmail/callback")
    private val config = GmailConfig("client-1", "secret-1", setOf(https, loopback))
    private val tokens = MemoryTokenStore()
    private val sessions = MemorySessions()
    private val google = FakeGoogle()
    private val connector = RecordingConnector()
    private val clock = TestClock()
    private val gmail = Gmail(config, tokens, sessions, connector, google, clock)
    private val alice = MailAddress("alice@gmail.com")

    private fun query(url: URI) = url.rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
    }

    private suspend fun authorize(redirect: URI = https, email: MailAddress = alice): Pair<AuthorizationCallback, String> {
        val request = gmail.beginAuthorization(email, redirect)
        return AuthorizationCallback("code-1", request.state) to query(request.authorizationUrl)
            .getValue("code_challenge")
    }

    private fun failure(e: Throwable?) = (e as MailException.AuthorizationFailed).reason
    private val aliceKey = TokenKey("gmail", "client-1", MailboxId("gmail", "alice@gmail.com"))

    @Test
    fun authorizationUrlCarriesStatePkceAndOfflineAccess() = runBlocking {
        val request = gmail.beginAuthorization(alice, https)
        val q = query(request.authorizationUrl)
        assertEquals("accounts.google.com", request.authorizationUrl.host)
        assertEquals("code", q["response_type"])
        assertEquals("client-1", q["client_id"])
        assertEquals(https.toString(), q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("offline", q["access_type"])
        assertEquals(request.state, q["state"])
        assertTrue("https://mail.google.com/" in q.getValue("scope").split(' '))
        val pending = sessions.map.getValue(request.state)
        assertEquals(AuthorizationSessions.challenge(pending.pkceVerifier), q["code_challenge"])
        assertEquals(Duration.ofMinutes(10), Duration.between(pending.createdAt, request.expiresAt))
        assertTrue("secret-1" !in request.authorizationUrl.toString())
    }

    @Test
    fun redirectOutsideAllowlistIsRejected() = runBlocking {
        val e = runCatching { gmail.beginAuthorization(alice, URI("https://evil.example.com/cb")) }.exceptionOrNull()
        assertEquals(AuthorizationFailure.REDIRECT_NOT_ALLOWED, failure(e))
        assertTrue(sessions.map.isEmpty())
    }

    @Test
    fun httpsAndLoopbackCallbacksCompleteAndPersistNamespacedRefreshToken() = runBlocking {
        for (redirect in listOf(https, loopback)) {
            tokens.map.clear()
            val (callback, challenge) = authorize(redirect)
            val id = gmail.completeAuthorization(callback)
            assertEquals(MailboxId("gmail", "alice@gmail.com"), id)
            val (code, usedRedirect, verifier) = google.exchanges.last()
            assertEquals("code-1", code)
            assertEquals(redirect, usedRedirect)
            assertEquals(challenge, AuthorizationSessions.challenge(verifier))
            assertEquals("refresh-1", GmailTokenPayload.decode(tokens.map.getValue(aliceKey.storageKey)))
        }
    }

    @Test
    fun wrongAccountIsRejectedAndNothingIsStored() = runBlocking {
        google.email = "mallory@gmail.com"
        val (callback, _) = authorize()
        assertEquals(AuthorizationFailure.WRONG_ACCOUNT,
            failure(runCatching { gmail.completeAuthorization(callback) }.exceptionOrNull()))
        assertTrue(tokens.map.isEmpty())
    }

    @Test
    fun replayCallbackErrorAndExpiryAreRejected() = runBlocking {
        val (callback, _) = authorize()
        gmail.completeAuthorization(callback)
        assertEquals(AuthorizationFailure.UNKNOWN_STATE,
            failure(runCatching { gmail.completeAuthorization(callback) }.exceptionOrNull()))
        val denied = gmail.beginAuthorization(alice, https)
        assertEquals(AuthorizationFailure.PROVIDER_ERROR, failure(runCatching {
            gmail.completeAuthorization(AuthorizationCallback(null, denied.state, "access_denied"))
        }.exceptionOrNull()))
        val late = gmail.beginAuthorization(alice, https)
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        assertEquals(AuthorizationFailure.EXPIRED, failure(runCatching {
            gmail.completeAuthorization(AuthorizationCallback("c", late.state))
        }.exceptionOrNull()))
    }

    @Test
    fun exchangeFailureAndForeignAudienceFailWithoutStoring() = runBlocking {
        google.exchangeFailure = GoogleTokenException(true)
        assertEquals(AuthorizationFailure.EXCHANGE_FAILED,
            failure(runCatching { gmail.completeAuthorization(authorize().first) }.exceptionOrNull()))
        google.exchangeFailure = null
        google.aud = "someone-else"
        assertEquals(AuthorizationFailure.EXCHANGE_FAILED,
            failure(runCatching { gmail.completeAuthorization(authorize().first) }.exceptionOrNull()))
        assertTrue(tokens.map.isEmpty())
    }

    @Test
    fun dotAndPlusVariantsResolveToTheSameMailboxIdentity() = runBlocking {
        google.email = "alice@gmail.com"
        val (callback, _) = authorize(email = MailAddress("A.lice+news@gmail.com"))
        assertEquals(MailboxId("gmail", "alice@gmail.com"), gmail.completeAuthorization(callback))
        gmail.open(MailAddress("a.lice@googlemail.com"))
        assertEquals(MailboxId("gmail", "alice@gmail.com"), connector.calls.single().id)
    }

    @Test
    fun openRequiresStoredCredentialsThenRefreshesSilently() = runBlocking {
        assertFailsWith<MailException.AuthenticationRequired> { gmail.open(alice) }
        gmail.completeAuthorization(authorize().first)
        gmail.open(alice)
        val call = connector.calls.single()
        assertEquals("imap.gmail.com", call.endpoint.imapHost)
        assertEquals("access-1", call.tokens.accessToken())
        assertEquals("access-1", call.tokens.accessToken(), "cached until near expiry")
        assertEquals(1, google.refreshes.size)
        clock.now = clock.now.plus(Duration.ofMinutes(59))
        google.rotated = "refresh-2"
        assertEquals("access-2", call.tokens.accessToken())
        assertEquals("refresh-2", GmailTokenPayload.decode(tokens.map.getValue(aliceKey.storageKey)))
    }

    @Test
    fun failedSilentRefreshRequiresAuthenticationAndTransientFailureIsRecoverable() = runBlocking {
        gmail.completeAuthorization(authorize().first)
        gmail.open(alice)
        val source = connector.calls.single().tokens
        google.refreshFailure = GoogleTokenException(true)
        assertFailsWith<MailException.AuthenticationRequired> { source.accessToken() }
        google.refreshFailure = GoogleTokenException(false)
        assertFailsWith<MailException.ConnectionFailed> { source.accessToken() }
        google.refreshFailure = null
        assertEquals("access-3", source.accessToken(), "reauthorized/recovered without new mailbox")
    }

    @Test
    fun accountsAndClientRegistrationsNeverShareTokenKeysOrSessions() = runBlocking {
        val bob = MailAddress("bob@gmail.com")
        gmail.completeAuthorization(authorize().first)
        google.email = "bob@gmail.com"
        gmail.completeAuthorization(authorize(email = bob).first)
        assertEquals(2, tokens.map.size)
        val other = Gmail(GmailConfig("client-2", "s", setOf(https)), tokens, sessions, connector,
            FakeGoogle(aud = "client-2"), clock)
        assertFailsWith<MailException.AuthenticationRequired> { other.open(alice) }
        val a = gmail.beginAuthorization(alice, https)
        val b = gmail.beginAuthorization(bob, https)
        assertNotEquals(a.state, b.state)
    }
}
