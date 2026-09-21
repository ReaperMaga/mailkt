package dev.reapermaga.mailkt.gmail

import dev.reapermaga.mailkt.client.AccessTokenSource
import dev.reapermaga.mailkt.client.TokenStore
import dev.reapermaga.mailkt.model.MailException
import dev.reapermaga.mailkt.model.RecoveryReason
import dev.reapermaga.mailkt.model.TokenKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Clock
import java.time.Instant

/** Serialization of the Gmail adapter's opaque token payload. */
internal object GmailTokenPayload {
    fun encode(refreshToken: String): ByteArray =
        buildJsonObject { put("refresh_token", refreshToken) }.toString().toByteArray()

    fun decode(bytes: ByteArray): String? = try {
        (Json.parseToJsonElement(String(bytes)).jsonObject["refresh_token"] as? JsonPrimitive)?.contentOrNull
    } catch (_: Exception) {
        null
    }
}

/**
 * Silent refresh only: never opens a browser. Unusable credentials become
 * [MailException.AuthenticationRequired]; transient failures become recoverable connection failures.
 */
internal class GmailAccessTokens(
    private val key: TokenKey,
    private val store: TokenStore,
    private val endpoint: GoogleTokenEndpoint,
    private val clock: Clock,
) : AccessTokenSource {
    private val mutex = Mutex()
    private var cached: String? = null
    private var expiresAt: Instant = Instant.MIN

    override suspend fun accessToken(): String = mutex.withLock {
        cached?.takeIf { clock.instant().isBefore(expiresAt.minusSeconds(60)) }?.let { return it }
        val refreshToken = store.load(key)?.let(GmailTokenPayload::decode)
            ?: throw MailException.AuthenticationRequired()
        val tokens = try {
            endpoint.refresh(refreshToken)
        } catch (e: GoogleTokenException) {
            if (e.invalidGrant) throw MailException.AuthenticationRequired(cause = e)
            throw MailException.ConnectionFailed(RecoveryReason.NETWORK, e)
        } catch (e: IOException) {
            throw MailException.ConnectionFailed(RecoveryReason.NETWORK, e)
        }
        tokens.refreshToken?.takeIf { it != refreshToken }?.let { store.save(key, GmailTokenPayload.encode(it)) }
        cached = tokens.accessToken
        expiresAt = clock.instant().plusSeconds(tokens.expiresInSeconds)
        tokens.accessToken
    }
}
