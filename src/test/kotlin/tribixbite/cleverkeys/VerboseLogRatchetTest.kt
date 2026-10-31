package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * R2 carry-over (2026-10-31): the hunt's report said "383 unguarded `Log.d` calls". Measured
 * properly, with the gate taken to be the ENCLOSING Block — a call counts as guarded when the
 * line it sits on, or the block it sits inside, mentions `BuildConfig.ENABLE_VERBOSE_LOGGING`
 * (the codebase writes the gate as a wrapping `if`, sometimes with several comment lines
 * between gate and call) — the number was 246, and the per-keystroke paths (`Pointers`,
 * `SuggestionHandler`, `Autocapitalisation`, `WordPredictor.predictInternal`) were ALREADY
 * gated by the earlier P1/P2 work. What was left is 246 calls in cold or one-shot paths:
 * dictionary load, backup/restore, model loading, theme preview — where the interpolated string
 * costs nothing because the code runs once.
 *
 * So the fix is not a mass rewrite. The three per-update calls in `SuggestionBar`
 * (`setSuggestionsWithScores` / `clearSuggestions`, which run on every keystroke) are now
 * gated, and this ratchet pins the repo-wide count at the measured baseline so the class cannot
 * grow again. New logging on a hot path must use the gated form — or this test fails and the
 * author has to make that decision explicitly.
 *
 * Runs from the project root and scans source text, like [CoreImeHygieneDriftTest].
 */
class VerboseLogRatchetTest {

    private val mainKotlin = File("src/main/kotlin")

    /**
     * Measured 2026-10-31 on the tree that gates SuggestionBar's per-update calls: 246.
     * This is a CEILING, not a target — when a slice removes more, lower it in the same commit.
     */
    private val MAX_UNGUARDED_LOG_CALLS = 246

    private val logCall = Regex("""\bLog\.[div]\(""")

    private fun isGate(line: String): Boolean =
        line.contains("ENABLE_VERBOSE_LOGGING") || line.contains("isLoggable")

    /**
     * Unguarded debug/verbose calls in [text], keyed by `path:line`. A call is guarded when its
     * own line or any block it is nested inside is a gate. Braces are tracked line by line, in
     * order, so a gate block that spans comments still covers its call.
     */
    private fun unguardedCalls(path: String, text: String): List<String> {
        val stack = ArrayDeque<String>()
        val out = mutableListOf<String>()
        text.lines().forEachIndexed { index, line ->
            if (logCall.containsMatchIn(line) && !isGate(line) && stack.none(::isGate)) {
                out.add("$path:${index + 1}: ${line.trim()}")
            }
            for (ch in line) {
                when (ch) {
                    '{' -> stack.addLast(line)
                    '}' -> if (stack.isNotEmpty()) stack.removeLast()
                }
            }
        }
        return out
    }

    private fun read(relative: String): String {
        val f = File(mainKotlin, relative)
        assertWithMessage("expected source file: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    @Test
    fun unguardedDebugLogCalls_doNotGrow() {
        assertWithMessage("test must run from the project root").that(mainKotlin.isDirectory).isTrue()

        val offenders = mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { unguardedCalls(it.path, it.readText()).asSequence() }
            .sorted()
            .toList()

        assertWithMessage(
            "ARC-R2 log ratchet: ${offenders.size} unguarded Log.d/i/v calls, ceiling is " +
                "$MAX_UNGUARDED_LOG_CALLS. New logging on a path that runs per key/touch/frame " +
                "must be gated: `if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(...)` or the " +
                "file-local `vlog { }`. The ceiling already covers the cold-path calls; if this " +
                "failed, a new unguarded call was added somewhere.\n" +
                offenders.take(40).joinToString("\n")
        ).that(offenders.size).isAtMost(MAX_UNGUARDED_LOG_CALLS)
    }

    @Test
    fun suggestionBarPerUpdateCallsStayGated() {
        // SuggestionBar updates on every keystroke through these two entry points; the rest of
        // its logging (password mode, emoji search, autofill) is rare and stays as it is.
        val bar = read("tribixbite/cleverkeys/SuggestionBar.kt")
        for (marker in listOf(
            "setSuggestionsWithScores: skipped",
            "clearSuggestions: skipped",
            "clearSuggestions called",
        )) {
            assertWithMessage("SuggestionBar's per-update log \"$marker\" must stay gated")
                .that(bar).contains("if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, \"$marker")
        }
    }

    @Test
    fun predictionHotPath_staysGated() {
        // predictInternal runs once per keystroke; slice it by brace depth so the check cannot
        // drift into the declarations that follow it.
        val lines = read("tribixbite/cleverkeys/WordPredictor.kt").lines()
        val start = lines.indexOfFirst { it.contains("fun predictInternal") }
        assertThat(start).isAtLeast(0)

        val body = StringBuilder()
        var depth = 0
        var opened = false
        for (line in lines.subList(start, lines.size)) {
            body.appendLine(line)
            for (ch in line) {
                if (ch == '{') { depth++; opened = true } else if (ch == '}') depth--
            }
            if (opened && depth <= 0) break
        }

        assertWithMessage("predictInternal must keep every log call gated")
            .that(unguardedCalls("WordPredictor.predictInternal", body.toString()))
            .isEmpty()
    }
}
