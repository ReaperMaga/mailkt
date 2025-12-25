package dev.reapermaga.mailkt.outlook

import com.microsoft.aad.msal4j.*
import dev.reapermaga.mailkt.auth.OAuth2MailAuth
import dev.reapermaga.mailkt.auth.OAuth2MailResult
import dev.reapermaga.mailkt.auth.TokenPersistenceStorage
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Provides OAuth2 device-code authentication against Outlook consumer accounts using MSAL,
 * optionally persisting the token cache via [TokenPersistenceStorage].
 */
class OutlookOAuth2MailAuth(
    clientId: String,
    val tokenPersistenceStorage: TokenPersistenceStorage? = null
) : OAuth2MailAuth {

    private val authority = "https://login.microsoftonline.com/consumers"
    private val scopes = setOf("https://outlook.office.com/IMAP.AccessAsUser.All")

    /**
     * MSAL public client configured for the consumer authority, wired to optionally persist its cache.
     */
    val app: PublicClientApplication = PublicClientApplication
        .builder(clientId)
        .authority(authority)
        .setTokenCacheAccessAspect(object : ITokenCacheAccessAspect {
            override fun beforeCacheAccess(ctx: ITokenCacheAccessContext) {
                val token = tokenPersistenceStorage?.load() ?: return
                ctx.tokenCache().deserialize(token)
            }

            override fun afterCacheAccess(ctx: ITokenCacheAccessContext) {
                if (ctx.hasCacheChanged() && tokenPersistenceStorage != null) {
                    val token = ctx.tokenCache().serialize()
                    tokenPersistenceStorage.store(token)
                }
            }
        })
        .build()

    /**
     * Returns true when MSAL already holds at least one cached account.
     */
    fun hasToken(): CompletableFuture<Boolean> = CompletableFuture.supplyAsync {
        runCatching {
            val accounts = app.accounts.join()
            accounts.isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * Starts the device-code flow and completes with the verification details.
     */
    fun deviceLogin(): CompletableFuture<OutlookOAuth2Verification> {
        val future = CompletableFuture<OutlookOAuth2Verification>()
        deviceLogin { verification ->
            future.complete(verification)
        }
        return future
    }

    /**
     * Runs the device-code flow, streaming verification data to [verificationConsumer],
     * and completes with the resulting [OAuth2MailResult].
     */
    fun deviceLogin(verificationConsumer: Consumer<OutlookOAuth2Verification>): CompletableFuture<OAuth2MailResult> =
        CompletableFuture.supplyAsync {
            try {
                val deviceParams = DeviceCodeFlowParameters
                    .builder(scopes) {
                        verificationConsumer.accept(
                            OutlookOAuth2Verification(
                                verificationUri = it.verificationUri(),
                                code = it.userCode()
                            )
                        )
                    }
                    .build()
                val token = app.acquireToken(deviceParams).join()
                OAuth2MailResult(
                    username = token.account().username(),
                    accessToken = token.accessToken(),
                )
            } catch (e: Exception) {
                OAuth2MailResult(
                    error = e
                )
            }
        }

    /**
     * Attempts a silent login using the cached account and wraps the outcome in [OAuth2MailResult].
     */
    override fun login(): CompletableFuture<OAuth2MailResult> = CompletableFuture.supplyAsync {
        try {
            val accounts = app.accounts.join()
            if (accounts.isEmpty()) error("No account logged in")
            val account = accounts.first()
            val silentParams = SilentParameters.builder(scopes, account).build()
            val token = app.acquireTokenSilently(silentParams).join()
            OAuth2MailResult(
                username = token.account().username(),
                accessToken = token.accessToken(),
            )
        } catch (ex: Exception) {
            OAuth2MailResult(
                error = ex
            )
        }
    }
}

/**
 * Verification payload returned during device-code authentication.
 */
data class OutlookOAuth2Verification(
    val verificationUri: String,
    val code: String
)