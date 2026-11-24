package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.LayoutLanguageBinding
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport

/**
 * Wave 15: a region-bearing language tag reaches the decoders, and the two engines used to
 * disagree about where one user's personal dictionary lives.
 *
 * `GeometricEngineAdapter.dictionaryFor` keyed `custom_words_*` / `disabled_words_*` /
 * `swipe_priority_*` on the raw tag, while `CtcEngineAdapter` went through
 * `CtcLanguageSupport.normalize` (which cuts at the first `-`/`_`). Binding a layout to `fr-CA`
 * therefore put the user's own words in `custom_words_fr-ca` for the geometric engine and
 * `custom_words_fr` for CTC — one finger, one keyboard, two different personal dictionaries,
 * depending only on which engine the router happened to pick for that layout.
 *
 * The TODO that deferred this called a region-bearing tag "latent today, NOT a live bug: every
 * producer feeds a bare code". That premise is what this file disproves at the source: a region
 * tag IS reachable, by design, through layout-language binding.
 */
class LanguageTagResolutionTest {

    @Test
    fun `a layout can legitimately be bound to a region tag`() {
        // If this ever stops holding the rest of the file is moot — but it held, which is why the
        // "no producer feeds a region tag" premise was wrong.
        for (tag in listOf("fr-ca", "en-gb", "pt_br", "es-419")) {
            // Already lowercase, so normalizeCode must hand it back untouched.
            assertThat(LayoutLanguageBinding.normalizeCode(tag)).isEqualTo(tag)
        }
        // Mixed case is accepted too — it is only case-folded, never truncated at the region.
        assertThat(LayoutLanguageBinding.normalizeCode("fr-CA")).isEqualTo("fr-ca")
    }

    @Test
    fun `binding a layout to a region tag reaches primary_language unnormalized`() {
        val resolved = LayoutLanguageBinding.resolve(
            bindings = listOf("fr-ca"),
            currentIndex = 0,
            userPrimary = "en",
            multilangEnabled = true,
            userSecondary = "de",
        )
        // Straight through, no base-subtag stripping — so the decoders DO see `fr-ca`, and
        // normalizing inside each adapter is the only place the split can be closed.
        assertThat(resolved.primary).isEqualTo("fr-ca")
        assertThat(resolved.boundLayoutLanguage).isEqualTo("fr-ca")
    }

    @Test
    fun `the preference-key namespace is the base subtag, shared by both engines`() {
        // Both engines resolve a region tag to the same preference suffix: CTC through
        // `normalize`, the geometric adapter by calling that same function rather than adding a
        // second copy. Asserting the function directly is asserting both call sites, because
        // neither keeps a private normalization any more.
        assertThat(CtcLanguageSupport.normalize("fr-CA")).isEqualTo("fr")
        assertThat(CtcLanguageSupport.normalize("EN-gb")).isEqualTo("en")
        assertThat(CtcLanguageSupport.normalize("pt_BR")).isEqualTo("pt")
        assertThat(CtcLanguageSupport.normalize("es-419")).isEqualTo("es")
        // A bare code is unchanged, so this cannot rewrite an existing user's keys.
        assertThat(CtcLanguageSupport.normalize("fr")).isEqualTo("fr")
    }

    @Test
    fun `contraction data is looked up by the base subtag, not the binding`() {
        // Contraction data is per-SCRIPT. `fr-CA` used to look for `contractions_fr-ca.json` and
        // `langpacks/fr-ca/`, find neither, and hand a French user contractions in NO language —
        // silently. These are the production helpers every lookup in the app now goes through
        // (`ContractionManager`, `WordPredictor`'s three loaders, `ContractionCollisionScanner`).
        assertThat(ContractionManager.packCode("fr-CA")).isEqualTo("fr")
        assertThat(ContractionManager.bundledContractionsAsset("fr-CA"))
            .isEqualTo("dictionaries/contractions_fr.json")
        assertThat(ContractionManager.bundledPairedContractionsAsset("pt_BR"))
            .isEqualTo("dictionaries/contraction_pairs_pt.json")
        // A bare code is unchanged, so no existing lookup moves.
        assertThat(ContractionManager.bundledContractionsAsset("de"))
            .isEqualTo("dictionaries/contractions_de.json")
        // Null/blank is the `usesEnglishBase` case; it must not produce `contractions_.json`.
        assertThat(ContractionManager.packCode(null)).isEmpty()
    }

    @Test
    fun `the pack namespace still answers from the normalized code, for both engines`() {
        // The geometric adapter now asks THIS function instead of composing the path itself, so
        // the two engines cannot disagree about the file even if the resolution changes later.
        assertThat(CtcLanguageSupport.langpackRelativePath("ru"))
            .isEqualTo("langpacks/ru/dictionary.bin")
        // A bundled language never claims a pack — the two lexicon sources stay exclusive.
        for (lang in listOf("en", "fr", "de", "es", "en-GB", "fr-CA")) {
            assertThat(CtcLanguageSupport.langpackRelativePath(lang)).isNull()
        }
        // An unsupported language resolves to nothing, so no tag shape reaches a path component.
        for (tag in listOf("zz", "zz-zz", "../etc", "a/b", "", "   ")) {
            assertThat(CtcLanguageSupport.langpackRelativePath(tag)).isNull()
        }
    }
}
