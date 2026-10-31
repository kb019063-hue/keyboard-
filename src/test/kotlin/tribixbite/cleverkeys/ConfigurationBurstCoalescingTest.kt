package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * R2/N2 (2026-10-31): a slider drag writes its preference once per increment, and every write
 * used to rebuild the whole [Config] and fan out to every listener — on the main thread,
 * between the frames the finger was moving through. [ConfigurationManager] now applies the
 * FIRST write of a burst immediately and collapses the rest into one trailing refresh.
 *
 * These tests pin the rule that decides which writes are immediate, so the "a single setting
 * change still applies at once" guarantee cannot regress silently.
 */
class ConfigurationBurstCoalescingTest {

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
}
