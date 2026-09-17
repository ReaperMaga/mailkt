package dev.reapermaga.mailkt.outlook

data class OutlookOAuth2Config(
    val clientId: String,
    val authority: String,
    val scopes: Set<String>,
) {
    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(authority.startsWith("https://")) { "authority must use HTTPS" }
        require(scopes.isNotEmpty()) { "scopes must not be empty" }
    }

    companion object {
        fun consumer(clientId: String, enableSending: Boolean = false) =
            OutlookOAuth2Config(
                clientId,
                "https://login.microsoftonline.com/consumers",
                buildSet {
                    add("https://outlook.office.com/IMAP.AccessAsUser.All")
                    if (enableSending) add("https://outlook.office.com/SMTP.Send")
                },
            )
    }
}
