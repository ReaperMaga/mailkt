package dev.reapermaga.mailkt.outlook

import com.microsoft.aad.msal4j.InteractiveRequestParameters
import com.microsoft.aad.msal4j.PublicClientApplication
import dev.reapermaga.mailkt.provider.OAuth2MailAuth
import dev.reapermaga.mailkt.provider.OAuth2MailUser
import java.net.URI
import java.util.concurrent.CompletableFuture

class OutlookOAuth2MailAuth(val clientId: String) : OAuth2MailAuth {
    private val authority = "https://login.microsoftonline.com/consumers"
    private val scope = "https://outlook.office.com/IMAP.AccessAsUser.All"

    override fun login(): CompletableFuture<OAuth2MailUser> {
        try {
            val app = PublicClientApplication
                .builder(clientId)
                .authority(authority)
                .build()
            val params = InteractiveRequestParameters
                .builder(URI("http://localhost:8080/"))
                .scopes(setOf(scope))
                .build()
            val future = CompletableFuture<OAuth2MailUser>()
            val tokenFuture = app.acquireToken(params)
            tokenFuture.exceptionally {
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