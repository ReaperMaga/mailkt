package dev.reapermaga.mailkt.outlook

import com.microsoft.aad.msal4j.DeviceCodeFlowParameters
import com.microsoft.aad.msal4j.InteractiveRequestParameters
import com.microsoft.aad.msal4j.PublicClientApplication
import com.microsoft.aad.msal4j.SilentParameters
import dev.reapermaga.mailkt.provider.OAuth2MailAuth
import dev.reapermaga.mailkt.provider.OAuth2MailUser
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class OutlookOAuth2MailAuth(val clientId: String, val verificationConsumer: Consumer<OutlookOAuth2Verification>) : OAuth2MailAuth {
    private val authority = "https://login.microsoftonline.com/consumers"
    private val scope = "https://outlook.office.com/IMAP.AccessAsUser.All"

    override fun login(): CompletableFuture<OAuth2MailUser> {
        try {
            val app = PublicClientApplication
                .builder(clientId)
                .authority(authority)
                .build()
            val deviceParams = DeviceCodeFlowParameters
                .builder(setOf(scope)) {
                    verificationConsumer.accept(OutlookOAuth2Verification(
                        verificationUri = it.verificationUri(),
                        code = it.userCode()
                    ))
                }
                .build()
            val future = CompletableFuture<OAuth2MailUser>()
            val tokenFuture = app.acquireToken(deviceParams)
            tokenFuture.exceptionally {
                it.printStackTrace()
                future.complete(
                    OAuth2MailUser(
                        error = it
                    )
                )
                null
            }
            tokenFuture.thenAccept { result ->
                val username = result.account().username()
                val token = result.accessToken()
                future.complete(
                    OAuth2MailUser(
                        username = username,
                        accessToken = token
                    )
                )
            }
            return future
        } catch (ex: Exception) {
            val future = CompletableFuture<OAuth2MailUser>()
            future.complete(
                OAuth2MailUser(
                    error = ex
                )
            )
            return future
        }
    }
}

data class OutlookOAuth2Verification(
    val verificationUri: String,
    val code: String
)