package tribixbite.cleverkeys

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Pure-JVM reader for the `CTRB` binary contraction store, produced by
 * `scripts/generate_binary_contractions.py` from the two JSON files that are the
 * SOURCE OF TRUTH (`contractions_non_paired.json`, `contraction_pairings.json`).
 *
 * This is deliberately free of `android.*` imports, so the pure test classpath can
 * read the SHIPPED asset and compare it with what the JSON parser produces. That
 * comparison is the whole point: the binary replaced a 154 KB `org.json` parse on the
 * main thread at every cold start (the `contractions` step of `onCreate`, the slowest
 * set-up step in three CI runs running), so it has to be provably the same data.
 *
 * ## V2 header (16 bytes, little-endian)
 *  - magic `CTRB` (4) · version 2 (4) · non-paired count (4) · paired BASE count (4)
 *
 * ## Non-paired section
 *  - per entry: length uint16 · UTF-8 bytes (apostrophe-free form)
 *    · length uint16 · UTF-8 bytes (the contraction with the apostrophe)
 *
 * ## Paired section (V2)
 *  - per BASE, in the JSON file's order: length uint16 · UTF-8 bytes · variant count uint16
 *  - per variant, in the JSON file's order: length uint16 · UTF-8 bytes ·
 *    frequency int32 (`0xFFFFFFFF` when the JSON entry carries none)
 *
 * ## V1 (the format this file replaced)
 *  - same header, version 1, but the paired section is a FLAT SET of contraction
 *    forms: length uint16 · UTF-8 bytes each, with no base and no frequency. A V1
 *    store therefore carries no pairing STRUCTURE, and the caller falls back to
 *    parsing `contraction_pairings.json` — the behaviour before this format existed.
 *
 * ORDER IS PART OF THE DATA. The paired bases and their variants are written in the
 * JSON file's order because `ContractionManager.parsePairings` builds a LinkedHashMap
 * in that order, and the order decides which variant the suggestion bar offers first.
 */
object ContractionBinaryReader {

    /** "CTRB" little-endian. */
    const val MAGIC = 0x42525443

    /** The flat-set format: paired forms only, no bases and no frequencies. */
    const val VERSION_V1 = 1

    /** The current format: the pairing structure itself. */
    const val VERSION_V2 = 2

    const val HEADER_SIZE = 16

    /** Bounds shared with the dictionary readers: an asset is never trusted. */
    const val MAX_BYTES: Long = 16L * 1024 * 1024
    const val MAX_BASES = 100_000
    const val MAX_VARIANTS_PER_BASE = 1_000

    /** The frequency value that means "this pairing carries no frequency". */
    const val NO_FREQUENCY = -1

    /**
     * One paired variant: the contraction with its apostrophe, and its pairing
     * frequency on the 0..255 byte scale, or null when the JSON entry carried none.
     */
    data class PairedEntry(val contraction: String, val frequency: Int?)

    /**
     * The whole store.
     *
     * @param nonPaired apostrophe-free form -> contraction (REPLACE mode)
     * @param known every contraction form the store knows, paired ones included
     * @param paired base -> its variants, in file order. EMPTY for a V1 store, which
     *   carries no pairing structure — the caller then parses the JSON.
     */
    data class Payload(
        val nonPaired: Map<String, String>,
        val known: Set<String>,
        val paired: Map<String, List<PairedEntry>>
    )

    /** Read to EOF with bounded allocation; never trust available() or a size hint. */
    fun readBoundedBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            checkInterrupted()
            val count = input.read(chunk)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_BYTES) throw IOException("Contraction store exceeds 16 MiB limit")
            output.write(chunk, 0, count)
        }
        return output.toByteArray()
    }

    /**
     * Parse a `CTRB` store.
     *
     * @throws IOException if the bytes are not a store this reader understands —
     *   a wrong magic, a version it does not know, or a truncated body. The caller
     *   treats that as "no binary" and falls back to the JSON files.
     */
    fun parse(bytes: ByteArray): Payload {
        if (bytes.size < HEADER_SIZE) {
            throw IOException("Contraction store is too small to hold a header (${bytes.size} bytes)")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        if (magic != MAGIC) {
            throw IOException(
                String.format("Invalid contraction store magic 0x%08X (expected 0x%08X)", magic, MAGIC)
            )
        }
        val version = buffer.int
        if (version != VERSION_V1 && version != VERSION_V2) {
            throw IOException("Unsupported contraction store version $version")
        }
        val nonPairedCount = buffer.int
        val pairedCount = buffer.int
        if (nonPairedCount < 0 || nonPairedCount > MAX_BASES) {
            throw IOException("Implausible non-paired count $nonPairedCount")
        }
        if (pairedCount < 0 || pairedCount > MAX_BASES) {
            throw IOException("Implausible paired count $pairedCount")
        }

        val nonPaired = LinkedHashMap<String, String>(nonPairedCount * 2)
        val known = LinkedHashSet<String>(nonPairedCount * 2)
        repeat(nonPairedCount) {
            val key = readString(buffer)
            val value = readString(buffer)
            nonPaired[key] = value
            known.add(value)
        }

        val paired: Map<String, List<PairedEntry>> = if (version == VERSION_V2) {
            val map = LinkedHashMap<String, List<PairedEntry>>(pairedCount * 2)
            repeat(pairedCount) {
                val base = readString(buffer)
                buffer.requireRemaining(2, "a variant count")
                val variantCount = buffer.short.toInt() and 0xFFFF
                if (variantCount > MAX_VARIANTS_PER_BASE) {
                    throw IOException("Implausible variant count $variantCount for base '$base'")
                }
                val entries = ArrayList<PairedEntry>(variantCount)
                repeat(variantCount) {
                    val contraction = readString(buffer)
                    buffer.requireRemaining(4, "a pairing frequency")
                    val raw = buffer.int
                    val frequency = if (raw == NO_FREQUENCY) null else raw
                    entries.add(PairedEntry(contraction, frequency))
                    known.add(contraction)
                }
                map[base] = entries
            }
            map
        } else {
            // V1: a flat set of paired forms. No bases, no frequencies.
            repeat(pairedCount) { known.add(readString(buffer)) }
            emptyMap()
        }

        return Payload(nonPaired, known, paired)
    }

    /**
     * Fail with an [IOException] rather than letting the buffer underflow.
     *
     * A truncated asset must read as "no binary store, fall back to the JSON", not as
     * a `BufferUnderflowException` escaping the caller's `catch (IOException)` and
     * taking the keyboard down with it.
     */
    private fun ByteBuffer.requireRemaining(count: Int, what: String) {
        if (remaining() < count) {
            throw IOException(
                "Contraction store is truncated: $what needs $count bytes, ${remaining()} left"
            )
        }
    }

    /**
     * Read a length-prefixed UTF-8 string from the buffer.
     *
     * @param buffer ByteBuffer to read from
     * @return Decoded string
     */
    private fun readString(buffer: ByteBuffer): String {
        buffer.requireRemaining(2, "a string length")
        val length = buffer.short.toInt() and 0xFFFF // Unsigned short
        if (length > buffer.remaining()) {
            throw IOException("Contraction store is truncated: a $length-byte string does not fit")
        }
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw IOException("Interrupted while reading the contraction store")
    }
}
