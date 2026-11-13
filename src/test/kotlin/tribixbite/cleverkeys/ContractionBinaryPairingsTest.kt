package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import org.json.JSONObject
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
 * store and the shipped JSON and requires them to agree on:
 *
 *  - the same BASES (the same set of keys);
 *  - the same VARIANTS under each base, in the same order — this is the order that is
 *    observed: `SuggestionHandler` injects `getPairedContractions(partial)` into the
 *    suggestion bar in list order, so a reordering would change what the bar offers
 *    first;
 *  - the same pairing frequency for each variant, including "no frequency" (the JSON
 *    omits the field; the store stores a sentinel for it);
 *  - the same set of known contraction forms, which is what the loader's DERIVATION
 *    step (a contraction without an apostrophe implies its base) runs on — a different
 *    set here would silently change which bases the keyboard offers a variant for.
 *
 * A stale binary (the JSON regenerated, the binary not) fails here, which is the point:
 * the binary is a build product of the JSON and must never disagree with it.
 *
 * ## Two comparisons, two orders
 *
 * The store is compared against the JSON TWICE, because the JSON has two orders and only
 * one of them is the file's:
 *
 *  1. Against the PRODUCTION parser, `ContractionManager.parsePairings` (org.json): the
 *     same bases, the same variants per base in the same order, the same frequencies.
 *     This is the contract that matters at runtime — the store stands in for that parser.
 *  2. Against the FILE's own order (read with Gson, which keeps insertion order): the
 *     bases in the order the JSON file lists them, and each base's variants in the order
 *     the file lists them. This is what "order is part of the data" means, and it is the
 *     order the store has to carry.
 *
 * The base order differs between the two comparisons and that is NOT a defect: org.json
 * keeps its keys in a `HashMap` on the JVM and in a `LinkedHashMap` on Android, so the
 * production parser yields the bases in a different order on the two platforms already —
 * before this change, on the same data. (Reproduced: the JVM's `HashMap` iteration order
 * over these 1,748 keys begins `hall, clinton, owners, yous, assassin, childs`, while the
 * file begins `a, aaron, abbott, abc, academy, act`.) Nothing observes the base order:
 * `getPairedContractions(base)`, `getPairedVariantFrequency(base, variant)`,
 * `getPairedFrequencyBases()` (a `Set`, and `PairingBaseFrequencies.select` writes it
 * into a `HashMap`) and `getAliasKeys()` (a `HashSet`) all look a base up BY NAME. The
 * only order the app sees is the order of the VARIANTS under a base, and that is compared
 * exactly, against both readings of the JSON.
 *
 * The store's base order is the FILE's base order, and on Android that is also what the
 * org.json parser used to yield — so the store preserves the shipping platform's order
 * exactly, and the difference above is a property of the JVM test classpath alone.
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

    /** The JSON as the PRODUCTION parser sees it (org.json). */
    private fun jsonPairings(): Map<String, List<ContractionManager.PairedVariant>> =
        ContractionManager.parsePairings(File(DICT_DIR, PAIRINGS_JSON).readText())

    /**
     * The JSON in the FILE's order, lowercased — the reading
     * `ContractionInjectionPolicyTest` makes of the same file.
     *
     * Gson keeps an object's keys in INSERTION order, so this is the order the file
     * lists its bases in. (org.json does not: it keeps them in a `HashMap` on the JVM
     * and in a `LinkedHashMap` on Android, which is why the production parser and the
     * store disagree about the base order under the JVM — see the class doc.)
     */
    private fun fileOrderPairings(): Map<String, List<String>> {
        val json = JsonParser.parseString(File(DICT_DIR, PAIRINGS_JSON).readText())
            .asJsonObject
        return json.entrySet().associate { (base, arr) ->
            base.lowercase() to arr.asJsonArray.map {
                it.asJsonObject.get("contraction").asString.lowercase()
            }
        }
    }

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
    fun `the store carries every paired base the JSON does`() {
        assertWithMessage("the store must carry the pairing structure at all")
            .that(payload().paired.keys).isEqualTo(jsonPairings().keys)
    }

    @Test
    fun `every base carries the same variants, in the same order, with the same frequencies`() {
        val fromBinary = payload().paired
        val fromJson = jsonPairings()

        for ((base, jsonVariants) in fromJson) {
            val binaryVariants = fromBinary[base]
            assertWithMessage("base '$base' must be in the store")
                .that(binaryVariants).isNotNull()
            assertWithMessage(
                "base '$base' must carry the same variants in the same order — the bar " +
                    "offers them in list order"
            ).that(binaryVariants!!.map { it.contraction })
                .isEqualTo(jsonVariants.map { it.contraction })
            assertWithMessage("base '$base' must carry the same frequencies")
                .that(binaryVariants.map { it.frequency })
                .isEqualTo(jsonVariants.map { it.frequency })
        }
    }

    @Test
    fun `the store keeps the bases in the JSON file's order`() {
        val fromBinary = payload().paired.keys.toList()
        val fromFile = fileOrderPairings().keys.toList()
        assertWithMessage(
            "the store must carry the bases in the order the JSON file lists them — the " +
                "store is the file's projection, and on Android that order is what the " +
                "org.json parser used to yield"
        ).that(fromBinary).isEqualTo(fromFile)
    }

    @Test
    fun `the store keeps each base's variants in the JSON file's order`() {
        val fromBinary = payload().paired
        for ((base, variants) in fileOrderPairings()) {
            val binaryVariants = fromBinary[base]
            assertWithMessage("base '$base' must be in the store").that(binaryVariants).isNotNull()
            assertWithMessage("base '$base' must carry its variants in the file's order")
                .that(binaryVariants!!.map { it.contraction })
                .isEqualTo(variants)
        }
    }

    @Test
    fun `the known forms are the non-paired values plus every paired variant`() {
        val store = payload()
        val nonPaired = JSONObject(File(DICT_DIR, NON_PAIRED_JSON).readText())
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
        val json = JSONObject(File(DICT_DIR, NON_PAIRED_JSON).readText())
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

    /**
     * A V1 store is still READABLE, and it still carries no pairing STRUCTURE — which is
     * what makes the loader fall back to `contraction_pairings.json`, exactly as it did
     * before this format existed. That fallback is the safety net for a store that is
     * missing, corrupt or old, so the property that makes it work is pinned here: a V1
     * store parses, and its pairing structure is EMPTY.
     *
     * (`ContractionManager.pairedVariantsFromBinary` returns null for an empty structure,
     * and `loadPairedContractions` then reads the JSON. That is the fallback; this test
     * pins the half of it that is pure — the reader — because the other half needs a
     * Context.)
     */
    @Test
    fun `a V1 store is readable and carries no pairing structure, so the loader falls back to the JSON`() {
        val v1 = v1Store(
            nonPaired = listOf("dont" to "don't"),
            pairedForms = listOf("it's", "we'll"),
        )
        val payload = ContractionBinaryReader.parse(v1)
        assertWithMessage("a V1 store must still be readable")
            .that(payload.nonPaired["dont"]).isEqualTo("don't")
        assertWithMessage(
            "a V1 store carries no pairing structure — that empty map is what tells the " +
                "loader to read contraction_pairings.json instead"
        ).that(payload.paired).isEmpty()
        assertWithMessage("a V1 store still knows its forms")
            .that(payload.known).containsExactly("don't", "it's", "we'll")
    }

    /** A V1 store: the same header, version 1, and the paired section as a FLAT set. */
    private fun v1Store(
        nonPaired: List<Pair<String, String>>,
        pairedForms: List<String>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun writeInt(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
            )
        )
        fun writeShort(value: Int) = out.write(
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        )
        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeShort(bytes.size)
            out.write(bytes)
        }

        writeInt(ContractionBinaryReader.MAGIC)
        writeInt(ContractionBinaryReader.VERSION_V1)
        writeInt(nonPaired.size)
        writeInt(pairedForms.size)
        for ((key, value) in nonPaired) {
            writeString(key)
            writeString(value)
        }
        for (form in pairedForms) writeString(form)
        return out.toByteArray()
    }
}
