package dev.reapermaga.mailkt.auth

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileReader

class FileTokenPersistenceStorage(val username: String) : TokenPersistenceStorage {

    private val file = File("oauth2_tokens.json")

    override fun store(token: String) {
        when (file.exists()) {
            true -> FileReader(file).use {
                val existingTokens = Json.decodeFromString<Map<String, String>>(it.readText()).toMutableMap()
                existingTokens[username] = token
                File("oauth2_tokens.json").apply {
                    writeText(Json.encodeToString(existingTokens))
                }
            }

            false -> {
                val newTokens = mapOf(username to token)
                File("oauth2_tokens.json").apply {
                    writeText(Json.encodeToString(newTokens))
                }
            }
        }
    }

    override fun load(): String? {
        if (!file.exists()) return null
        FileReader(file).use {
            val existingTokens = Json.decodeFromString<Map<String, String>>(it.readText())
            return existingTokens[username]
        }
    }
}
