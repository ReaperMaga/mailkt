package dev.reapermaga.mailkt.outlook

import com.microsoft.aad.msal4j.DeviceCodeFlowParameters
import com.microsoft.aad.msal4j.ITokenCacheAccessAspect
import com.microsoft.aad.msal4j.ITokenCacheAccessContext
import com.microsoft.aad.msal4j.PublicClientApplication
import com.microsoft.aad.msal4j.SilentParameters
import dev.reapermaga.mailkt.auth.OAuth2Credentials
import dev.reapermaga.mailkt.auth.OAuth2MailAuth
import dev.reapermaga.mailkt.auth.TokenPersistenceStorage
import kotlinx.coroutines.future.await

/** Outlook OAuth2 authentication backed by MSAL's device-code and silent refresh flows. */
class OutlookOAuth2MailAuth(
    val config: OutlookOAuth2Config,
    val tokenPersistenceStorage: TokenPersistenceStorage? = null,
) : OAuth2MailAuth {

    private val app: PublicClientApplication =
        PublicClientApplication.builder(config.clientId)
            .authority(config.authority)
            .setTokenCacheAccessAspect(
                object : ITokenCacheAccessAspect {
                    override fun beforeCacheAccess(context: ITokenCacheAccessContext) {
                        tokenPersistenceStorage?.load()?.let(context.tokenCache()::deserialize)
                    }

                    override fun afterCacheAccess(context: ITokenCacheAccessContext) {
                        if (context.hasCacheChanged()) {
                            tokenPersistenceStorage?.store(context.tokenCache().serialize())
                        }
                    }
                }
            )
            .build()

    /** Returns whether MSAL has at least one cached account. Storage errors are propagated. */
    suspend fun hasToken(): Boolean = app.accounts.await().isNotEmpty()

    /** Performs device-code login and returns the acquired credentials. */
    suspend fun deviceLogin(
        onVerification: (OutlookOAuth2Verification) -> Unit
    ): OAuth2Credentials {
        val parameters =
            DeviceCodeFlowParameters.builder(config.scopes) { code ->
                    onVerification(
                        OutlookOAuth2Verification(
                            verificationUri = code.verificationUri(),
                            code = code.userCode(),
                        )
                    )
                }
                .build()
        val token = app.acquireToken(parameters).await()
        return OAuth2Credentials(
            username = requireNotNull(token.account().username()) { "MSAL returned no username" },
            accessToken = requireNotNull(token.accessToken()) { "MSAL returned no access token" },
        )
    }

    /** Silently refreshes credentials for the first cached account. */
    override suspend fun login(): OAuth2Credentials {
        val account = app.accounts.await().firstOrNull() ?: error("No Outlook account is logged in")
        val token =
            app.acquireTokenSilently(SilentParameters.builder(config.scopes, account).build()).await()
        return OAuth2Credentials(
            username = requireNotNull(token.account().username()) { "MSAL returned no username" },
            accessToken = requireNotNull(token.accessToken()) { "MSAL returned no access token" },
        )
    }
}

data class OutlookOAuth2Verification(val verificationUri: String, val code: String)
