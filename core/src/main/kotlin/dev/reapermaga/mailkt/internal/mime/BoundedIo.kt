package dev.reapermaga.mailkt.internal.mime

import dev.reapermaga.mailkt.model.ByteContent
import dev.reapermaga.mailkt.model.MailException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Reads at most [maxBytes] from the stream; throws [MailException.LimitExceeded] if more remain. */
internal fun InputStream.readBounded(maxBytes: Long): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be >= 0" }
    val out = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
    val buf = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw MailException.LimitExceeded(maxBytes)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

internal fun InputStream.readBoundedContent(maxBytes: Long): ByteContent = ByteContent(readBounded(maxBytes))

/** Output stream that fails with [MailException.LimitExceeded] once more than [maxBytes] were written. */
internal class BoundedOutputStream(private val maxBytes: Long) : OutputStream() {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()

    override fun write(b: Int) {
        check(1)
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(len)
        out.write(b, off, len)
    }

    private fun check(add: Int) {
        if (out.size().toLong() + add > maxBytes) throw MailException.LimitExceeded(maxBytes)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
