package tribixbite.cleverkeys

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [ProcessStartLog]: the list of times the keyboard was set up from nothing, and the sentence
 * the Keyboard speed card makes of it. The sentence is what the owner reads off the phone to
 * tell "the phone keeps killing the keyboard" from "the keyboard itself is slow", so the counting
 * is pinned here.
 */
class ProcessStartLogTest {

    private lateinit var dir: File
    private lateinit var file: File

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "start-log-test-${System.nanoTime()}")
        dir.mkdirs()
        file = File(dir, "keyboard-starts.txt")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aStartIsWrittenDown_andReadBack() {
        assertTrue(ProcessStartLog.read(file).isEmpty())
        ProcessStartLog.record(file, now - hour)
        ProcessStartLog.record(file, now)
        assertEquals(listOf(now - hour, now), ProcessStartLog.read(file))
        assertTrue("no scratch file is left behind", dir.list()!!.toList() == listOf("keyboard-starts.txt"))
    }

    @Test
    fun onlyTheLastFewDozenAreKept() {
        for (i in 0 until ProcessStartLog.KEEP + 15) ProcessStartLog.record(file, now + i)
        val kept = ProcessStartLog.read(file)
        assertEquals(ProcessStartLog.KEEP, kept.size)
        assertEquals("the newest survive", now + ProcessStartLog.KEEP + 14, kept.last())
        assertEquals(now + 15, kept.first())
    }

    @Test
    fun aTimeFromTheFuture_isDroppedWhenTheNextStartIsRecorded() {
        // The clock was set back since: such a time would never age out of "the last 24 hours".
        file.writeText("${now + 5 * hour}\n${now - hour}\n")
        ProcessStartLog.record(file, now)
        assertEquals(listOf(now - hour, now), ProcessStartLog.read(file))
    }

    @Test
    fun aFileThatIsNotAListOfTimes_isReadAsWhatCanBeRead() {
        file.writeText("garbage\n\n${now - hour}\n-4\n  ${now}  \n")
        assertEquals(listOf(now - hour, now), ProcessStartLog.read(file))
    }

    @Test
    fun aPlaceThatCannotBeWritten_costsNothing() {
        // A directory where the file should be: reading finds nothing, recording gives up quietly.
        val blocked = File(dir, "blocked").apply { mkdirs() }
        ProcessStartLog.record(blocked, now)
        assertTrue(ProcessStartLog.read(blocked).isEmpty())
    }

    @Test
    fun summary_countsTheLastDay_andSaysHowLongAgoTheLatestWas() {
        val starts = listOf(now - 30 * hour, now - 20 * hour, now - 3 * hour, now - 12 * minute)
        assertEquals(
            "Set up from nothing: 3 times in the last 24 hours (1 in the last hour); the latest 12 min ago",
            ProcessStartLog.summary(starts, now)
        )
    }

    @Test
    fun summary_ofOneStart_saysOnce() {
        assertEquals(
            "Set up from nothing: once in the last 24 hours; the latest 3 h 05 min ago",
            ProcessStartLog.summary(listOf(now - 3 * hour - 5 * minute), now)
        )
        assertEquals(
            "Set up from nothing: once in the last 24 hours; the latest under a minute ago",
            ProcessStartLog.summary(listOf(now - 20_000L), now)
        )
    }

    @Test
    fun summary_doesNotRepeatTheHourWhenEveryStartWasInIt() {
        val starts = listOf(now - 40 * minute, now - 5 * minute)
        assertEquals(
            "Set up from nothing: 2 times in the last 24 hours; the latest 5 min ago",
            ProcessStartLog.summary(starts, now)
        )
    }

    @Test
    fun summary_saysOrMore_whenTheListIsFullOfTodaysStarts() {
        // A phone that kills the keyboard all day: the file holds only the last KEEP of them.
        val starts = (1..ProcessStartLog.KEEP).map { now - it * 10 * minute }.reversed()
        val text = ProcessStartLog.summary(starts, now)!!
        assertTrue(text, text.startsWith("Set up from nothing: ${ProcessStartLog.KEEP} times or more in the last 24 hours"))
        assertTrue(text, text.contains("(5 in the last hour)"))
    }

    @Test
    fun summary_isAbsentWhenNothingHappenedInTheLastDay() {
        assertNull(ProcessStartLog.summary(emptyList(), now))
        assertNull(ProcessStartLog.summary(listOf(now - 30 * hour), now))
        assertNull("a time from the future is not a start", ProcessStartLog.summary(listOf(now + hour), now))
    }
}
