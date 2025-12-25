package dev.reapermaga.mailkt.outlook

import com.microsoft.aad.msal4j.*
import dev.reapermaga.mailkt.auth.OAuth2MailAuth
import dev.reapermaga.mailkt.auth.OAuth2MailResult
import dev.reapermaga.mailkt.auth.TokenPersistenceStorage
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class OutlookOAuth2MailAuth(
    clientId: String,
    val tokenPersistenceStorage: TokenPersistenceStorage? = null
) : OAuth2MailAuth {

    private val authority = "https://login.microsoftonline.com/consumers"
    private val scopes = setOf("https://outlook.office.com/IMAP.AccessAsUser.All")

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

    fun hasToken(): CompletableFuture<Boolean> = CompletableFuture.supplyAsync {
        runCatching {
            val accounts = app.accounts.join()
            accounts.isNotEmpty()
        }.getOrDefault(false)
    }

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

data class OutlookOAuth2Verification(
    val verificationUri: String,
    val code: String
)