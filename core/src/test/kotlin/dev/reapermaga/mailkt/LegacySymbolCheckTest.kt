package dev.reapermaga.mailkt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Repository-level guard: Kotlin and Markdown sources must not mention the removed session, device-code
 * and helper APIs, and only the Angus adapter may import Jakarta/Angus. The redesign plan under
 * `docs/` is intentionally excluded because it describes what was removed.
 */
class LegacySymbolCheckTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val removed = Regex(
        "\\b(" + listOf(
            "MailSession", "ManagedMailSession", "MailSessionManager", "ManagedMailSessionState", "MailConnection",
            "ImapMailSession", "GmailMailSession", "OutlookMailSession", "OutlookOAuth2Verification",
            "OutlookOAuth2MailAuth", "GmailOAuth2MailAuth", "OutlookOAuth2Config", "GmailOAuth2Config", "OAuth2MailAuth",
            "FileTokenPersistenceStorage", "AESEncryptedTokenPersistenceStorage", "TokenPersistenceStorage",
            "JakartaPropertiesFactory", "LocalServerReceiver", "deviceLogin", "acquireTokenByDeviceCode",
            "readMessagesFlow", "readMessagePage", "readMessages", "readConversations", "synchronizeConversations",
            "assembleConversations", "watchFolder", "composeMessage", "composeReply", "appendSentMessage",
            "listMailFolders", "HistoricalReader", "HistoricalMessage", "ReadMessagesResult", "MessagePageCursor",
        ).joinToString("|") + ")\\b",
    )
    private val jakartaImport = Regex("""^\s*import\s+(jakarta\.|javax\.mail|org\.eclipse\.angus|com\.sun\.mail)""")

    private fun files(): List<File> = root.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".git", ".idea", "docs", "node_modules") }
        .filter { it.isFile && it.extension in setOf("kt", "kts", "md") }
        .filter { it.name != "LegacySymbolCheckTest.kt" }
        .toList()

    @Test
    fun noRemovedSymbolsInSourcesTestsExamplesOrMarkdown() {
        val hits = files().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                removed.find(line)?.let { "${f.relativeTo(root)}:${i + 1}: ${it.value}" }
            }
        }
        assertTrue(hits.isEmpty(), "Removed API referenced:\n" + hits.joinToString("\n"))
    }

    @Test
    fun onlyTheAngusAdapterImportsJakartaOrAngus() {
        val adapter = File(root, "core/src/main/kotlin/dev/reapermaga/mailkt/internal/angus").canonicalPath
        val adapterTests = File(root, "core/src/test/kotlin/dev/reapermaga/mailkt/internal/angus").canonicalPath
        val hits = files().filter { it.extension == "kt" || it.extension == "md" }
            .filterNot { it.canonicalPath.startsWith(adapter) || it.canonicalPath.startsWith(adapterTests) }
            .flatMap { f -> f.readLines().filter { jakartaImport.containsMatchIn(it) }.map { "${f.relativeTo(root)}: ${it.trim()}" } }
        assertTrue(hits.isEmpty(), "Jakarta/Angus imports outside the adapter:\n" + hits.joinToString("\n"))
    }
}
