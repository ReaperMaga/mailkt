package dev.reapermaga.mailkt.auth

import java.util.concurrent.CompletableFuture

interface OAuth2MailAuth {

    fun login(): CompletableFuture<OAuth2MailResult>
}

data class OAuth2MailResult(
    val username: String? = null,
    val accessToken: String? = null,
    val error: Throwable? = null
) {
    val success get() = error == null && username != null && accessToken != null
}
