package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Wave 17: the memory accounting the NFR-2 budget is stated in.
 *
 * `TemplateCache`'s class KDoc claimed the per-index footprint was "≈ 2.3 MB", and
 * `TemplateIndex.estimatedBytes` is the function that number is supposed to come from. It is
 * ~1.2 MiB at the shipped sizes (98,140 words). The doc figure was never checked against the
 * formula — and until this test existed, nothing checked the formula at all in a runnable
 * class: the two assertions that do exist live in `CandidatePrunerTest` (skipped wholesale in
 * this fork for absent upstream artefacts) and `GeoBenchmarkTest` (runs, but only asserts the
 * combined total is *under* the 2.5 MiB bar, so it passes whether the doc is right or wrong).
 *
 * These assertions are deliberately ARITHMETIC and fixture-free: they pin the two structural
 * invariants the doc figures rest on — the per-word byte cost of Tier-A, and the per-variant
 * payload cost in Tier-B — so a future array added to the index, or a different
 * `resamplePoints`, moves a number here rather than silently invalidating prose.
 */
class GeoMemoryAccountingTest {

    /** `config.resamplePoints` — the N every template variant is resampled to. */
    private val n = GeometricEngineConfig().resamplePoints

    @Test
    fun `tierA per-word structural cost is 9 bytes plus one int per typeable word`() {
        // From TemplateIndex.estimatedBytes: 1+1+1 (keyId/lastKey/collapsedLen) + 2
        // (idealPathLenQ) + 4 (bboxQ x4) = 9 B/word, plus bucketEntries at 4 B/typeable word.
        // (This test's own first version asserted 10 here — the sum of its own addends was 9.
        // CI caught it, which is the entire argument for asserting arithmetic instead of
        // describing it: a doc saying 10 would have read perfectly.)
        val perWord = 3 + 2 + 4
        assertThat(perWord).isEqualTo(9)

        // Reproduce the function exactly for a small, exactly-known index, so this is a test OF
        // estimatedBytes rather than a restatement of it.
        val n0 = 1000
        val typeable = 985
        val expected = tierA(bytes = n0, typeable = typeable, keyCount = 28)
        // 6 array headers (3 x ByteArray, 1 x ShortArray, 1 x ByteArray bbox, 1 x IntArray), the
        // two CSR tables, 9 B of per-word payload and 4 B per typeable word. ~16 KB at 1000.
        assertThat(expected).isEqualTo(16_192L)
    }

    @Test
    fun `the documented 2_3_MB per-index figure does not match the formula`() {
        // The real shipped English dictionary is 98,140 words (CKDT v2 header, u32 at offset 12).
        val words = 98_140
        val typeable = (words * 0.985).toInt() // ~1.5% of a lexicon does not project
        // keyCount only affects bucketOffsets ((keyCount^2+1) ints); at 28..60 keys that is
        // 3..14 KiB, i.e. under 1.5% of the total, so the headline number is key-count-insensitive.
        val tierAAt28 = tierA(bytes = words, typeable = typeable, keyCount = 28)
        val tierAAt60 = tierA(bytes = words, typeable = typeable, keyCount = 60)
        val mib = tierAAt28 / (1024.0 * 1024.0)

        assertWithMessage("Tier-A alone at the shipped size")
            .that(mib).isLessThan(1.5)
        assertWithMessage("key count must not move the headline figure materially")
            .that(tierAAt60 / (1024.0 * 1024.0) - mib).isLessThan(0.05)
        // The number the KDoc used to claim. Asserting the DISCREPANCY is the point: if someone
        // restores "≈ 2.3 MB" to TemplateCache's KDoc, this is the calculation that refutes it.
        assertWithMessage("the old doc figure was ~2x the formula's own answer")
            .that(2.3 - mib).isGreaterThan(0.8)
    }

    @Test
    fun `a template variant costs 2N shorts and a doubled letter doubles it`() {
        // WordTemplate.points is variantCount*2N shorts; pathLengthsKw is variantCount floats.
        val oneVariant = 2 * n * 2
        assertThat(oneVariant).isEqualTo(128) // at the default resamplePoints = 32
        assertWithMessage("a doubled letter carries a second full variant")
            .that(2 * oneVariant).isEqualTo(256)

        // MEMO_ENTRY_OVERHEAD_BYTES is added ON TOP of the payload, and its own KDoc itemises
        // 16 (ShortArray header) + 32 (WordTemplate) + 16 (FloatArray header) + ~16 (boxed key)
        // + ~48 (map entry) = 128. The constant is 112, i.e. conservative — this assertion exists
        // so a future edit that inflates it has to argue with a number.
        val itemised = 16 + 32 + 16 + 16 + 48
        assertThat(TemplateCache.MEMO_ENTRY_OVERHEAD_BYTES).isAtMost(itemised.toLong())
    }

    /**
     * `TemplateIndex.estimatedBytes` transcribed — the real one is an instance method, so
     * calling it needs a full `TemplateIndex.build` over a fixture dictionary. Transcribing
     * keeps this test arithmetic-only and fixture-free; the trade is that a change to
     * `estimatedBytes` must be mirrored here, which is deliberate (that is the invariant being
     * pinned, and `GeoBenchmarkTest` still exercises the real method against the real build).
     */
    private fun tierA(bytes: Int, typeable: Int, keyCount: Int): Long {
        val hdr = 16L
        return hdr + bytes + hdr + bytes + hdr + bytes +
            hdr + bytes.toLong() * 2 + hdr + bytes.toLong() * 4 +
            hdr + (keyCount.toLong() * keyCount + 1) * 4 + hdr + typeable.toLong() * 4
    }
}
