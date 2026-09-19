package dev.reapermaga.mailkt.gmail

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl
import com.google.api.client.auth.oauth2.BearerToken
import com.google.api.client.auth.oauth2.ClientParametersAuthentication
import com.google.api.client.auth.oauth2.StoredCredential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.Key
import com.google.api.client.util.store.AbstractDataStore
import com.google.api.client.util.store.AbstractDataStoreFactory
import com.google.api.client.util.store.DataStore
import com.google.api.client.util.store.DataStoreFactory
import dev.reapermaga.mailkt.auth.OAuth2Credentials
import dev.reapermaga.mailkt.auth.OAuth2MailAuth
import dev.reapermaga.mailkt.auth.TokenPersistenceStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.Serializable

/**
 * Authenticates a Gmail account with Google's installed-application OAuth2 flow. A browser is opened
 * for consent when no refresh token is cached; Google then redirects to a temporary loopback server.
 */
class GmailOAuth2MailAuth(
    val config: GmailOAuth2Config,
    val tokenPersistenceStorage: TokenPersistenceStorage? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OAuth2MailAuth {

    private val transport = NetHttpTransport()
    private val jsonFactory: JsonFactory = GsonFactory.getDefaultInstance()
    private val flow: AuthorizationCodeFlow = createFlow()

    /** Returns whether a credential is available in the configured token storage. */
    suspend fun hasToken(): Boolean =
        runInterruptible(ioDispatcher) { flow.loadCredential(USER_ID) != null }

    /**
     * Loads and refreshes a cached credential or opens the system browser for first-time consent.
     */
    override suspend fun login(): OAuth2Credentials = loginInternal(null)

    /**
     * Runs the installed-app flow and supplies the consent URL to [onAuthorizationUrl]. The
     * callback is responsible for displaying or opening the URL.
     */
    suspend fun login(onAuthorizationUrl: (String) -> Unit): OAuth2Credentials =
        loginInternal(onAuthorizationUrl)

    private suspend fun loginInternal(
        onAuthorizationUrl: ((String) -> Unit)?
    ): OAuth2Credentials = runInterruptible(ioDispatcher) {
        val credential =
            installedApp(onAuthorizationUrl).authorize(
                if (tokenPersistenceStorage == null) null else USER_ID
            )
        val request =
            transport
                .createRequestFactory(credential)
                .buildGetRequest(GenericUrl(USER_INFO_URL))
        val response = request.execute()
        val userInfo: GmailUserInfo =
            try {
                jsonFactory.fromInputStream(response.content, GmailUserInfo::class.java)
            } finally {
                response.disconnect()
            }
        val email = requireNotNull(userInfo.email) { "Google did not return an email address" }
        val accessToken =
            requireNotNull(credential.accessToken) { "Google did not return an access token" }
        OAuth2Credentials(username = email, accessToken = accessToken)
    }

    private fun createFlow(): AuthorizationCodeFlow {
        val builder =
            AuthorizationCodeFlow.Builder(
                    BearerToken.authorizationHeaderAccessMethod(),
                    transport,
                    jsonFactory,
                    GenericUrl(TOKEN_URL),
                    ClientParametersAuthentication(config.clientId, config.clientSecret),
                    config.clientId,
                    AUTHORIZATION_URL,
                )
                .enablePKCE()
                .setScopes(config.scopes)

        tokenPersistenceStorage?.let {
            builder.setDataStoreFactory(TokenPersistenceDataStoreFactory(it))
        }
        return builder.build()
    }

    private fun installedApp(onAuthorizationUrl: ((String) -> Unit)?): AuthorizationCodeInstalledApp {
        val receiver =
            LocalServerReceiver.Builder()
                .setHost(config.callbackHost)
                .setPort(config.callbackPort)
                .setCallbackPath(config.callbackPath)
                .build()
        val browser =
            onAuthorizationUrl?.let { callback ->
                AuthorizationCodeInstalledApp.Browser { url -> callback(url) }
            } ?: AuthorizationCodeInstalledApp.DefaultBrowser()

        return object : AuthorizationCodeInstalledApp(flow, receiver, browser) {
            override fun onAuthorization(authorizationUrl: AuthorizationCodeRequestUrl) {
                authorizationUrl.set("access_type", "offline")
                authorizationUrl.set("include_granted_scopes", true)
                authorizationUrl.set("prompt", "consent")
                super.onAuthorization(authorizationUrl)
            }
        }
    }

    // Must be accessible outside this module: google-http-client's JsonParser instantiates this
    // class and populates its @Key field via reflection, which fails against a private nested class
    // or a Kotlin-generated private backing field.
    internal class GmailUserInfo {
        @JvmField @Key var email: String? = null
    }

    companion object {
        private const val USER_ID = "gmail"
        private const val AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val USER_INFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
    }
}

private class TokenPersistenceDataStoreFactory(
    private val storage: TokenPersistenceStorage,
) : AbstractDataStoreFactory() {
    override fun <V : Serializable> createDataStore(id: String): DataStore<V> {
        require(id == StoredCredential.DEFAULT_DATA_STORE_ID) { "Unsupported data store: $id" }
        @Suppress("UNCHECKED_CAST")
        return TokenPersistenceDataStore(this, id, storage) as DataStore<V>
    }
}

private class TokenPersistenceDataStore(
    factory: DataStoreFactory,
    id: String,
    private val storage: TokenPersistenceStorage,
) : AbstractDataStore<StoredCredential>(factory, id) {

    @Synchronized
    override fun keySet(): Set<String> = readEntry()?.let { setOf(it.first) } ?: emptySet()

    @Synchronized
    override fun values(): Collection<StoredCredential> =
        readEntry()?.let { listOf(it.second) } ?: emptyList()

    @Synchronized
    override fun get(key: String): StoredCredential? =
        readEntry()?.takeIf { it.first == key }?.second

    @Synchronized
    override fun set(key: String, value: StoredCredential): DataStore<StoredCredential> {
        storage.store(
            buildJsonObject {
                    put("key", key)
                    value.accessToken?.let { put("accessToken", it) }
                    value.refreshToken?.let { put("refreshToken", it) }
                    value.expirationTimeMilliseconds?.let { put("expirationTimeMilliseconds", it) }
                }
                .toString()
        )
        return this
    }

    @Synchronized
    override fun clear(): DataStore<StoredCredential> {
        storage.store(EMPTY_STORE)
        return this
    }

    @Synchronized
    override fun delete(key: String): DataStore<StoredCredential> {
        if (readEntry()?.first == key) storage.store(EMPTY_STORE)
        return this
    }

    private fun readEntry(): Pair<String, StoredCredential>? {
        val serialized = storage.load()?.takeUnless { it == EMPTY_STORE } ?: return null
        try {
            val json = Json.parseToJsonElement(serialized).jsonObject
            val key = json["key"]?.jsonPrimitive?.content ?: return null
            val credential =
                StoredCredential()
                    .setAccessToken(json["accessToken"]?.jsonPrimitive?.content)
                    .setRefreshToken(json["refreshToken"]?.jsonPrimitive?.content)
                    .setExpirationTimeMilliseconds(
                        json["expirationTimeMilliseconds"]?.jsonPrimitive?.longOrNull
                    )
            return key to credential
        } catch (exception: Exception) {
            throw IOException("Unable to read the persisted Gmail credential", exception)
        }
    }

    companion object {
        private const val EMPTY_STORE = "{}"
    }
}
