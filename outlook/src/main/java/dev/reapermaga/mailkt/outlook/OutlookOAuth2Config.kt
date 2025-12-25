package dev.reapermaga.mailkt.outlook

data class OutlookOAuth2Config(
    val clientId: String,
    val authority: String,
    val scopes: Set<String>
) {
    companion object {
        fun consumer(clientId: String) = OutlookOAuth2Config(
            clientId,
            "https://login.microsoftonline.com/consumers",
            setOf("https://outlook.office.com/IMAP.AccessAsUser.All")
        )
    }
}