package dev.reapermaga.mailkt.outlook

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
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

/** Fake MSAL: the serialized cache is "cache:<username>"; silent renewal only works for the exact cached account. */
internal class FakeMsal(var username: String = "alice@outlook.com") : MsalBackend {
    var exchangeFailure: Exception? = null
    var silentFailure: Exception? = null
    var changedCache: ByteArray? = null
    val exchanges = mutableListOf<Triple<String, URI, String>>()
    val silentCalls = mutableListOf<String>()

    override fun authorizationUrl(redirectUri: URI, state: String, codeChallenge: String, loginHint: String): URI {
        fun e(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        return URI(
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=c&redirect_uri=${e(redirectUri.toString())}" +
                "&state=$state&code_challenge=$codeChallenge&code_challenge_method=S256&login_hint=${e(loginHint)}",
        )
    }

    override suspend fun exchange(code: String, redirectUri: URI, verifier: String): MsalAccount {
        exchanges += Triple(code, redirectUri, verifier)
        exchangeFailure?.let { throw it }
        return MsalAccount(username, "cache:$username".toByteArray())
    }

    override suspend fun silent(username: String, cache: ByteArray): MsalSilentResult {
        silentCalls += username
        silentFailure?.let { throw it }
        if (String(cache) != "cache:$username") throw MsalInteractionRequired()
        return MsalSilentResult("token-${silentCalls.size}", Instant.parse("2026-01-01T01:00:00Z"), changedCache)
    }
}

internal val NETWORK_DOWN = IOException("down")
