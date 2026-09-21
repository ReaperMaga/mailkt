package dev.reapermaga.mailkt.examples

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps documentation from drifting: every Kotlin block in README.md must be an exact copy of a
 * `// region` of a compiled example source, so each README workflow is compile-tested.
 */
class ReadmeSnippetsTest {
    private val sources = File("src/main/kotlin")
    private val readme = File("../README.md")

    private fun regions(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        sources.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            var name: String? = null
            val body = mutableListOf<String>()
            file.readLines().forEach { line ->
                val t = line.trim()
                when {
                    t.startsWith("// region ") -> { name = t.removePrefix("// region ").trim(); body.clear() }
                    t == "// endregion" && name != null -> { result[name!!] = dedent(body); name = null }
                    name != null -> body += line
                }
            }
        }
        return result
    }

    private fun dedent(lines: List<String>): String {
        val indent = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
        return lines.joinToString("\n") { it.drop(indent).trimEnd() }.trim()
    }

    private fun readmeBlocks(): List<String> =
        Regex("```kotlin\\R(.*?)\\R```", RegexOption.DOT_MATCHES_ALL).findAll(readme.readText().replace("\r\n", "\n"))
            .map { it.groupValues[1].lines().joinToString("\n") { l -> l.trimEnd() }.trim() }.toList()

    @Test
    fun everyReadmeKotlinBlockIsACompiledExampleRegion() {
        val known = regions().values.toSet()
        val blocks = readmeBlocks().filterNot { it.startsWith("repositories {") || it.startsWith("interface ") } // Gradle setup and port signatures
        assertTrue(blocks.isNotEmpty(), "README must contain Kotlin examples")
        val drifted = blocks.filter { it !in known }
        assertTrue(drifted.isEmpty(), "README blocks without a matching example region:\n" + drifted.joinToString("\n---\n"))
    }

    @Test
    fun everyRegionIsDocumented() {
        val text = readme.readText().replace("\r\n", "\n")
        val missing = regions().filterValues { it !in text }.keys
        assertTrue(missing.isEmpty(), "Examples missing from the README: $missing")
    }
}
