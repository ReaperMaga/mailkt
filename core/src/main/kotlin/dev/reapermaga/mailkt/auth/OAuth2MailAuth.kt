package dev.reapermaga.mailkt.auth

/**
 * Defines the contract for fetching OAuth2 credentials used by a mail session.
 */
interface OAuth2MailAuth {

    /** Returns valid credentials or throws when authentication fails. */
    suspend fun login(): OAuth2Credentials
}

/** Valid OAuth2 credentials ready for an IMAP connection. */
data class OAuth2Credentials(
    val username: String,
    val accessToken: String,
) {
    init {
        require(username.isNotBlank()) { "username must not be blank" }
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
    }

    override fun toString(): String =
        "OAuth2Credentials(username=$username, accessToken=<redacted>)"
}
