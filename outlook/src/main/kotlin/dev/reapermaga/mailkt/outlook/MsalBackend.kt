package dev.reapermaga.mailkt.outlook

import java.net.URI
import java.time.Instant

/** Result of exchanging an authorization code: authenticated username and serialized MSAL cache. */
internal class MsalAccount(val username: String?, val cache: ByteArray)

/** Access token from silent renewal; [cache] is non-null when MSAL changed the serialized cache. */
internal class MsalSilentResult(val accessToken: String, val expiresAt: Instant, val cache: ByteArray?)

/** Silent renewal impossible without user interaction (expired/revoked refresh token, unknown account). */
internal class MsalInteractionRequired : Exception("Interaction required")

/** Port over MSAL4J so the flow is testable with fakes. Transient failures surface as IOException. */
internal interface MsalBackend {
    fun authorizationUrl(redirectUri: URI, state: String, codeChallenge: String, loginHint: String): URI

    suspend fun exchange(code: String, redirectUri: URI, verifier: String): MsalAccount

    /** Renews for exactly [username]; never selects an arbitrary cached account. */
    suspend fun silent(username: String, cache: ByteArray): MsalSilentResult
}
