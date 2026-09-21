package dev.reapermaga.mailkt.gmail

import dev.reapermaga.mailkt.model.MailAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64

internal const val GMAIL_PROVIDER = "gmail"

internal class IdClaims(val email: String?, val audiences: List<String>)

internal object GmailIdentity {
    /** Provider-canonical address: lower-case; gmail.com/googlemail.com ignore dots and `+tag` in the local part. */
    fun canonical(address: MailAddress): MailAddress {
        val (local, domain) = address.normalized.split('@', limit = 2)
        if (domain != "gmail.com" && domain != "googlemail.com") return MailAddress(address.normalized)
        val cleaned = local.substringBefore('+').replace(".", "")
        return MailAddress("$cleaned@gmail.com")
    }

    /**
     * Reads claims of an ID token received directly from Google's token endpoint over TLS
     * (OpenID Connect permits skipping signature validation in that case).
     */
    fun claims(idToken: String?): IdClaims? = try {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        val obj = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))).jsonObject
        IdClaims(obj.string("email"), obj.audiences())
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.audiences(): List<String> = when (val aud = this["aud"]) {
        is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
        else -> emptyList()
    }
}
