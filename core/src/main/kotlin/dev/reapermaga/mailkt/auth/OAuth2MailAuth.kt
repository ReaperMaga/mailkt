package dev.reapermaga.mailkt.auth

import java.util.concurrent.CompletableFuture

/**
 * Defines the contract for fetching OAuth2 credentials used by a mail session.
 *
 * @return a future completing with the authentication result.
 */
interface OAuth2MailAuth {

    /**
     * Initiates the login process and returns a future that will complete with the result.
     */
    fun login(): CompletableFuture<OAuth2MailResult>
}

/**
 * Encapsulates the outcome of an OAuth2 mail authentication attempt.
 *
 * @property username resolved account identifier when successful.
 * @property accessToken bearer token used to authorize SMTP/IMAP connections.
 * @property error optional failure describing why credentials could not be produced.
 */
data class OAuth2MailResult(
    val username: String? = null,
    val accessToken: String? = null,
    val error: Throwable? = null
) {
    /**
     * Indicates whether both the username and access token are present and no error occurred.
     */
    val success get() = error == null && username != null && accessToken != null
}
