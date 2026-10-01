package dev.reapermaga.mailkt.gmail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class GoogleTokens(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String?,
    val idToken: String?,
)

/** Token endpoint failure. [invalidGrant] means the credential is unusable and reauthorization is required. */
internal class GoogleTokenException(val invalidGrant: Boolean) : Exception("Google token request failed")

/** Port over Google's token endpoint so the flow is testable with fakes. */
internal interface GoogleTokenEndpoint {
    suspend fun exchangeCode(code: String, redirectUri: URI, verifier: String): GoogleTokens
    suspend fun refresh(refreshToken: String): GoogleTokens
}

internal class HttpGoogleTokenEndpoint(private val config: GmailConfig) : GoogleTokenEndpoint {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override suspend fun exchangeCode(code: String, redirectUri: URI, verifier: String) = post(
        "grant_type" to "authorization_code", "code" to code,
        "redirect_uri" to redirectUri.toString(), "code_verifier" to verifier,
    )

    override suspend fun refresh(refreshToken: String) =
        post("grant_type" to "refresh_token", "refresh_token" to refreshToken)

    private suspend fun post(vararg params: Pair<String, String>): GoogleTokens = withContext(Dispatchers.IO) {
        val all = params.toList() + listOf("client_id" to config.clientId, "client_secret" to config.clientSecret)
        val body = all.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val json = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        if (response.statusCode() != 200 || json == null) {
            val invalid = response.statusCode() in 400..401 && json.str("error") == "invalid_grant"
            throw GoogleTokenException(invalid)
        }
        GoogleTokens(
            accessToken = json.str("access_token") ?: throw GoogleTokenException(false),
            expiresInSeconds = (json["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600,
            refreshToken = json.str("refresh_token"),
            idToken = json.str("id_token"),
        )
    }

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
}
