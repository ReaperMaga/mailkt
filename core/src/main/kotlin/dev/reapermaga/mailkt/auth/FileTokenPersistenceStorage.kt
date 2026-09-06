package dev.reapermaga.mailkt.auth

import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Atomic, process-local thread-safe JSON token storage intended for desktop applications. */
class FileTokenPersistenceStorage(
    val username: String,
    fileName: String = "oauth2_tokens.json",
) : TokenPersistenceStorage {
    init {
        require(username.isNotBlank()) { "username must not be blank" }
    }

    private val path = Path.of(fileName).toAbsolutePath().normalize()
    private val lock = locks.computeIfAbsent(path) { ReentrantLock() }

    override fun store(token: String) {
        lock.withLock {
            val tokens = readTokens().toMutableMap()
            tokens[username] = token
            writeAtomically(Json.encodeToString(tokens))
        }
    }

    override fun load(): String? = lock.withLock { readTokens()[username] }

    private fun readTokens(): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        val content = Files.readString(path, StandardCharsets.UTF_8)
        if (content.isBlank()) return emptyMap()
        return Json.decodeFromString(content)
    }

    private fun writeAtomically(content: String) {
        val parent = path.parent ?: error("Token storage path must have a parent directory")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            runCatching {
                Files.setPosixFilePermissions(
                    temporary,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
