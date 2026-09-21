package dev.reapermaga.mailkt.client

import dev.reapermaga.mailkt.model.AuthorizationCallback
import dev.reapermaga.mailkt.model.AuthorizationFailure
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.PendingAuthorization
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class MutableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
}

private class MemorySessions : AuthorizationSessionStore {
    val map = ConcurrentHashMap<String, PendingAuthorization>()
    override suspend fun save(session: PendingAuthorization) { map[session.state] = session }
    override suspend fun consume(state: String): PendingAuthorization? = map.remove(state)
}

class AuthorizationSessionsTest {
    private val https = URI("https://app.example.com/oauth/gmail/callback")
    private val loopback = URI("http://localhost:8080/oauth/gmail/callback")
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val store = MemorySessions()
    private val sessions = AuthorizationSessions("gmail", "client", setOf(https, loopback), store, clock)
    private val alice = MailAddress("alice@example.com")


    @Test
    fun beginStoresOneTimeStatePkceAndTenMinuteExpiry() = runBlocking {
        val a = sessions.begin(alice, https)
        val b = sessions.begin(alice, loopback)
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.pkceVerifier, b.pkceVerifier)
        assertTrue(a.pkceVerifier.length >= 43)
        assertEquals(Duration.ofMinutes(10), Duration.between(a.createdAt, a.expiresAt))
        assertEquals(a, store.map[a.state])
    }

    @Test
    fun rejectsRedirectOutsideAllowlist() = runBlocking {
        val reason = runCatching { sessions.begin(alice, URI("https://evil.example.com/cb")) }
            .exceptionOrNull() as MailException.AuthorizationFailed
        assertEquals(AuthorizationFailure.REDIRECT_NOT_ALLOWED, reason.reason)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun allowlistOnlyAcceptsHttpsOrLoopbackHttp() {
        assertFailsWith<IllegalArgumentException> {
            AuthorizationSessions("g", "c", setOf(URI("http://app.example.com/cb")), store)
        }
        assertFailsWith<IllegalArgumentException> { AuthorizationSessions("g", "c", emptySet(), store) }
    }

    @Test
    fun consumeIsOneTimeAndReplayIsRejected() = runBlocking {
        val p = sessions.begin(alice, https)
        assertEquals(p, sessions.consume(AuthorizationCallback("code", p.state)))
        val replay = runCatching { sessions.consume(AuthorizationCallback("code", p.state)) }.exceptionOrNull()
        assertEquals(AuthorizationFailure.UNKNOWN_STATE, (replay as MailException.AuthorizationFailed).reason)
    }

    @Test
    fun rejectsUnknownMissingExpiredAndProviderErrors() = runBlocking {
        fun reason(t: Throwable?) = (t as MailException.AuthorizationFailed).reason
        assertEquals(AuthorizationFailure.UNKNOWN_STATE,
            reason(runCatching { sessions.consume(AuthorizationCallback("c", null)) }.exceptionOrNull()))
        assertEquals(AuthorizationFailure.UNKNOWN_STATE,
            reason(runCatching { sessions.consume(AuthorizationCallback("c", "nope")) }.exceptionOrNull()))
        val expired = sessions.begin(alice, https)
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        assertEquals(AuthorizationFailure.EXPIRED,
            reason(runCatching { sessions.consume(AuthorizationCallback("c", expired.state)) }.exceptionOrNull()))
        val denied = sessions.begin(alice, https)
        assertEquals(AuthorizationFailure.PROVIDER_ERROR,
            reason(runCatching { sessions.consume(AuthorizationCallback(null, denied.state, "access_denied")) }.exceptionOrNull()))
        assertTrue(store.map.isEmpty(), "failed callbacks still burn the state")
        val noCode = sessions.begin(alice, https)
        assertEquals(AuthorizationFailure.PROVIDER_ERROR,
            reason(runCatching { sessions.consume(AuthorizationCallback(null, noCode.state)) }.exceptionOrNull()))
    }

    @Test
    fun sessionFromAnotherClientRegistrationIsRejected() = runBlocking {
        val other = AuthorizationSessions("gmail", "other-client", setOf(https), store, clock)
        val p = other.begin(alice, https)
        val e = runCatching { sessions.consume(AuthorizationCallback("c", p.state)) }.exceptionOrNull()
        assertEquals(AuthorizationFailure.UNKNOWN_STATE, (e as MailException.AuthorizationFailed).reason)
    }

    @Test
    fun pkceChallengeMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AuthorizationSessions.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }
}
