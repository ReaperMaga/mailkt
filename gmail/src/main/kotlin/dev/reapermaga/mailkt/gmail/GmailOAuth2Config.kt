package dev.reapermaga.mailkt.gmail

/** Configuration for Google's installed-application OAuth2 flow. */
data class GmailOAuth2Config(
    val clientId: String,
    val clientSecret: String,
    val scopes: Set<String> = DEFAULT_SCOPES,
    val callbackHost: String = "localhost",
    val callbackPort: Int = -1,
    val callbackPath: String = "/Callback",
) {
    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(clientSecret.isNotBlank()) { "clientSecret must not be blank" }
        require(MAIL_SCOPE in scopes) { "Gmail IMAP authentication requires the $MAIL_SCOPE scope" }
        require(callbackPort == -1 || callbackPort in 1..65535) {
            "callbackPort must be -1 or a valid TCP port"
        }
        require(callbackPath.startsWith('/')) { "callbackPath must start with /" }
    }

    override fun toString(): String =
        "GmailOAuth2Config(clientId=$clientId, clientSecret=<redacted>, scopes=$scopes, " +
            "callbackHost=$callbackHost, callbackPort=$callbackPort, callbackPath=$callbackPath)"

    companion object {
        const val MAIL_SCOPE = "https://mail.google.com/"
        const val OPEN_ID_SCOPE = "openid"
        const val EMAIL_SCOPE = "email"

        val DEFAULT_SCOPES = setOf(MAIL_SCOPE, OPEN_ID_SCOPE, EMAIL_SCOPE)

        /**
         * Creates the standard configuration for a Google OAuth desktop application. Each login
         * uses an available ephemeral callback port, allowing independent auth instances to run
         * concurrently.
         */
        fun installedApp(clientId: String, clientSecret: String) =
            GmailOAuth2Config(clientId = clientId, clientSecret = clientSecret)
    }
}
