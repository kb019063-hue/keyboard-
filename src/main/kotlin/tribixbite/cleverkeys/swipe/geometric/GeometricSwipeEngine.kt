package tribixbite.cleverkeys.swipe.geometric

import tribixbite.cleverkeys.PredictionResult

/**
 * The geometric swipe decoder facade: wires
 * `GesturePreprocessor → CandidatePruner → PathScorer → CandidateRanker` together
 * over a [TemplateCache], implementing the [SwipeDecodingEngine] router seam.
 *
 * ```
 * decode(request):
 *   guard degenerate input        → empty PredictionResult
 *   getOrBuild cached Tier-A index (warmUp fallback)
 *   guard DEAD_LAYOUT / empty dict → empty PredictionResult
 *   preprocess raw trace          → ProcessedGesture (§1)
 *   prune                          → ≤ maxCandidatesScored ordinals (Tier-A only)
 *   materialize + score survivors  → S(w) per candidate (Tier-B memo)  (§3–§6)
 *   rank                           → PredictionResult (top-K, softmax→0–1000) (§7)
 * ```
 *
 * **Thread-safety** (contract on [SwipeDecodingEngine]): the cache is internally
 * synchronized; `decode()` snapshots the [TemplateCache.CachedIndex] and its immutable
 * Tier-A [TemplateIndex] and then scores lock-free (each decode allocates its own
 * [PathScorer]/scratch buffers, so no scoring state is shared across threads). The
 * Tier-B template memo it reads is an independently-synchronized LRU. The core spawns
 * no threads and reads no preferences (NFR-3). Two concurrent decodes on the same
 * `(layout, dictionary)` produce identical output (NFR-4).
 *
 * **Scores are engine-relative** (fixed-temperature softmax posterior × 1000) — never
 * comparable to the CTC decoder's log-probabilities; the router must not cross-compare them.
 *
 * Purity (NFR-3): `kotlin.*` + the pure [PredictionResult] only.
 */
class GeometricSwipeEngine(
    private val config: GeometricEngineConfig = GeometricEngineConfig(),
    private val cache: TemplateCache = TemplateCache(config),
) : SwipeDecodingEngine {

    /** Stateless, config-only; safe to share across decodes/threads. */
    private val preprocessor = GesturePreprocessor(config)
    private val pruner = CandidatePruner(config)
    private val ranker = CandidateRanker(config)

    /** Empty result reused for every degenerate / dead-layout short-circuit. */
    private val emptyResult = PredictionResult(emptyList(), emptyList())

    override fun decode(request: GeometricSwipeRequest): PredictionResult {
        // ── Error Handling: degenerate input → empty result, never throws. ────────
        val points = request.points
        if (points.size < 3) return emptyResult
        if (request.keyAreaWidthPx <= 0f || request.keyAreaHeightPx <= 0f) return emptyResult
        // Non-finite coordinates defeat every downstream guard (NaN comparisons are
        // uniformly false, so the resampler's zero-length check and nearestKeys'
        // insertion window both silently pass a poisoned polyline through to a
        // negative-key-id CSR lookup). Reject them up front.
        for (k in points.indices) {
            val p = points[k]
            if (!p.x.isFinite() || !p.y.isFinite()) return emptyResult
        }
        val layout = request.layout
        val dictionary = request.dictionary
        if (dictionary.size == 0) return emptyResult
        if (layout.letterNodeCount < 2) return emptyResult // < 2 letter nodes → dead

        // ── Tier-A index: cache hit, or synchronous build fallback (caller skipped
        //    warmUp — a documented caller-contract violation). ─────────────────────
        val cached = cache.getOrBuild(layout, dictionary)
        val index = cached.index
        if (index.deadLayout || index.typeableCount == 0) return emptyResult

        // ── Preprocess (§1). A zero-length trace (all coincident) survives with a
        //    finite ProcessedGesture but will score uniformly; the ranker still emits a
        //    result. A truly zero-length trace is guarded here as "nothing to decode". ─
        val gesture = preprocessor.process(
            points, request.keyAreaWidthPx, request.keyAreaHeightPx, layout,
        )
        if (gesture.pathLengthKw <= 0f) return emptyResult // zero-length trace

        // ── Prune (Tier-A metadata only) → ≤ maxCandidatesScored ordinals. ────────
        val survivors = pruner.prune(gesture, index, layout)
        if (survivors.isEmpty()) return emptyResult

        // ── Score survivors with a fail-fast top-K (ASK pattern): keep only the best
        //    `maxResults` by S(w); a candidate that cannot beat the current worst of a
        //    full top-K is dropped without materializing further work. Each decode owns
        //    its scorer (single-thread-scoped scratch buffers). ─────────────────────
        val scorer = PathScorer(config)
        val topK = TopKScored(config.maxResults)
        for (ordinal in survivors) {
            val template = cached.template(ordinal) ?: continue // untypeable guard (never proposed)
            // User swipe priority (GeometricDictionary.swipeBonus): added only to pruner
            // survivors, so it re-ranks words the trace already reached; 0 for base words.
            val s = scorer.score(gesture, template, layout, ordinal) + dictionary.swipeBonus(ordinal)
            // Only the finite scores contribute; the loop-primary rule + clamps make
            // non-finite impossible, but stay defensive so no NaN escapes the ranker.
            if (!s.isFinite()) continue
            topK.offer(ordinal, dictionary.word(ordinal), s)
        }

        // ── Rank → PredictionResult (§7). The reversal count feeds the OQ-11
        //    confidence temperature only (never ranking — see CandidateRanker.rank). ─
        return ranker.rank(topK.toList(), gesture.reversalCount)
    }

    override fun warmUp(layout: LayoutGeometry, dictionary: GeometricDictionary): WarmUpResult =
        cache.warmUp(layout, dictionary)

    override fun evict(layoutFingerprint: String, language: String) =
        cache.evict(layoutFingerprint, language)

    /**
     * A fixed-capacity best-K collector with a fail-fast admission test (ASK pattern):
     * once full, a candidate whose score cannot beat the current worst is rejected in
     * O(1). Deterministic tie handling is deferred to [CandidateRanker] (which does the
     * authoritative score-desc / ordinal-asc / lexicographic sort) — this collector
     * only bounds the working set, so its internal ordering need not be total, but it
     * MUST retain a superset of the true top-K under any tie. To guarantee that, the
     * admission threshold uses strict `>` on the worst score AND keeps ALL candidates
     * tied at the current worst-admitted score (so a tie never silently drops a word
     * the ranker would have preferred by ordinal). Capacity is a soft floor: ties at
     * the boundary can transiently exceed it; the ranker re-caps to `maxResults`.
     *
     * **Known cost of that guarantee** (wave 16, 2026-11-15 — measured, not fixed). Kept
     * ties are never dropped, so a candidate that finds no vacancy does nothing and the
     * working set only grows: a group of N candidates sharing the exact worst admitted
     * float score leaves `size ≈ min(N, survivors)`, re-sorting that whole set on every
     * subsequent offer (`kthLargest` copies + sorts). Simulated at the production bounds
     * (`maxCandidatesScored = 1200`, `maxResults` 3..15): the pathological all-ties case
     * reaches a 1200-element working set and ~7.6M comparison-ops of bookkeeping, versus
     * ~52k if the set stayed at K — ~146x, on the order of 100 ms on the decode thread for
     * a single swipe. Every other case stays at K.
     *
     * It is NOT a correctness bug and it is deliberately NOT rewritten here. A K-bounded
     * collector was simulated against this one over 4000 randomized candidate sets
     * (distinct / small-tie / all-tie / mixed scores) and their ranker output differed in
     * 75% of trials, because this collector preserves a tie group while a bounded one
     * keeps whichever members it happened to see. That makes the rewrite a selection
     * change, not a fix — and per the hunter's own rule for calibrated score paths, a
     * selection change needs a full-grid accuracy A/B (`GeoAccuracy*` + `GeoAblationTest`
     * at `-PgeoFull`) before it can ship. A mechanical fix was also not available: an
     * earlier attempt at "evict the newest tie" was wrong in the other direction, evicting
     * the best item, which is what the simulation was for.
     *
     * The one realizable part of the trigger — duplicate personal-dictionary words landing
     * in one bucket — is closed at the source instead: see
     * [GeometricUserWordMerge.merge]'s case-folded dedupe.
     */
    private class TopKScored(private val capacity: Int) {
        private val items = ArrayList<CandidateRanker.Scored>(capacity + 4)
        // The K-th best score once we are at/over capacity; below it we never admit.
        private var worstAdmitted = Float.NEGATIVE_INFINITY

        fun offer(ordinal: Int, word: String, score: Float) {
            if (items.size >= capacity && score < worstAdmitted) return // fail-fast reject
            items.add(CandidateRanker.Scored(ordinal, word, score))
            // Recompute the worst-admitted threshold as the K-th largest score. This
            // sorts a fresh copy — O(size·log size) per admitted offer — but only runs
            // when we are at/over capacity, and `size` is bounded by capacity + the
            // number of boundary ties (tiny, maxResults=10 default); the dominant cost
            // stays the per-candidate scoring, not this bookkeeping.
            if (items.size >= capacity) {
                worstAdmitted = kthLargest(capacity)
                // Prune anything strictly below the new threshold (boundary ties kept).
                if (items.size > capacity) pruneBelow(worstAdmitted)
            }
        }

        /** The [k]-th largest score currently held (1-based k), or -inf if fewer than k. */
        private fun kthLargest(k: Int): Float {
            if (items.size < k) return Float.NEGATIVE_INFINITY
            val scores = FloatArray(items.size) { items[it].score }
            scores.sort() // ascending
            return scores[scores.size - k] // k-th from the top
        }

        /** Drop items strictly below [threshold]; keep ties AT the threshold. */
        private fun pruneBelow(threshold: Float) {
            var w = 0
            for (r in items.indices) {
                if (items[r].score >= threshold) {
                    items[w] = items[r]; w++
                }
            }
            while (items.size > w) items.removeAt(items.size - 1)
        }

        fun toList(): List<CandidateRanker.Scored> = items
    }
}
