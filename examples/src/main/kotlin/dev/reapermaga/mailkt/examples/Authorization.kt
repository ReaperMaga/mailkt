package dev.reapermaga.mailkt.examples

import dev.reapermaga.mailkt.client.Mailbox
import dev.reapermaga.mailkt.client.MailboxOptions
import dev.reapermaga.mailkt.gmail.Gmail
import dev.reapermaga.mailkt.gmail.GmailConfig
import dev.reapermaga.mailkt.model.AuthorizationCallback
import dev.reapermaga.mailkt.model.MailAddress
import dev.reapermaga.mailkt.outlook.Outlook
import dev.reapermaga.mailkt.outlook.OutlookConfig
import java.net.URI

// region gmail-setup
fun gmailClient(tokens: InMemoryTokenStore, sessions: InMemoryAuthorizationSessionStore): Gmail {
    val config = GmailConfig(
        clientId = System.getenv("GMAIL_CLIENT_ID"),
        clientSecret = System.getenv("GMAIL_CLIENT_SECRET"),
        // Exact backend callback URIs registered with Google for this environment.
        allowedRedirectUris = setOf(
            URI("https://app.example.com/oauth/gmail/callback"),
            URI("http://localhost:8080/oauth/gmail/callback"), // local development
        ),
    )
    return Gmail(config, tokens, sessions)
}
// endregion

// region gmail-begin
/** Backend endpoint: returns the URL the frontend navigates the browser to. */
suspend fun beginGmailAuthorization(gmail: Gmail, email: String): String {
    val request = gmail.beginAuthorization(
        expectedEmail = MailAddress(email),
        redirectUri = URI("https://app.example.com/oauth/gmail/callback"),
    )
    return request.authorizationUrl.toString()
}
// endregion

// region gmail-callback
/** Backend route registered as the redirect URI: hand the query parameters to MailKT. */
suspend fun gmailCallback(gmail: Gmail, code: String?, state: String?, error: String?): Mailbox {
    val id = gmail.completeAuthorization(AuthorizationCallback(code, state, error))
    println("Authorized mailbox of provider ${id.provider}")
    return gmail.open(MailAddress(requireNotNull(System.getenv("GMAIL_ADDRESS"))), MailboxOptions())
}
// endregion

// region outlook-setup
fun outlookClient(tokens: InMemoryTokenStore, sessions: InMemoryAuthorizationSessionStore): Outlook {
    val config = OutlookConfig(
        clientId = System.getenv("OUTLOOK_CLIENT_ID"),
        clientSecret = System.getenv("OUTLOOK_CLIENT_SECRET"), // held by the backend only
        allowedRedirectUris = setOf(URI("https://app.example.com/oauth/outlook/callback")),
        enableSending = true, // adds the SMTP.Send scope
    )
    return Outlook(config, tokens, sessions)
}
// endregion

// region outlook-flow
suspend fun outlookAuthorizeAndOpen(outlook: Outlook, email: String, code: String, state: String): Mailbox {
    // 1. The backend creates the browser URL for the frontend.
    val request = outlook.beginAuthorization(MailAddress(email), URI("https://app.example.com/oauth/outlook/callback"))
    println("Send the browser to ${request.authorizationUrl}")
    // 2. The provider redirects to the backend callback, which completes the flow.
    outlook.completeAuthorization(AuthorizationCallback(code = code, state = state))
    // 3. Open the mailbox using the stored tokens.
    return outlook.open(MailAddress(email))
}
// endregion
