package dev.reapermaga.mailkt.outlook

import com.microsoft.aad.msal4j.*
import dev.reapermaga.mailkt.auth.OAuth2MailAuth
import dev.reapermaga.mailkt.auth.OAuth2MailUser
import dev.reapermaga.mailkt.auth.TokenPersistenceStorage
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class OutlookOAuth2MailAuth(
    val clientId: String,
    val tokenPersistenceStorage: TokenPersistenceStorage? = null,
    val verificationConsumer: Consumer<OutlookOAuth2Verification>
) :
    OAuth2MailAuth {
    private val authority = "https://login.microsoftonline.com/consumers"
    private val scopes = setOf("https://outlook.office.com/IMAP.AccessAsUser.All")

    override fun login(): CompletableFuture<OAuth2MailUser> = CompletableFuture.supplyAsync {
        try {
            val app = PublicClientApplication
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
            val accounts = app.accounts.join()
            if (accounts.isNotEmpty()) {
                val account = accounts.first()
                val silentParams = SilentParameters.builder(scopes, account).build()
                val token = app.acquireTokenSilently(silentParams).join()
                return@supplyAsync OAuth2MailUser(
                    username = token.account().username(),
                    accessToken = token.accessToken(),
                )
            }
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
            OAuth2MailUser(
                username = token.account().username(),
                accessToken = token.accessToken(),
            )
        } catch (ex: Exception) {
            OAuth2MailUser(
                error = ex
            )
        }

    }
}

data class OutlookOAuth2Verification(
    val verificationUri: String,
    val code: String
)