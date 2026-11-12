package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.IOException
import org.junit.Test

/**
 * The shipped contraction store must say exactly what the shipped JSON says (2026-11-12).
 *
 * `contractions_non_paired.json` + `contraction_pairings.json` are the SOURCE OF TRUTH;
 * `scripts/generate_binary_contractions.py` projects them into `contractions.bin`, and
 * the app reads the binary instead of parsing 154 KB of JSON with org.json on the main
 * thread at every cold start. That parse was the slowest step of `onCreate` in three CI
 * runs running (79, 85 and 40 ms of a 191-224 ms set-up) and it sat on the critical
 * path to the first picture.
 *
 * So the substitution has to be PROVEN, not asserted. This class reads the shipped
 * binary and the shipped JSON and requires them to agree on:
 *
 *  - the same bases, in the same order (the order decides which variant the suggestion
 *    bar offers first — `ContractionManager.parsePairings` builds a LinkedHashMap in
 *    the file's order, and the binary was written in that order for exactly this
 *    reason);
 *  - the same variants per base, in the same order;
 *  - the same pairing frequency for each variant, including "no frequency" (the JSON
 *    omits the field; the binary stores a sentinel for it);
 *  - the same set of known contraction forms, which is what the loader's DERIVATION
 *    step (a contraction without an apostrophe implies its base) runs on — a different
 *    set here would silently change which bases the keyboard offers a variant for.
 *
 * A stale binary (the JSON regenerated, the binary not) fails here, which is the point:
 * the binary is a build product of the JSON and must never disagree with it.
 */
class ContractionBinaryPairingsTest {

    companion object {
        private const val DICT_DIR = "src/main/assets/dictionaries"
        private const val BINARY = "contractions.bin"
        private const val PAIRINGS_JSON = "contraction_pairings.json"
        private const val NON_PAIRED_JSON = "contractions_non_paired.json"
    }

    private fun binaryFile(): File {
        val file = File(DICT_DIR, BINARY)
        check(file.isFile) {
            "expected the shipped contraction store at ${file.path} (run from the project root)"
        }
        return file
    }

    private fun payload(): ContractionBinaryReader.Payload =
        ContractionBinaryReader.parse(binaryFile().readBytes())

    private fun jsonPairings(): Map<String, List<ContractionManager.PairedVariant>> =
        ContractionManager.parsePairings(File(DICT_DIR, PAIRINGS_JSON).readText())

    @Test
    fun `the shipped store is V2`() {
        val bytes = binaryFile().readBytes()
        val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val magic = header.int
        val version = header.int
        assertWithMessage("contractions.bin magic must be 'CTRB'")
            .that(magic).isEqualTo(ContractionBinaryReader.MAGIC)
        assertWithMessage(
            "contractions.bin must be V2 — V1 carries no pairing structure, so the app " +
                "would be back to parsing contraction_pairings.json on the main thread. " +
                "Regenerate with scripts/generate_binary_contractions.py."
        ).that(version).isEqualTo(ContractionBinaryReader.VERSION_V2)
    }

    @Test
    fun `the store carries every paired base the JSON does, in the same order`() {
        val fromBinary = payload().paired
        val fromJson = jsonPairings()

        assertWithMessage("the store must carry the pairing structure at all")
            .that(fromBinary.keys.toList()).isEqualTo(fromJson.keys.toList())
    }

    @Test
    fun `every base carries the same variants, in the same order, with the same frequencies`() {
        val fromBinary = payload().paired
        val fromJson = jsonPairings()

        for ((base, jsonVariants) in fromJson) {
            val binaryVariants = fromBinary[base]
            assertWithMessage("base '$base' must be in the store")
                .that(binaryVariants).isNotNull()
            assertWithMessage("base '$base' must carry the same variants in the same order")
                .that(binaryVariants!!.map { it.contraction })
                .isEqualTo(jsonVariants.map { it.contraction })
            assertWithMessage("base '$base' must carry the same frequencies")
                .that(binaryVariants.map { it.frequency })
                .isEqualTo(jsonVariants.map { it.frequency })
        }
    }

    @Test
    fun `the known forms are the non-paired values plus every paired variant`() {
        val store = payload()
        val nonPaired = File(DICT_DIR, NON_PAIRED_JSON).readText()
            .let { org.json.JSONObject(it) }
        val expected = HashSet<String>()
        val keys = nonPaired.keys()
        while (keys.hasNext()) {
            expected.add(nonPaired.getString(keys.next()).lowercase())
        }
        store.paired.values.forEach { entries ->
            entries.forEach { expected.add(it.contraction) }
        }

        assertWithMessage(
            "the known set is what the loader's derivation step runs on: every known " +
                "contraction that is not a non-paired value implies a base"
        ).that(store.known).isEqualTo(expected)
    }

    @Test
    fun `the non-paired mappings are the ones the JSON file holds`() {
        val store = payload()
        val json = org.json.JSONObject(File(DICT_DIR, NON_PAIRED_JSON).readText())
        val expected = HashMap<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            expected[key.lowercase()] = json.getString(key).lowercase()
        }
        assertThat(store.nonPaired).isEqualTo(expected)
    }

    @Test
    fun `a truncated store is refused with an IOException, not a crash`() {
        val bytes = binaryFile().readBytes()
        for (cut in listOf(0, 4, 15, 16, 64, bytes.size / 2)) {
            val truncated = bytes.copyOf(cut)
            try {
                ContractionBinaryReader.parse(truncated)
                throw AssertionError("Accepted a store truncated to $cut bytes")
            } catch (expected: IOException) {
                // the caller's catch (IOException) is what turns this into "no binary"
            }
        }
    }

    @Test
    fun `a store with the wrong magic is refused with an IOException`() {
        val bytes = binaryFile().readBytes().copyOf()
        bytes[0] = 'X'.code.toByte()
        try {
            ContractionBinaryReader.parse(bytes)
            throw AssertionError("Accepted a store with a foreign magic")
        } catch (expected: IOException) {
            assertThat(expected.message.orEmpty()).contains("magic")
        }
    }
}
