package dev.reapermaga.mailkt.outlook

import com.microsoft.aad.msal4j.AuthorizationCodeParameters
import com.microsoft.aad.msal4j.AuthorizationRequestUrlParameters
import com.microsoft.aad.msal4j.ClientCredentialFactory
import com.microsoft.aad.msal4j.ConfidentialClientApplication
import com.microsoft.aad.msal4j.ITokenCacheAccessAspect
import com.microsoft.aad.msal4j.ITokenCacheAccessContext
import com.microsoft.aad.msal4j.MsalInteractionRequiredException
import com.microsoft.aad.msal4j.MsalServiceException
import com.microsoft.aad.msal4j.ResponseMode
import com.microsoft.aad.msal4j.SilentParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutionException

/** MSAL4J confidential-client implementation. Each call uses an app instance backed by an in-memory cache. */
internal class MsalJBackend(private val config: OutlookConfig) : MsalBackend {
    private class MemoryCache(var data: String?) : ITokenCacheAccessAspect {
        var changed = false
        override fun beforeCacheAccess(ctx: ITokenCacheAccessContext) {
            data?.let { ctx.tokenCache().deserialize(it) }
        }
        override fun afterCacheAccess(ctx: ITokenCacheAccessContext) {
            if (ctx.hasCacheChanged()) {
                data = ctx.tokenCache().serialize()
                changed = true
            }
        }
    }

    private fun app(cache: MemoryCache): ConfidentialClientApplication =
        ConfidentialClientApplication.builder(
            config.clientId,
            ClientCredentialFactory.createFromSecret(config.clientSecret),
        ).authority(config.authority).setTokenCacheAccessAspect(cache).build()

    override fun authorizationUrl(redirectUri: URI, state: String, codeChallenge: String, loginHint: String): URI {
        val params = AuthorizationRequestUrlParameters.builder(redirectUri.toString(), config.scopes)
            .responseMode(ResponseMode.QUERY)
            .state(state)
            .codeChallenge(codeChallenge)
            .codeChallengeMethod("S256")
            .loginHint(loginHint)
            .build()
        return app(MemoryCache(null)).getAuthorizationRequestUrl(params).toURI()
    }

    override suspend fun exchange(code: String, redirectUri: URI, verifier: String): MsalAccount =
        withContext(Dispatchers.IO) {
            val cache = MemoryCache(null)
            val params = AuthorizationCodeParameters.builder(code, redirectUri)
                .scopes(config.scopes).codeVerifier(verifier).build()
            val result = translate { app(cache).acquireToken(params).get() }
            MsalAccount(result.account()?.username(), (cache.data ?: "").toByteArray())
        }

    override suspend fun silent(username: String, cache: ByteArray): MsalSilentResult = withContext(Dispatchers.IO) {
        val memory = MemoryCache(String(cache))
        val app = app(memory)
        val account = translate { app.accounts.get() }
            .firstOrNull { it.username().equals(username, ignoreCase = true) }
            ?: throw MsalInteractionRequired()
        val result = translate { app.acquireTokenSilently(SilentParameters.builder(config.scopes, account).build()).get() }
        MsalSilentResult(
            result.accessToken(),
            result.expiresOnDate().toInstant(),
            if (memory.changed) memory.data?.toByteArray() else null,
        )
    }

    /** Maps MSAL failures: interaction-required to [MsalInteractionRequired], everything else to IOException. */
    private inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: ExecutionException) {
        throw map(e.cause ?: e)
    } catch (e: MsalInteractionRequiredException) {
        throw MsalInteractionRequired()
    }

    private fun map(t: Throwable): Exception = when {
        t is MsalInteractionRequiredException -> MsalInteractionRequired()
        t is MsalServiceException && t.errorCode() in setOf("invalid_grant", "interaction_required") ->
            MsalInteractionRequired()
        else -> IOException("Microsoft token request failed: ${t.javaClass.simpleName}")
    }
}
