package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * R2/N2 (2026-10-31): a slider drag writes its preference once per increment, and every write
 * used to rebuild the whole [Config] and fan out to every listener — on the main thread,
 * between the frames the finger was moving through. [ConfigurationManager] now applies the
 * FIRST write of a burst immediately and collapses the rest into one trailing refresh.
 *
 * These tests pin the rule that decides which writes are immediate, so the "a single setting
 * change still applies at once" guarantee cannot regress silently.
 *
 * ## After-refresh delivery (2026-11-11)
 *
 * The second half pins the ORDER: the service's own preference handling reads [Config], so it
 * must run after the refresh — on both the immediate and the trailing path. It used to be a
 * second, independent SharedPreferences listener. Android runs such listeners in an unspecified
 * order (a HashSet over a WeakHashMap's keys), and the coalescing above postponed the refresh
 * for every write but the first of a burst while that listener still ran at once. Either way the
 * open keyboard applied the value from BEFORE the change: one-handed mode stayed one tap behind,
 * and its stop control could not recover it (writing `false` over `false` reports no change).
 * See docs/BUG-HUNT-2026-11-11.md.
 */
class ConfigurationBurstCoalescingTest {

    // Two paths under test log: the guard around the consumer (Log.e) and the public
    // constructor's "no main Looper" fallback (Log.w). CI's android.jar answers 0 for both, the
    // on-device runners' stubs throw instead, so they are mocked here as everywhere else
    // (docs/TEST-PITFALLS.md, "Facts that keep costing time") -- and released again, because a
    // static mock is process-global and would leak into whichever class runs next.
    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun releaseLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun firstWriteAfterStartup_isImmediate() {
        assertWithMessage("no burst in progress (burstStartedAt = 0) must always refresh at once")
            .that(ConfigurationManager.shouldRefreshImmediately(0L, 1_000L)).isTrue()
    }

    @Test
    fun writesInsideTheBurstWindow_areCoalesced() {
        val burstStart = 10_000L
        for (delta in listOf(0L, 1L, 16L, 90L, 149L)) {
            assertWithMessage("a write ${delta}ms after the burst started belongs to the burst")
                .that(ConfigurationManager.shouldRefreshImmediately(burstStart, burstStart + delta))
                .isFalse()
        }
    }

    @Test
    fun writeAfterTheWindow_startsANewBurst() {
        val burstStart = 10_000L
        assertWithMessage("a write just past the window starts a new burst and is applied at once")
            .that(ConfigurationManager.shouldRefreshImmediately(burstStart, burstStart + 151L)).isTrue()
        assertWithMessage("a much later write is a fresh, immediate refresh")
            .that(ConfigurationManager.shouldRefreshImmediately(burstStart, burstStart + 5_000L)).isTrue()
    }

    @Test
    fun aLoneSettingChange_isStillImmediate() {
        // The contract the settings UI relies on: tapping one switch applies within a frame,
        // theme re-inflation included. That write is the first of its burst, so it is immediate.
        val justAfterStartup = 42L
        assertThat(ConfigurationManager.shouldRefreshImmediately(0L, justAfterStartup)).isTrue()
    }

    // ── After-refresh delivery ───────────────────────────────────────────────────────────────

    /** A hand-cranked stand-in for the main Looper: nothing runs until the test says so. */
    private class ManualScheduler : ConfigRefreshScheduler {
        var task: Runnable? = null
        var lastDelayMs = -1L

        override fun postDelayed(task: Runnable, delayMs: Long) {
            this.task = task
            lastDelayMs = delayMs
        }

        override fun cancel(task: Runnable) {
            if (this.task === task) this.task = null
        }

        fun runPending() {
            val pending = task
            task = null
            pending?.run()
        }
    }

    /**
     * A real [ConfigurationManager] whose refresh and whose after-refresh consumer both write
     * into [events], so a test can assert what happened and in which order.
     */
    private class Harness(withScheduler: Boolean = true) {
        val events = mutableListOf<String>()
        var now = 10_000L
        val scheduler = ManualScheduler()
        val prefs: SharedPreferences = mockk(relaxed = true)
        var onUi: ((String?) -> Unit)? = null
        var onRefresh: (() -> Unit)? = null
        val manager: ConfigurationManager

        init {
            val config = mockk<Config>(relaxed = true)
            every { config.refresh(any(), any()) } answers {
                events.add("refresh")
                onRefresh?.invoke()
                Unit
            }
            val context = mockk<Context>(relaxed = true)
            val tracker = mockk<FoldStateTracker>(relaxed = true)
            manager = if (withScheduler) {
                ConfigurationManager(context, config, tracker, scheduler) { now }
            } else {
                ConfigurationManager(context, config, tracker)
            }
            manager.setAfterRefreshListener(object : SharedPreferences.OnSharedPreferenceChangeListener {
                override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
                    events.add("ui:$key")
                    onUi?.invoke(key)
                }
            })
        }

        fun write(key: String?) = manager.onSharedPreferenceChanged(prefs, key)
    }

    @Test
    fun aLoneWrite_refreshesConfigBeforeTheConsumerRuns() {
        val h = Harness()

        h.write("one_handed_enabled")

        assertWithMessage("consumer ran before Config was refreshed (or not at all)")
            .that(h.events).isEqualTo(listOf("refresh", "ui:one_handed_enabled"))
        assertWithMessage("a lone write must leave nothing queued").that(h.scheduler.task).isNull()
    }

    @Test
    fun aWriteInsideTheBurst_reachesTheConsumerOnlyAfterTheTrailingRefresh() {
        val h = Harness()

        h.write("one_handed_enabled")       // tap: on
        h.now += 100
        h.write("one_handed_scale")         // inside the 150 ms window: refresh is postponed

        assertWithMessage("2nd write was delivered against a Config not refreshed yet (stuck-toggle bug)")
            .that(h.events).isEqualTo(listOf("refresh", "ui:one_handed_enabled"))
        assertThat(h.scheduler.lastDelayMs).isEqualTo(100L)

        h.scheduler.runPending()

        assertWithMessage("2nd write must be delivered right after the trailing refresh")
            .that(h.events)
            .isEqualTo(listOf("refresh", "ui:one_handed_enabled", "refresh", "ui:one_handed_scale"))
    }

    @Test
    fun oneKeyWrittenManyTimesInABurst_reachesTheConsumerOnce() {
        val h = Harness()

        h.write("theme")
        repeat(9) {
            h.now += 16                     // a slider drag: one write per frame
            h.write("keyboard_height")
        }
        h.scheduler.runPending()

        assertWithMessage("slider key must be delivered once per refresh, not once per write")
            .that(h.events).isEqualTo(listOf("refresh", "ui:theme", "refresh", "ui:keyboard_height"))
    }

    @Test
    fun aNewBurst_deliversWhatTheSupersededTrailingRefreshOwed() {
        val h = Harness()

        h.write("a")
        h.now += 100
        h.write("b")                        // owed to the trailing refresh
        h.now += 60                         // 160 ms after the burst began: a new burst
        h.write("c")                        // immediate, and it cancels that trailing refresh

        assertWithMessage("key owed to a cancelled trailing refresh was dropped")
            .that(h.events).isEqualTo(listOf("refresh", "ui:a", "refresh", "ui:b", "ui:c"))
        assertWithMessage("the superseded trailing refresh must be cancelled").that(h.scheduler.task).isNull()
    }

    @Test
    fun aConsumerThatThrows_doesNotLoseTheKeysBehindIt() {
        val h = Harness()
        h.onUi = { key -> if (key == "a" || key == "b") throw IllegalStateException("boom") }

        h.write("a")                        // must not propagate: a UI update cannot take the IME down
        h.now += 50
        h.write("b")                        // owed to the trailing refresh, and throws there
        h.now += 10
        h.write("c")                        // owed behind it, in the same delivery
        h.scheduler.runPending()

        assertWithMessage("a key behind a throwing one in the same delivery was dropped")
            .that(h.events).isEqualTo(listOf("refresh", "ui:a", "refresh", "ui:b", "ui:c"))
    }

    @Test
    fun aConsumerThatWritesAPreference_reEntersSafely() {
        val h = Harness()
        var nested = false
        h.onUi = { key ->
            if (key == "a" && !nested) {
                nested = true
                h.write("written_by_consumer")
            }
        }

        h.write("a")
        h.scheduler.runPending()

        assertThat(h.events)
            .isEqualTo(listOf("refresh", "ui:a", "refresh", "ui:written_by_consumer"))
    }

    @Test
    fun aKeyWrittenWhileTheRefreshRuns_waitsForARefreshThatCoversIt() {
        // A config listener that saves a preference from inside the refresh (the current layout
        // is written that way). That refresh may already have read the old value.
        val h = Harness()
        var nested = false
        h.onRefresh = {
            if (!nested) {
                nested = true
                h.write("written_during_refresh")
            }
        }

        h.write("a")

        assertWithMessage("a key written mid-refresh was delivered against that same refresh")
            .that(h.events).isEqualTo(listOf("refresh", "ui:a"))

        h.scheduler.runPending()

        assertThat(h.events)
            .isEqualTo(listOf("refresh", "ui:a", "refresh", "ui:written_during_refresh"))
    }

    @Test
    fun withoutAConsumer_refreshesStillHappen() {
        val h = Harness()
        h.manager.setAfterRefreshListener(null)      // service teardown

        h.write("a")
        h.now += 100
        h.write("b")
        h.scheduler.runPending()

        assertThat(h.events).isEqualTo(listOf("refresh", "refresh"))
    }

    @Test
    fun withoutAMainLooper_everyWriteIsRefreshedAndDeliveredAtOnce() {
        // The public constructor on a plain JVM: no Looper, so nothing can be postponed.
        val h = Harness(withScheduler = false)

        h.write("a")
        h.write("b")

        assertThat(h.events).isEqualTo(listOf("refresh", "ui:a", "refresh", "ui:b"))
    }

    /**
     * The wiring half. If the service registers ITSELF on the SharedPreferences again, it is
     * back to racing the manager's refresh, and every test above still passes — so this pins the
     * two symbols rather than leaving it to the next person to remember. Whitespace-tolerant on
     * purpose (docs/TEST-PITFALLS.md, entries 8 and 10).
     */
    @Test
    fun theService_handlesPreferencesThroughTheManager_notAsASecondListener() {
        val service = File("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt").readText()

        assertWithMessage("CleverKeysService must call _configManager.setAfterRefreshListener(this)")
            .that(Regex("""_configManager\s*\.\s*setAfterRefreshListener\(\s*this\s*\)""").containsMatchIn(service))
            .isTrue()
        assertWithMessage("CleverKeysService must not register itself on SharedPreferences (unordered vs the refresh)")
            .that(Regex("""(?<!un)registerOnSharedPreferenceChangeListener\(\s*this\s*\)""").containsMatchIn(service))
            .isFalse()
    }
}
