package tribixbite.cleverkeys

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins [ContractionInjectionPolicy] — which PAIRED contraction variants the tap path may inject
 * alongside a typed partial.
 *
 * Two shipped defects motivated this class, both measured on-device in
 * `docs/eval/2026-08-28-arc019-ctc-local-head2head.md` §4:
 *
 *  1. typed `id` produced `[id, idea, ideas, ideal, idiot]` — `i'd` absent entirely, because the
 *     `length >= 3` floor excluded the only two-letter I-contraction base.
 *  2. typed `ill` produced `[I'll, I'll, ill, illegal, …]` — the two English paired-contraction
 *     sources overlap and the variant list carried `i'll` twice.
 *
 * The last test is the one that matters most: it re-derives the whole shipped English paired map
 * exactly as `ContractionManager` builds it and proves the relaxation changes the injected set for
 * EXACTLY ONE base. Without it, "allow two-letter bases" reads as unbounded.
 */
class ContractionInjectionPolicyTest {

    private companion object {
        const val DICT_DIR = "src/main/assets/dictionaries"
    }

    // ---------------------------------------------------------------------------------------
    // isFirstPersonContraction
    // ---------------------------------------------------------------------------------------

    @Test
    fun firstPersonFamilyIsRecognised() {
        for (v in listOf("i'd", "i'll", "i've", "i'm")) {
            assertTrue("'$v' is a first-person contraction", ContractionInjectionPolicy.isFirstPersonContraction(v))
        }
    }

    @Test
    fun firstPersonExcludesThePossessiveOfTheLetterI() {
        // `is` pairs to `i's` (plural/possessive of the letter I). Injecting it when the user
        // types the word "is" would be gibberish at the top of the bar.
        assertFalse(ContractionInjectionPolicy.isFirstPersonContraction("i's"))
    }

    @Test
    fun firstPersonExcludesOtherPronounFamilies() {
        for (v in listOf("it's", "it'd", "it'll", "isn't", "he's", "we'll", "t's", "a's")) {
            assertFalse("'$v' must not read as first-person", ContractionInjectionPolicy.isFirstPersonContraction(v))
        }
    }

    @Test
    fun firstPersonIsCaseInsensitive() {
        assertTrue(ContractionInjectionPolicy.isFirstPersonContraction("I'd"))
    }

    // ---------------------------------------------------------------------------------------
    // injectableVariants — the length policy
    // ---------------------------------------------------------------------------------------

    @Test
    fun nullOrEmptyVariantsYieldNothing() {
        assertEquals(emptyList<String>(), ContractionInjectionPolicy.injectableVariants("its", null))
        assertEquals(emptyList<String>(), ContractionInjectionPolicy.injectableVariants("its", emptyList()))
    }

    @Test
    fun threeCharBaseInjectsEveryVariantInOrder() {
        assertEquals(
            listOf("it's"),
            ContractionInjectionPolicy.injectableVariants("its", listOf("it's"))
        )
        assertEquals(
            listOf("we'll"),
            ContractionInjectionPolicy.injectableVariants("well", listOf("we'll"))
        )
    }

    /** UT-7's real fix: `id` is PAIRED, two letters, and must now inject `i'd`. */
    @Test
    fun twoCharBaseInjectsTheFirstPersonContraction() {
        assertEquals(
            listOf("i'd"),
            ContractionInjectionPolicy.injectableVariants("id", listOf("i'd"))
        )
    }

    @Test
    fun twoCharBaseStillBlocksPossessivesAndOtherPronouns() {
        // `as` -> `a's` (letter possessive), `it` -> pronoun family, `we`/`he` likewise, and
        // `is` -> both `i's` (letter possessive) and `isn't` (not first-person).
        assertEquals(emptyList<String>(), ContractionInjectionPolicy.injectableVariants("as", listOf("a's")))
        assertEquals(emptyList<String>(), ContractionInjectionPolicy.injectableVariants("cd", listOf("cd's")))
        assertEquals(
            emptyList<String>(),
            ContractionInjectionPolicy.injectableVariants("it", listOf("it'll", "it's", "it'd"))
        )
        assertEquals(
            emptyList<String>(),
            ContractionInjectionPolicy.injectableVariants("we", listOf("we'd", "we'll", "we're", "we've"))
        )
        assertEquals(
            emptyList<String>(),
            ContractionInjectionPolicy.injectableVariants("is", listOf("i's", "isn't"))
        )
    }

    @Test
    fun singleCharBaseInjectsNothingEvenForTheFirstPersonFamily() {
        // `i` pairs to the whole family; at one character the literal "I" must own the bar.
        assertEquals(
            emptyList<String>(),
            ContractionInjectionPolicy.injectableVariants("i", listOf("i's", "i'd", "i'll", "i've", "i'm"))
        )
        assertEquals(emptyList<String>(), ContractionInjectionPolicy.injectableVariants("t", listOf("t's")))
    }

    @Test
    fun caseOfThePartialDoesNotChangeTheDecision() {
        assertEquals(listOf("i'd"), ContractionInjectionPolicy.injectableVariants("Id", listOf("i'd")))
        assertEquals(listOf("it's"), ContractionInjectionPolicy.injectableVariants("ITS", listOf("it's")))
    }

    // ---------------------------------------------------------------------------------------
    // injectableVariants — dedup (the doubled I'll)
    // ---------------------------------------------------------------------------------------

    @Test
    fun duplicateVariantsCollapseKeepingFirstOccurrence() {
        assertEquals(
            listOf("i'll"),
            ContractionInjectionPolicy.injectableVariants("ill", listOf("i'll", "i'll"))
        )
        assertEquals(
            listOf("i'll"),
            ContractionInjectionPolicy.injectableVariants("ill", listOf("i'll", "I'LL"))
        )
        assertEquals(
            listOf("it's", "it'd"),
            ContractionInjectionPolicy.injectableVariants("it_", listOf("it's", "it'd", "it's"))
        )
    }

    // ---------------------------------------------------------------------------------------
    // Blast radius over the SHIPPED English data
    // ---------------------------------------------------------------------------------------

    /**
     * Re-derives the English paired map the way `ContractionManager.loadEnglishBase` does
     * (binary-derived pairs, then the true pairing structure merged earlier-wins) and asserts
     * that relaxing the floor to two characters changes the injected set for exactly ONE base.
     *
     * The true structure is read here from `contraction_pairings.json`; the app reads it from
     * the binary store since 2026-11-12. `ContractionBinaryPairingsTest` proves the two are
     * the same data, base for base and variant for variant, so this map is the app's map.
     *
     * If a future data change adds another two-letter first-person base this test fails loudly
     * and the new entry has to be justified — which is the point.
     */
    @Test
    fun exactlyOneShippedBaseChangesUnderTheRelaxedFloor() {
        val paired = shippedEnglishPairedMap()
        assertTrue("paired map must be populated", paired.size > 2000)

        val changed = paired.entries.mapNotNull { (base, variants) ->
            val old = if (base.length >= 3) variants else emptyList()
            val new = ContractionInjectionPolicy.injectableVariants(base, variants)
            if (old != new) base to (old to new) else null
        }.toMap()

        assertEquals(
            "relaxing the floor must change exactly one shipped base; got ${changed.keys.sorted()}",
            setOf("id"),
            changed.keys
        )
        assertEquals(listOf("i'd"), changed.getValue("id").second)
    }

    /**
     * The loader-side dedup ([ContractionManager.loadPairedContractions]) is what removes the
     * doubled `I'll` at its source. Pinned on the data: the two English sources DO overlap, so a
     * non-deduping merge is provably wrong — and after the merge no base may hold a repeat.
     */
    @Test
    fun theTwoEnglishPairedSourcesOverlapAndTheMergeMustNotRepeat() {
        val derived = binaryDerivedPairedMap()
        val pairings = pairingsFilePairedMap()

        val overlap = derived.keys.filter { base ->
            pairings[base]?.any { it in derived.getValue(base) } == true
        }
        assertTrue(
            "the binary-derived pairs and contraction_pairings.json must overlap — this overlap " +
                "IS the doubled-suggestion bug's source; got ${overlap.size} bases",
            overlap.size > 100
        )
        assertTrue("`ill` must be one of the overlapping bases", "ill" in overlap)
        assertTrue("`id` must be one of the overlapping bases", "id" in overlap)

        for ((base, variants) in shippedEnglishPairedMap()) {
            assertEquals(
                "base '$base' must hold no repeated variant after the merge: $variants",
                variants.size,
                variants.map { it.lowercase() }.toSet().size
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shipped-data readers — mirror ContractionManager's load order exactly
    // ---------------------------------------------------------------------------------------

    /**
     * `contractions.bin`, read by the PRODUCTION parser ([ContractionBinaryReader]).
     *
     * There is one parser, and this test uses it: a second hand-rolled reader here is a
     * second thing to keep in step with the format, and this one already had to change when
     * the store went from V1 (a flat set of paired forms) to V2 (the pairing structure).
     *
     * Returns the non-paired map and the paired FORMS the store carries. V2 keeps the
     * forms as the union of the variants of every base; V1 keeps them as its own flat
     * list. Either way the caller puts them into a set and drops the non-paired values,
     * which is what `ContractionManager.loadBinaryContractions` does with them.
     */
    private fun readBinary(): Pair<Map<String, String>, List<String>> {
        val bytes = File("$DICT_DIR/contractions.bin").readBytes()
        val payload = ContractionBinaryReader.parse(bytes)
        val paired = ArrayList<String>()
        if (payload.paired.isNotEmpty()) {
            payload.paired.values.forEach { entries ->
                entries.forEach { paired.add(it.contraction) }
            }
        } else {
            // V1: no structure, so the known set is all the store has beyond the
            // non-paired values. The caller filters those out again.
            paired.addAll(payload.known)
        }
        return payload.nonPaired to paired
    }

    /** `ContractionManager.loadBinaryContractions`'s derivation, verbatim. */
    private fun binaryDerivedPairedMap(): Map<String, MutableList<String>> {
        val (nonPaired, paired) = readBinary()
        val nonPairedValues = nonPaired.values.toSet()
        val known = LinkedHashSet<String>(nonPairedValues).apply { addAll(paired) }
        val out = LinkedHashMap<String, MutableList<String>>()
        for (contraction in known) {
            if (contraction !in nonPairedValues) {
                out.getOrPut(contraction.replace("'", "")) { mutableListOf() }.add(contraction)
            }
        }
        return out
    }

    /** `contraction_pairings.json`: `{base: [{contraction, frequency}]}`. */
    private fun pairingsFilePairedMap(): Map<String, List<String>> {
        val json = JsonParser.parseString(File("$DICT_DIR/contraction_pairings.json").readText())
            .asJsonObject
        return json.entrySet().associate { (base, arr) ->
            base.lowercase() to arr.asJsonArray.map {
                it.asJsonObject.get("contraction").asString.lowercase()
            }
        }
    }

    /**
     * Binary-derived pairs, then the true pairing structure merged earlier-wins (post-fix
     * loader). The structure comes from the JSON here and from the binary store in the app;
     * the two are the same data (`ContractionBinaryPairingsTest`).
     */
    private fun shippedEnglishPairedMap(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        binaryDerivedPairedMap().forEach { (k, v) -> out[k] = v.toMutableList() }
        for ((base, variants) in pairingsFilePairedMap()) {
            val existing = out.getOrPut(base) { mutableListOf() }
            for (v in variants) if (v !in existing) existing.add(v)
        }
        return out
    }
}
