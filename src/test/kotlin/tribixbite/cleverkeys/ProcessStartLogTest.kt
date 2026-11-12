package tribixbite.cleverkeys

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import tribixbite.cleverkeys.ProcessStartLog.Event

/**
 * [ProcessStartLog]: the list of times the keyboard was set up from nothing (and let go of), and
 * the sentence the Keyboard speed card makes of it. The sentence is what the owner reads off the
 * phone to tell "the phone keeps killing the keyboard" from "the keyboard itself is slow" and
 * from "I switched keyboards", so the counting is pinned here.
 */
class ProcessStartLogTest {

    private lateinit var dir: File
    private lateinit var file: File

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_800_000_000_000L

    private fun start(at: Long) = Event(at)
    private fun stop(at: Long) = Event(at, stop = true)

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
    fun startsAndStopsAreWrittenDown_andReadBackInOrder() {
        assertTrue(ProcessStartLog.read(file).isEmpty())
        ProcessStartLog.record(file, now - hour)
        ProcessStartLog.recordStop(file, now - 30 * minute)
        ProcessStartLog.record(file, now)
        assertEquals(listOf(start(now - hour), stop(now - 30 * minute), start(now)), ProcessStartLog.read(file))
        assertEquals("a start is a bare time, a stop says so",
            "${now - hour}\n${now - 30 * minute} stop\n$now\n", file.readText())
        assertTrue("no scratch file is left behind", dir.list()!!.toList() == listOf("keyboard-starts.txt"))
    }

    @Test
    fun onlyTheLastLinesAreKept() {
        for (i in 0 until ProcessStartLog.KEEP + 15) ProcessStartLog.record(file, now + i)
        val kept = ProcessStartLog.read(file)
        assertEquals(ProcessStartLog.KEEP, kept.size)
        assertEquals("the newest survive", now + ProcessStartLog.KEEP + 14, kept.last().at)
        assertEquals(now + 15, kept.first().at)
    }

    @Test
    fun aTimeFromTheFuture_isDroppedWhenTheNextLineIsRecorded() {
        // The clock was set back since: such a time would never age out of "the last 24 hours".
        file.writeText("${now + 5 * hour}\n${now - hour}\n")
        ProcessStartLog.record(file, now)
        assertEquals(listOf(start(now - hour), start(now)), ProcessStartLog.read(file))
    }

    @Test
    fun aFileThatIsNotAListOfTimes_isReadAsWhatCanBeRead() {
        file.writeText("garbage\n\n${now - hour}\n-4\n  ${now - minute}   stop \n${now} something\n")
        assertEquals(
            listOf(start(now - hour), stop(now - minute), start(now)),
            ProcessStartLog.read(file)
        )
    }

    @Test
    fun aPlaceThatCannotBeWritten_costsNothing() {
        // A directory where the file should be: reading finds nothing, recording gives up quietly.
        val blocked = File(dir, "blocked").apply { mkdirs() }
        ProcessStartLog.record(blocked, now)
        ProcessStartLog.recordStop(blocked, now)
        assertTrue(ProcessStartLog.read(blocked).isEmpty())
    }

    @Test
    fun summary_countsTheLastDay_andSaysHowLongAgoTheLatestWas() {
        // Four starts with no shutdown between them: each after the first followed a kill.
        val events = listOf(now - 30 * hour, now - 20 * hour, now - 3 * hour, now - 12 * minute).map(::start)
        assertEquals(
            "Set up from nothing: 3 times in the last 24 hours (1 in the last hour), " +
                "3 after being killed; the latest 12 min ago",
            ProcessStartLog.summary(events, now)
        )
    }

    @Test
    fun summary_tellsAKillFromASwitchOfKeyboards() {
        // The owner switches to another keyboard and back, twice; then the phone kills this one.
        val events = listOf(
            start(now - 10 * hour), stop(now - 9 * hour),       // switched away
            start(now - 8 * hour), stop(now - 7 * hour),        // came back, switched away again
            start(now - 6 * hour),                              // came back
            start(now - 2 * hour),                              // no stop before it: killed
        )
        assertEquals(
            "Set up from nothing: 4 times in the last 24 hours, 1 after being killed; the latest 2 h 00 min ago",
            ProcessStartLog.summary(events, now)
        )
        // Only ever switched: nothing here is the phone's doing.
        assertEquals(
            "Set up from nothing: 3 times in the last 24 hours, none after being killed; the latest 6 h 00 min ago",
            ProcessStartLog.summary(events.dropLast(1), now)
        )
    }

    @Test
    fun summary_doesNotBlameTheFirstLineOfTheFile_norAKillFromBeforeTheDay() {
        // The very first start ever recorded has nothing before it.
        assertEquals(
            "Set up from nothing: once in the last 24 hours, not after being killed; the latest 3 h 05 min ago",
            ProcessStartLog.summary(listOf(start(now - 3 * hour - 5 * minute)), now)
        )
        // A start two days ago, never shut down; one start today: that one followed a kill.
        assertEquals(
            "Set up from nothing: once in the last 24 hours, after being killed; the latest under a minute ago",
            ProcessStartLog.summary(listOf(start(now - 50 * hour), start(now - 20_000L)), now)
        )
        // Two kills, both before the day began, then a clean day.
        assertEquals(
            "Set up from nothing: once in the last 24 hours, not after being killed; the latest 1 h 00 min ago",
            ProcessStartLog.summary(
                listOf(start(now - 60 * hour), start(now - 50 * hour), start(now - 40 * hour),
                    stop(now - 30 * hour), start(now - hour)),
                now
            )
        )
    }

    @Test
    fun summary_doesNotRepeatTheHourWhenEveryStartWasInIt() {
        val events = listOf(start(now - 40 * minute), stop(now - 30 * minute), start(now - 5 * minute))
        assertEquals(
            "Set up from nothing: 2 times in the last 24 hours, none after being killed; the latest 5 min ago",
            ProcessStartLog.summary(events, now)
        )
    }

    @Test
    fun summary_saysOrMore_whenTheFileIsFullOfTodaysLines() {
        // A phone that kills the keyboard all day: the file holds only the last KEEP of them.
        val events = (1..ProcessStartLog.KEEP).map { start(now - it * 10 * minute) }.reversed()
        val text = ProcessStartLog.summary(events, now)!!
        assertTrue(text, text.startsWith("Set up from nothing: ${ProcessStartLog.KEEP} times or more in the last 24 hours"))
        assertTrue(text, text.contains("(5 in the last hour)"))
        assertTrue(text, text.contains(", ${ProcessStartLog.KEEP - 1} after being killed;"))
    }

    @Test
    fun summary_isAbsentWhenNoStartFellInTheLastDay() {
        assertNull(ProcessStartLog.summary(emptyList(), now))
        assertNull(ProcessStartLog.summary(listOf(start(now - 30 * hour)), now))
        assertNull("a time from the future is not a start", ProcessStartLog.summary(listOf(start(now + hour)), now))
        assertNull("a shutdown alone is not a start", ProcessStartLog.summary(listOf(stop(now - hour)), now))
    }

    @Test
    fun theSentenceBeginsTheWayTheSmokeTestLooksForIt() {
        // scripts/ci/emulator-smoke.sh 9b greps the card for this.
        val pattern = Regex("Set up from nothing: (once|[0-9]+ times)")
        assertTrue(pattern.containsMatchIn(ProcessStartLog.summary(listOf(start(now - hour)), now)!!))
        assertTrue(pattern.containsMatchIn(ProcessStartLog.summary(listOf(start(now - 2 * hour), start(now - hour)), now)!!))
    }
}
