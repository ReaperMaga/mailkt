package dev.reapermaga.mailkt.auth

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileTokenPersistenceStorageTest {
    @Test
    fun `stores multiple users without overwriting existing tokens`() {
        val path = Files.createTempFile("mailkt-tokens", ".json")
        path.deleteIfExists()
        try {
            val alice = FileTokenPersistenceStorage("alice", path.toString())
            val bob = FileTokenPersistenceStorage("bob", path.toString())

            assertNull(alice.load())
            alice.store("alice-token")
            bob.store("bob-token")

            assertEquals("alice-token", alice.load())
            assertEquals("bob-token", bob.load())
        } finally {
            path.deleteIfExists()
        }
    }
}
