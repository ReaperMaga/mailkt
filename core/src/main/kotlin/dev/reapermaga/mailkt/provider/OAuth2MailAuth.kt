package dev.reapermaga.mailkt.provider

import java.util.concurrent.CompletableFuture

interface OAuth2MailAuth {

    fun login(): CompletableFuture<OAuth2MailUser>
}

data class OAuth2MailUser(
    val username: String? = null,
    val accessToken: String? = null,
    val error: Throwable? = null
)
