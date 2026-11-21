package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Malformed-input behaviour of [CkdtDictionaryReader].
 *
 * The reader is the entry point for two things a user can install: a language pack (a ZIP the
 * user picked, carrying `dictionary.bin`) and, through
 * `ContractionCollisionScanner`/`CtcInstalledPacks`, the same bytes reached from pack metadata.
 * Every one of its production callers treats a failure as "this language has no dictionary" —
 * `CtcEngineAdapter.lexiconFor` and `GeometricEngineAdapter` both `catch (Exception)` and return
 * null — so the *reason* a bad file fails matters less than failing at all, in a way that is
 * reported as a malformed file rather than as an unknown internal error.
 *
 * The reader validated its header thoroughly and its record section only statistically: it
 * checked `wordCount * 3 <= sectionLength` once, then read a `uint16` length per record and
 * `get()` that many bytes with no per-record check. A corrupt length near the end of the section
 * reached `ByteBuffer.get` and threw `BufferUnderflowException` — an `IndexOutOfBoundsException`,
 * not an `IOException`. These cases pin the trap.
 */
class CkdtDictionaryReaderTest {

    // --------------------------------------------------------------------- CKDT v2 builder

    /**
     * Build a CKDT v2 file. `records` are `(word, rank)` in file order.
     *
     * Layout mirrors the reader's KDoc: a 48-byte little-endian header, then the canonical
     * section of `uint16 length | UTF-8 bytes | uint8 rank`.
     */
    private fun buildCkdt(records: List<Pair<String, Int>>): ByteArray {
        val canonical = ByteArrayOutputStream()
        for ((word, rank) in records) {
            val wb = word.toByteArray(Charsets.UTF_8)
            canonical.write(wb.size and 0xFF)
            canonical.write((wb.size ushr 8) and 0xFF) // uint16 little-endian
            canonical.write(wb)
            canonical.write(rank and 0xFF)
        }
        val section = canonical.toByteArray()
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("CKDT".toByteArray(Charsets.US_ASCII))
        header.putInt(2)                 // version
        header.put(byteArrayOf(0, 0, 0, 0)) // language, unused
        header.putInt(records.size)      // wordCount
        header.putInt(HEADER_SIZE)       // canonicalOffset
        header.put(ByteArray(HEADER_SIZE - header.position()))
        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(section)
        return out.toByteArray()
    }

    /**
     * A file whose header claims [declaredCount] records but whose canonical section holds
     * [section] verbatim — the shape a truncated or hand-edited dictionary has.
     */
    private fun buildCkdtWithSection(declaredCount: Int, section: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("CKDT".toByteArray(Charsets.US_ASCII))
        header.putInt(2)
        header.put(byteArrayOf(0, 0, 0, 0))
        header.putInt(declaredCount)
        header.putInt(HEADER_SIZE)
        header.put(ByteArray(HEADER_SIZE - header.position()))
        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(section)
        return out.toByteArray()
    }

    private companion object {
        const val HEADER_SIZE = 48
    }

    // ------------------------------------------------------------------------- happy path

    @Test
    fun `reads records and orders them by rank ascending`() {
        val bytes = buildCkdt(
            listOf(
                "common" to 0,
                "rare" to 200,
                "middling" to 50,
                // Same rank as "common" — a stable sort must keep FILE order (NFR-4).
                "alsocommon" to 0,
            )
        )

        val entries = CkdtDictionaryReader.readEntries(bytes)

        assertThat(entries.map { it.word })
            .containsExactly("common", "alsocommon", "middling", "rare").inOrder()
        assertThat(entries.map { it.rank }).containsExactly(0, 0, 50, 200).inOrder()
    }

    @Test
    fun `an empty dictionary is valid`() {
        val entries = CkdtDictionaryReader.readEntries(buildCkdt(emptyList()))
        assertThat(entries).isEmpty()
    }

    // -------------------------------------------------------------------- malformed input

    /**
     * The regression this class exists for. One record declares a 20-byte word and the section
     * carries 3 bytes for it, so `buffer.get` runs off the end.
     */
    @Test
    fun `a record length that runs past the section is an IOException, not a buffer underflow`() {
        val section = ByteArrayOutputStream().apply {
            write(20); write(0)       // length 20, little-endian
            write("ab".toByteArray()) // ...but only 2 bytes follow
            write(7)                  // rank
        }.toByteArray()
        val bytes = buildCkdtWithSection(declaredCount = 1, section = section)

        val failure = runCatching { CkdtDictionaryReader.readEntries(bytes) }.exceptionOrNull()

        assertWithMessage(
            "A corrupt length must surface as IOException (\"unreadable dictionary\"), which is " +
                "what every caller's catch block reports; got $failure"
        ).that(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("Truncated CKDT record")
    }

    /** A record that ends after its word but before its rank byte. */
    @Test
    fun `a record missing its rank byte is an IOException`() {
        val section = ByteArrayOutputStream().apply {
            write(2); write(0)
            write("ab".toByteArray())
            // no rank byte
        }.toByteArray()
        val bytes = buildCkdtWithSection(declaredCount = 1, section = section)

        val failure = runCatching { CkdtDictionaryReader.readEntries(bytes) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("missing rank byte")
    }

    /**
     * The largest length a `uint16` can express, in a section that cannot possibly hold it. This
     * is the case that would have allocated 64 KiB and then run off the end on an empty tail.
     */
    @Test
    fun `a maximum uint16 length in a tiny section is rejected`() {
        val section = ByteArrayOutputStream().apply {
            write(0xFF); write(0xFF) // 65535
            write(0)                 // one stray byte
        }.toByteArray()
        val bytes = buildCkdtWithSection(declaredCount = 1, section = section)

        val failure = runCatching { CkdtDictionaryReader.readEntries(bytes) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a word count that cannot fit in the section is rejected up front`() {
        val headerOnly = buildCkdtWithSection(declaredCount = 1_000, section = ByteArray(3))

        val failure = runCatching { CkdtDictionaryReader.readEntries(headerOnly) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure!!.message).contains("Truncated canonical records")
    }

    // Bad magic and a header-short file are already pinned by [CkdtReaderTest]
    // (`read_rejectsBadMagic`, and the same reader's length guard) — not repeated here.

    /** A canonical offset pointing into the header (or before it) is rejected. */
    @Test
    fun `a canonical offset inside the header is rejected`() {
        val bytes = buildCkdt(listOf("word" to 0))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 4)

        val failure = runCatching { CkdtDictionaryReader.readEntries(bytes) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure!!.message).contains("Invalid canonical offset")
    }
}
