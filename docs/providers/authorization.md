# Hosted authorization

Gmail and Outlook both use the same split frontend/backend **authorization-code flow with PKCE**. MailKT does the cryptography and validation; your backend owns the HTTP routes and the browser redirect.

<AuthFlowDiagram />

## The four steps

1. **Begin.** Your backend calls `beginAuthorization(expectedEmail, redirectUri)` and returns `request.authorizationUrl` to the frontend, which navigates the browser there.
2. **Redirect.** The provider sends the browser to a callback route you registered with the provider: HTTPS in production, or a provider-supported loopback such as `http://localhost:8080/oauth/{provider}/callback` during development. MailKT never registers or serves this route.
3. **Complete.** The callback handler turns the query parameters into an `AuthorizationCallback(code, state, error)` and calls `completeAuthorization`. MailKT validates the one-time state, expiry, redirect URI, provider response and PKCE verifier, verifies that the authenticated account is the requested mailbox, and only then stores the tokens.
4. **Open.** `open(email)` builds the `Mailbox` from the stored tokens using silent refresh only.

## Security properties

| Check | Failure |
|---|---|
| Redirect URI is in the configured allowlist | `REDIRECT_NOT_ALLOWED` |
| `state` exists and is consumed exactly once | `UNKNOWN_STATE` |
| Pending session is younger than ten minutes | `EXPIRED` |
| Provider returned a code, not an `error` | `PROVIDER_ERROR` |
| Code exchange with PKCE verifier succeeded | `EXCHANGE_FAILED` |
| Authenticated account equals the expected email | `WRONG_ACCOUNT` |
| Session store reachable | `STORE_FAILED` |

Each failure is thrown as `MailException.AuthorizationFailed(reason)`.

::: warning Never trust a redirect URI from the frontend
Configuration holds an **explicit allowlist** of redirect URIs and `beginAuthorization` rejects anything else. Redirect URIs must be `https`, or `http` on a loopback host, without query or fragment.
:::

## Mailbox identity

The expected email is the mailbox's user-facing identity. MailKT derives the `MailboxId` from the provider and the provider-canonical authenticated address, so the same address on two providers never collides. To switch accounts, open a different email explicitly. There is no implicit "current account".

## Re-authorization

When a refresh token is revoked or expires, the mailbox moves to `MailboxState.AuthenticationRequired`. MailKT never starts a browser flow on its own. Run the same hosted flow again for that email, then call `mailbox.reconnect()`. See [Mailbox lifecycle](/guide/lifecycle#reconnecting-after-re-authorization).

## Wiring it into a web framework

A sketch with Ktor; the same shape works with Spring, http4k or anything else:

```kotlin
routing {
    get("/oauth/gmail/start") {
        val email = call.request.queryParameters["email"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val request = gmail.beginAuthorization(MailAddress(email), URI("https://app.example.com/oauth/gmail/callback"))
        call.respondRedirect(request.authorizationUrl.toString())
    }

    get("/oauth/gmail/callback") {
        val p = call.request.queryParameters
        try {
            val id = gmail.completeAuthorization(AuthorizationCallback(p["code"], p["state"], p["error"]))
            mailboxes.connect(id) // your code: open and keep the Mailbox
            call.respondRedirect("/settings/mail?connected=1")
        } catch (e: MailException.AuthorizationFailed) {
            call.respondRedirect("/settings/mail?error=${e.reason}")
        }
    }
}
```

Continue with the provider pages for registration details: [Gmail](./gmail) and [Outlook](./outlook).
