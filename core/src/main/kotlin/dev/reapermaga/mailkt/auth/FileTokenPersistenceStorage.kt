package dev.reapermaga.mailkt.auth

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileReader

/**
 * Simple JSON-file token store intended for local experimentation rather than production use.
 * Persists tokens per user inside a shared `oauth2_tokens.json`.
 */
class FileTokenPersistenceStorage(val username: String, fileName: String = "oauth2_tokens.json") :
    TokenPersistenceStorage {

    private val file = File(fileName)

    /**
     * Reads the current JSON map, updates the entry for [username], and rewrites the file in plain
     * text.
     */
    override fun store(token: String) {
        when (file.exists()) {
            true ->
                FileReader(file).use {
                    val existingTokens =
                        Json.decodeFromString<Map<String, String>>(it.readText()).toMutableMap()
                    existingTokens[username] = token
                    File("oauth2_tokens.json").apply {
                        writeText(Json.encodeToString(existingTokens))
                    }
                }

            false -> {
                val newTokens = mapOf(username to token)
                File("oauth2_tokens.json").apply { writeText(Json.encodeToString(newTokens)) }
            }
        }
    }

    /** Loads the JSON map from disk and returns the token for [username], or null if absent. */
    override fun load(): String? {
        if (!file.exists()) return null
        FileReader(file).use {
            val existingTokens = Json.decodeFromString<Map<String, String>>(it.readText())
            return existingTokens[username]
        }
    }
}
