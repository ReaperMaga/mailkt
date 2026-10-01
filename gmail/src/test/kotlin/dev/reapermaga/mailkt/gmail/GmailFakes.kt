package dev.reapermaga.mailkt.gmail

import dev.reapermaga.mailkt.client.AccessTokenSource
import dev.reapermaga.mailkt.client.AuthorizationSessionStore
import dev.reapermaga.mailkt.client.ImapEndpoint
import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.client.MailboxConnector
import dev.reapermaga.mailkt.client.MailboxOptions
import dev.reapermaga.mailkt.client.TokenStore
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.model.MailboxId
import dev.reapermaga.mailkt.model.PendingAuthorization
import dev.reapermaga.mailkt.model.TokenKey
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal class MemoryTokenStore : TokenStore {
    val map = ConcurrentHashMap<String, ByteArray>()
    override suspend fun load(key: TokenKey): ByteArray? = map[key.storageKey]?.copyOf()
    override suspend fun save(key: TokenKey, value: ByteArray) { map[key.storageKey] = value.copyOf() }
    override suspend fun delete(key: TokenKey) { map.remove(key.storageKey) }
}

internal class MemorySessions : AuthorizationSessionStore {
    val map = ConcurrentHashMap<String, PendingAuthorization>()
    override suspend fun save(session: PendingAuthorization) { map[session.state] = session }
    override suspend fun consume(state: String): PendingAuthorization? = map.remove(state)
}

internal class TestClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
}

internal class Connected(val id: MailboxId, val email: MailAddress, val endpoint: ImapEndpoint, val tokens: AccessTokenSource)

internal class RecordingConnector : MailboxConnector {
    val calls = mutableListOf<Connected>()
    override suspend fun connect(
        id: MailboxId, email: MailAddress, endpoint: ImapEndpoint, tokens: AccessTokenSource, options: MailboxOptions,
    ): Mailbox {
        calls += Connected(id, email, endpoint, tokens)
        return java.lang.reflect.Proxy.newProxyInstance(Mailbox::class.java.classLoader, arrayOf(Mailbox::class.java)) { _, _, _ ->
            throw UnsupportedOperationException()
        } as Mailbox
    }
}

internal fun idToken(email: String, aud: String): String {
    fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    return "${b64("""{"alg":"none"}""")}.${b64("""{"email":"$email","aud":"$aud"}""")}.sig"
}

internal class FakeGoogle(var email: String = "alice@gmail.com", var aud: String = "client-1") : GoogleTokenEndpoint {
    var exchangeFailure: Throwable? = null
    var refreshFailure: Throwable? = null
    var refreshToken: String? = "refresh-1"
    var rotated: String? = null
    val exchanges = mutableListOf<Triple<String, URI, String>>()
    val refreshes = mutableListOf<String>()

    override suspend fun exchangeCode(code: String, redirectUri: URI, verifier: String): GoogleTokens {
        exchanges += Triple(code, redirectUri, verifier)
        exchangeFailure?.let { throw it }
        return GoogleTokens("access-x", 3600, refreshToken, idToken(email, aud))
    }

    override suspend fun refresh(refreshToken: String): GoogleTokens {
        refreshes += refreshToken
        refreshFailure?.let { throw it }
        return GoogleTokens("access-${refreshes.size}", 3600, rotated, null)
    }
}
