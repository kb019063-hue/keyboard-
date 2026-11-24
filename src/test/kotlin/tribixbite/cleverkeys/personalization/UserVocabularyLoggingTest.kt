package tribixbite.cleverkeys.personalization

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.Collections
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The vocabulary store must never put user text in its log.
 *
 * [UserVocabulary] is a user's typing history: every entry is a word they actually typed. The
 * repository's rule is that user text, app package names and field names never appear in logcat
 * (`AGENTS.md`), and `Logs`/`PrivacyManager` strip words from their own lines to honour it.
 *
 * This store's eviction path did not: `removeLowestValueWords` logged
 * `"Removed low-value word: ${usage.word}"`. Worse than a normal violation because
 *  - its singleton logger is ungated `Log.d` — `ENABLE_VERBOSE_LOGGING` is not consulted at all,
 *    so the installed debug build printed it on every eviction, and
 *  - `VerboseLogRatchetTest` cannot see it: that test scans for `Log.d(...)` call sites, and this
 *    one is spelled `log(...)` through an injected lambda.
 *
 * The count is what an eviction log is for, so only counts are logged now. These tests drive both
 * real eviction paths — one word over the cap on the typing hot path, and a load-time trim when
 * the user has lowered `personalization_max_words` below the persisted size — and assert that no
 * logged line ever contains a word that was in the vocabulary, including the evicted one.
 */
class UserVocabularyLoggingTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    /** Every message handed to the store's logger, in order. */
    private val logged: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun newVocab(storage: InMemoryLearnedStorage = InMemoryLearnedStorage(), cap: Int = 100) =
        UserVocabulary(
            storage,
            debounceMs = 60_000,
            maxDelayMs = 120_000,
            scheduler = scheduler,
            log = { msg, _ -> logged.add(msg) },
            maxWords = { cap },
        )

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** Assert no logged line mentions [words]. */
    private fun assertNoWordLeaked(words: Collection<String>) {
        // Snapshot under the list's own lock: the persistence scheduler may log concurrently.
        val lines = synchronized(logged) { logged.toList() }
        val leaked = lines.flatMap { line ->
            words.filter { word -> line.contains(word) }.map { hit -> "$hit  <-  \"$line\"" }
        }
        assertTrue(
            "The vocabulary log leaked user text (words are typing history):\n" +
                leaked.joinToString("\n") + "\n\nAll lines:\n" + lines.joinToString("\n"),
            leaked.isEmpty()
        )
    }

    @Test
    fun `eviction at capacity does not log the evicted word`() {
        val cap = 100 // MIN_VOCABULARY_CAP
        val vocab = newVocab(cap = cap)
        val now = System.currentTimeMillis()

        // Fill to the cap with words that all have some usage, so eviction has a real victim.
        val words = (1..cap).map { "secretword$it" }
        for ((index, word) in words.withIndex()) {
            repeat(index + 2) { vocab.recordWordUsage(word, timestamp = now) }
        }
        assertEquals(cap, vocab.size())
        logged.clear() // ignore the init/load lines

        // One more word forces exactly one eviction.
        vocab.recordWordUsage("freshword", timestamp = now)

        assertEquals("cap must still hold", cap, vocab.size())
        assertNoWordLeaked(words + "freshword")
    }

    /**
     * The other road into `removeLowestValueWords`: a vocabulary persisted ABOVE the current cap
     * (the user lowered `personalization_max_words` since the blob was written) is trimmed by
     * [UserVocabulary.enforceCap] on load, in the constructor — before any test can clear the
     * log. So this asserts over the whole log rather than a cleared window.
     */
    @Test
    fun `load-time trim to a lowered cap does not log the words it evicts`() {
        val storage = InMemoryLearnedStorage()
        val now = System.currentTimeMillis()
        val words = (1..150).map { "privateword$it" }

        // First store at a cap of 200, so all 150 words persist.
        val big = newVocab(storage, cap = 200)
        for ((index, word) in words.withIndex()) {
            repeat(index + 2) { big.recordWordUsage(word, timestamp = now) }
        }
        big.flush()
        assertEquals(150, big.size())

        // A second store over the same storage, with a cap of 100: the constructor trims 50 on
        // load, and every one of those evictions used to print the word.
        val small = newVocab(storage, cap = 100)

        assertEquals("the lowered cap must have trimmed", 100, small.size())
        assertNoWordLeaked(words)
    }

    @Test
    fun `no log line ever contains a recorded word`() {
        val vocab = newVocab(cap = 130)
        val now = System.currentTimeMillis()
        val words = (1..150).map { "leakcanary$it" }

        for ((index, word) in words.withIndex()) {
            repeat((index % 5) + 2) { vocab.recordWordUsage(word, timestamp = now) }
        }
        vocab.cleanupStaleWords(now)
        vocab.flush()

        assertNoWordLeaked(words)
        assertTrue("logger should have been called at all", logged.isNotEmpty())
    }

    /** Guard against a silent regression to word-printing: the count must still be reported. */
    @Test
    fun `eviction still reports how many words it removed`() {
        val cap = 100
        val vocab = newVocab(cap = cap)
        val now = System.currentTimeMillis()
        for (i in 1..cap) {
            repeat(i + 2) { vocab.recordWordUsage("w$i", timestamp = now) }
        }
        logged.clear()

        vocab.recordWordUsage("extra", timestamp = now)

        assertTrue(
            "eviction should log a count, got: $logged",
            logged.any { it.contains("low-value") && it.contains("1") }
        )
        assertFalse(
            "the count line must not contain a word",
            logged.any { it.contains("Removed low-value word:") }
        )
    }
}
