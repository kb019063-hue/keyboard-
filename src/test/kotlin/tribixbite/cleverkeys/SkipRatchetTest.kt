package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Ratchets how many tests this checkout is allowed to silently stop running.
 *
 * ## Why this exists
 *
 * The 2026-11-15 hunt (part 4) found **70 tests that had never executed here**. Nobody deleted
 * them. A `@Before` calling `Assume.assumeTrue` skips *every test in the class*, and four classes
 * carried a guard for an artefact only some of their tests needed. The suite summary could not
 * show it, because a skipped test is still counted: `4847 run in 430 classes` was identical before
 * and after 70 tests were recovered. The only number that moved was `skipped`, and nothing was
 * watching it. This is that watcher.
 *
 * ## The one exact rule
 *
 * [noGuardInsideALifecycleMethod]. It is not a heuristic — it is JUnit's semantics. `@Before`,
 * `@BeforeClass`, `@BeforeEach` and `@BeforeAll` all run on behalf of the whole class, so an
 * `Assume` (or `UpstreamArtefacts.require`, which is one) inside one skips every test in it. There
 * is nothing to argue about case by case: a guard in a lifecycle method is *at least* a class-wide
 * skip. That is the mechanism that cost 70 tests, and it is now measured at **20** and capped.
 *
 * ## The two ceilings, and why they are separate
 *
 *  - [MAX_LIFECYCLE_GUARDS] = **20** — guards inside lifecycle methods. The dangerous kind.
 *  - [MAX_GUARD_SITES] = **25** — `UpstreamArtefacts.require(...)` call sites. The sanctioned kind.
 *
 * There is deliberately no ceiling on *direct* `Assume` use outside the owner file. There are
 * **63** such sites across **29** files today — every replay/eval harness in `swipe/`,
 * `autocorrect/` and `androidTest` gates itself that way — so a "route them all through
 * UpstreamArtefacts" rule would be aspirational rather than true, and a ratchet that fails on the
 * tree it was added to is a ratchet nobody keeps. What is pinned instead is that the count must
 * not *grow*: [directAssumeUse_doesNotGrow].
 *
 * Everything is counted with comments stripped. The prose in this repository discusses `Assume`
 * constantly — including in this sentence — and a scan over raw text would count documentation as
 * a guard. `import` lines are excluded: nine files import `Assume.assumeTrue` and an import is not
 * a skip.
 *
 * ## What it deliberately does NOT assert
 *
 * *"Every test in a guarded class must reference the artefact it guards on."* Tried and rejected. A
 * `@Test` body does not include the `@Before`, and tests reach a langpack through a field
 * initializer or a helper (`GeoTestFixtures`) rather than in their own body, so a per-body token
 * scan reports them as artefact-free when they are not — it flagged `GeoAccuracyJcukenRuTest`'s six
 * Russian tests as free because that class names `russianCkdt()` and nothing else. Separating
 * "which test needs which artefact" is a review activity, not an assertion; see
 * `docs/BUG-HUNT-2026-11-15-PART-4.md` §P6.
 *
 * ## Relationship to CI
 *
 * These are source counts, not the runner's skip count. CI reported **4847 tests, 134 skipped in
 * 430 classes** at this baseline. The two measure different things and must not be compared; they
 * only need to move together. When they disagree, CI is right.
 *
 * Source scan, runs from the project root, like [CoreImeHygieneDriftTest] and
 * [VerboseLogRatchetTest].
 */
class SkipRatchetTest {

    private val testRoots = listOf(File("src/test/kotlin"), File("src/androidTest"))

    /** The sanctioned home of a skip. Named so a reader can find the one place to change. */
    private val assumptionOwner = "src/test/kotlin/tribixbite/cleverkeys/UpstreamArtefacts.kt"

    /** This file, which legitimately contains the patterns it searches for. */
    private val self = "src/test/kotlin/tribixbite/cleverkeys/SkipRatchetTest.kt"

    // ------------------------------------------------------------------ measurements

    /**
     * Measured 2026-11-15 after recovering 70 tests: **20** guards in lifecycle methods.
     *
     * A CEILING, not a target. Lower it in the same commit that removes one — the rule
     * [VerboseLogRatchetTest] applies to its log ceiling.
     */
    private val MAX_LIFECYCLE_GUARDS = 20

    /** Measured 2026-11-15: **25** `UpstreamArtefacts.require(...)` call sites. */
    private val MAX_GUARD_SITES = 25

    /** Measured 2026-11-15: **63** direct `Assume`/`@Ignore` sites outside [assumptionOwner]. */
    private val MAX_DIRECT_ASSUMPTIONS = 63

    /**
     * The runner's number at this baseline, quoted so the next author knows where to look.
     *
     * `full-test-suite.yml` on the tree that set these ceilings:
     * `unit tests: 4847 run, 0 failed, 134 skipped in 430 classes` (run 44873156941). Re-read it
     * from a green run before changing any ceiling.
     */
    private val ciSkipBaseline =
        "CI baseline at these ceilings: 4847 tests, 134 skipped in 430 classes " +
            "(full-test-suite.yml run 44873156941). The skip figure is the \"S skipped\" number " +
            "in its summary annotation."

    private val requireCall = Regex("""UpstreamArtefacts\.require""")
    private val directAssume = Regex("""\b(Assume|assumeTrue|assumeFalse|assumeNoException|@Ignore)\b""")
    private val anyGuard = Regex("""(UpstreamArtefacts\.require|Assume|assumeTrue|assumeFalse|assumeNoException|@Ignore)""")
    private val lifecycle = Regex("""^\s*@(Before|BeforeClass|BeforeEach|BeforeAll)\b""")

    /** Blank out comments so prose about `Assume` is not counted as a use of it. */
    private fun stripComments(source: String): String {
        val noBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
            .replace(source) { match -> "\n".repeat(match.value.count { it == '\n' }) }
        return Regex("""//[^\n]*""").replace(noBlock, "")
    }

    private fun kotlinFiles(): List<File> {
        assertWithMessage("test must run from the project root")
            .that(testRoots.first().isDirectory).isTrue()
        return testRoots.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }
    }

    private fun codeLines(file: File): List<String> = stripComments(file.readText()).lines()

    private fun isSelf(file: File) = file.path.replace('\\', '/').endsWith(self)

    /** Lines that mention [pattern], skipping `import` lines: an import is not a skip. */
    private fun sitesMatching(pattern: Regex): List<String> {
        val sites = mutableListOf<String>()
        for (file in kotlinFiles()) {
            if (isSelf(file)) continue
            for ((index, line) in codeLines(file).withIndex()) {
                if (line.trim().startsWith("import ")) continue
                if (pattern.containsMatchIn(line)) {
                    sites.add("${file.path}:${index + 1}: ${line.trim()}")
                }
            }
        }
        return sites
    }

    /** Direct `Assume`/`@Ignore` use outside [assumptionOwner]. */
    private fun directAssumptions(): List<String> =
        sitesMatching(directAssume).filterNot { it.startsWith(assumptionOwner) }

    /**
     * Guards inside JUnit lifecycle methods: the class-wide skip mechanism.
     *
     * Bodies are found by brace depth from the declaration, so a nested `try`/`if` cannot
     * truncate the scan.
     */
    private fun lifecycleGuards(): List<String> {
        val found = mutableListOf<String>()
        for (file in kotlinFiles()) {
            if (isSelf(file)) continue
            val lines = codeLines(file)

            for ((start, line) in lines.withIndex()) {
                if (!lifecycle.containsMatchIn(line)) continue

                var decl = start + 1
                while (decl < lines.size && !lines[decl].contains("fun ")) decl++
                if (decl >= lines.size) continue

                val body = StringBuilder()
                var depth = 0
                var opened = false
                for (index in decl until lines.size) {
                    body.appendLine(lines[index])
                    for (ch in lines[index]) {
                        when (ch) {
                            '{' -> { depth++; opened = true }
                            '}' -> depth--
                        }
                    }
                    if (opened && depth <= 0) break
                }

                if (anyGuard.containsMatchIn(body.toString())) {
                    val name = Regex("""fun (\w+)""").find(body)?.groupValues?.get(1) ?: "?"
                    found.add("${file.path}:${start + 1}: ${line.trim()} $name(...)")
                }
            }
        }
        return found
    }

    // ------------------------------------------------------------------- assertions

    @Test
    fun noGuardInsideALifecycleMethod() {
        val guards = lifecycleGuards()

        assertWithMessage(
            "Skip ratchet: ${guards.size} guard(s) in a JUnit lifecycle method, ceiling is " +
                "$MAX_LIFECYCLE_GUARDS.\n" +
                "A guard in `@Before` / `@BeforeClass` skips EVERY test in the class, not just " +
                "the ones that need the artefact — that is exactly how 70 tests were lost here in " +
                "2026-11-15 (see docs/BUG-HUNT-2026-11-15-PART-4.md). Put the call in the one " +
                "test that needs it:\n\n" +
                "    @Test\n" +
                "    fun theThingThatNeedsAPack() {\n" +
                "        UpstreamArtefacts.require(UpstreamArtefacts.LANGPACK_DIR)\n" +
                "        ...\n" +
                "    }\n\n" +
                "then lower $MAX_LIFECYCLE_GUARDS. Remaining lifecycle guards:\n" +
                guards.joinToString("\n") + "\n\n" + ciSkipBaseline
        ).that(guards.size).isAtMost(MAX_LIFECYCLE_GUARDS)
    }

    @Test
    fun upstreamRequireSites_doNotGrow() {
        val sites = sitesMatching(requireCall)
        assertWithMessage(
            "Skip ratchet: ${sites.size} `UpstreamArtefacts.require(...)` call sites, ceiling " +
                "is $MAX_GUARD_SITES. A new skip needs a decision — per-test (fine) or " +
                "class-wide (see noGuardInsideALifecycleMethod). Lower the ceiling if you " +
                "removed any.\n" + ciSkipBaseline + "\n\n" + sites.joinToString("\n")
        ).that(sites.size).isAtMost(MAX_GUARD_SITES)
    }

    @Test
    fun directAssumeUse_doesNotGrow() {
        val sites = directAssumptions()
        assertWithMessage(
            "Skip ratchet: ${sites.size} direct `Assume`/`@Ignore` sites outside " +
                "$assumptionOwner, ceiling is $MAX_DIRECT_ASSUMPTIONS. The replay/eval harnesses " +
                "gate themselves this way and that is accepted; this ceiling only stops the " +
                "number growing. New per-test skips should prefer " +
                "`UpstreamArtefacts.require(...)`.\n" + ciSkipBaseline + "\n\n" +
                sites.take(25).joinToString("\n")
        ).that(sites.size).isAtMost(MAX_DIRECT_ASSUMPTIONS)
    }

    /**
     * Keeps the measurements honest: a ceiling must equal a number somebody actually counted, and
     * the source counts must not be confused with the runner's skip count.
     */
    @Test
    fun ceilingsTrackTheMeasuredTree() {
        val guards = lifecycleGuards().size
        val requireSites = sitesMatching(requireCall).size
        val direct = directAssumptions().size

        assertWithMessage(
            "Measured $guards lifecycle guards, ceiling $MAX_LIFECYCLE_GUARDS — the ceiling " +
                "must be the measurement, so that lowering is the only honest direction."
        ).that(MAX_LIFECYCLE_GUARDS).isAtMost(guards)
        assertWithMessage(
            "Measured $requireSites `require` sites, ceiling $MAX_GUARD_SITES."
        ).that(MAX_GUARD_SITES).isAtMost(requireSites)
        assertWithMessage(
            "Measured $direct direct `Assume` sites, ceiling $MAX_DIRECT_ASSUMPTIONS."
        ).that(MAX_DIRECT_ASSUMPTIONS).isAtMost(direct)

        assertWithMessage(
            "The source counts ($guards/$requireSites/$direct) and the runner's skip count (134) " +
                "are different quantities. If they ever coincide, this assertion is misleading " +
                "and should be replaced by the CI figure from a green run."
        ).that(listOf(guards, requireSites, direct)).doesNotContain(134)

        assertThat(assumptionOwner).endsWith("UpstreamArtefacts.kt")
        assertWithMessage("the owner file must still exist at $assumptionOwner")
            .that(File(assumptionOwner).isFile).isTrue()
    }
}
