package dev.reapermaga.mailkt.internal.mime

import dev.reapermaga.mailkt.model.MailboxId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest

/** Dedicated logger categories. */
internal enum class LogCategory(val loggerName: String) {
    LIFECYCLE("dev.reapermaga.mailkt.lifecycle"),
    AUTH("dev.reapermaga.mailkt.auth"),
    IMAP("dev.reapermaga.mailkt.imap"),
    WATCH("dev.reapermaga.mailkt.watch"),
    SYNC("dev.reapermaga.mailkt.sync"),
    MIME("dev.reapermaga.mailkt.mime"),
    SMTP("dev.reapermaga.mailkt.smtp"),
}

/**
 * Structured key/value logging on SLF4J API only. Only whitelisted primitive fields are emitted
 * so secrets, addresses, subjects and provider error text cannot reach a log.
 * Logging is observational and never drives behavior; failures inside logging are swallowed.
 */
internal class MailLog(private val category: LogCategory, private val correlation: String) {
    private val logger: Logger = LoggerFactory.getLogger(category.loggerName)

    fun isEnabled(level: Level): Boolean = logger.isEnabledForLevel(level)

    fun info(op: String, vararg f: Pair<String, Any?>) = log(Level.INFO, op, null, f)
    fun warn(op: String, error: Throwable? = null, vararg f: Pair<String, Any?>) = log(Level.WARN, op, error, f)
    fun debug(op: String, vararg f: Pair<String, Any?>) = log(Level.DEBUG, op, null, f)

    private fun log(level: Level, op: String, error: Throwable?, fields: Array<out Pair<String, Any?>>) {
        try {
            if (!logger.isEnabledForLevel(level)) return
            val sb = StringBuilder("op=").append(sanitize(op)).append(" mailbox=").append(correlation)
            for ((k, v) in fields) {
                if (k !in ALLOWED) continue
                sb.append(' ').append(k).append('=').append(render(v))
            }
            if (error != null) {
                // Class name only: the message may contain server responses or credentials.
                sb.append(" failure=").append(error.javaClass.simpleName)
            }
            logger.atLevel(level).log(sb.toString())
        } catch (_: Throwable) {
        }
    }

    private fun render(v: Any?): String = when (v) {
        null -> "-"
        is Number, is Boolean -> v.toString()
        is Enum<*> -> v.name
        is Throwable -> v.javaClass.simpleName
        is CharSequence -> if (SAFE.matches(v)) v.toString() else "[redacted]"
        else -> v.javaClass.simpleName
    }

    private fun sanitize(s: String) = if (SAFE.matches(s)) s else "[redacted]"

    companion object {
        /** Fields that may appear in log output. Everything else is dropped. */
        val ALLOWED = setOf(
            "generation", "attempt", "uid", "elapsedMs", "reason", "outcome", "folderHash",
            "count", "state", "failureType", "limit", "batch",
        )
        private val SAFE = Regex("[A-Za-z0-9_.:-]{1,64}")

        fun forMailbox(category: LogCategory, id: MailboxId): MailLog = MailLog(category, correlationId(id))

        /** Stable one-way opaque id; never the address. */
        fun correlationId(id: MailboxId): String = hash(id.storageKey, 6)

        fun folderHash(folder: String): String = hash(folder, 4)

        private fun hash(s: String, bytes: Int): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
