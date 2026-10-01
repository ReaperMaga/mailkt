package dev.reapermaga.mailkt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureTest {
    private val root = File("src/main/kotlin/dev/reapermaga/mailkt")
    private val forbidden = Regex("""^\s*import\s+(jakarta\.|javax\.mail|org\.eclipse\.angus|com\.sun\.mail)""")

    private fun sources(vararg dirs: String): List<File> =
        dirs.flatMap { d -> File(root, d).walkTopDown().filter { it.extension == "kt" }.toList() }

    private fun violations(files: List<File>, rule: Regex): List<String> =
        files.flatMap { f -> f.readLines().filter { rule.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" } }

    @Test
    fun publicPackagesDoNotImportJakartaOrAngus() {
        val v = violations(sources("model", "client"), forbidden)
        assertTrue(v.isEmpty(), "Public packages must not import Jakarta/Angus: $v")
    }

    @Test
    fun internalNonAdapterPackagesDoNotImportJakartaOrAngus() {
        val v = violations(sources("internal/application", "internal/connection", "internal/transport", "internal/mime"), forbidden)
        assertTrue(v.isEmpty(), "Only internal.angus may import Jakarta/Angus: $v")
    }

    @Test
    fun applicationDoesNotImportAngusAdapter() {
        val rule = Regex("""^\s*import\s+dev\.reapermaga\.mailkt\.internal\.angus""")
        val v = violations(sources("internal/application"), rule)
        assertTrue(v.isEmpty(), "internal.application must not import internal.angus: $v")
    }

    @Test
    fun publicPackagesDoNotExposeInternals() {
        val rule = Regex("""^\s*import\s+dev\.reapermaga\.mailkt\.internal\.""")
        val v = violations(sources("model"), rule)
        assertTrue(v.isEmpty(), "model must not import internal packages: $v")
    }
}
