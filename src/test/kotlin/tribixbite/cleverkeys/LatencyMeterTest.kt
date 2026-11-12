package tribixbite.cleverkeys

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LatencyMeter] is the instrument every latency claim in this project now rests on, so its own
 * arithmetic is pinned here: a wrong average or a line the smoke test cannot parse would make
 * every later comparison quietly meaningless. Pure JVM -- the meter uses no Android class.
 */
class LatencyMeterTest {

    private var now = 1_000_000_000L
    private fun advanceMs(ms: Long) { now += ms * 1_000_000L }

    @Before
    fun setUp() {
        LatencyMeter.resetForTest()
        now = 1_000_000_000L
        LatencyMeter.clock = { now }
    }

    @After
    fun tearDown() = LatencyMeter.resetForTest()

    private fun field(line: String, name: String): String =
        Regex("(?:^| )$name=(\\S+)").find(line)?.groupValues?.get(1)
            ?: throw AssertionError("no '$name=' in: $line")

    @Test
    fun stat_keepsCountAverageWorstAndSlow() {
        val s = LatencyMeter.Stat()
        s.add(2_000_000L)
        s.add(4_000_000L)
        s.add(30_000_000L)
        s.add(-5L) // a clock that ran backwards is ignored, not averaged in
        assertEquals(3, s.count)
        assertEquals(12.0, s.avgMs, 1e-9)
        assertEquals(30.0, s.maxMs, 1e-9)
        assertEquals("only the 30 ms sample is over a 60 Hz frame", 1, s.slow)
        assertEquals("12.00/30.00", s.pair())
    }

    @Test
    fun emptyStat_reportsZeroRatherThanDividingByZero() {
        val s = LatencyMeter.Stat()
        assertEquals(0.0, s.avgMs, 0.0)
        assertEquals("0.00/0.00", s.pair())
    }

    @Test
    fun coldLine_carriesStartUpOnceAndNamesTheSlowestStepsFirst() {
        LatencyMeter.createBegin(310)
        advanceMs(5); LatencyMeter.mark("compose")
        advanceMs(120); LatencyMeter.mark("predict")
        advanceMs(40); LatencyMeter.mark("inflate")
        advanceMs(1)
        LatencyMeter.createEnd()
        LatencyMeter.mark("after-the-end") // ignored: onCreate is over

        LatencyMeter.showBegin()
        advanceMs(4); LatencyMeter.showMark("clip")
        advanceMs(16); LatencyMeter.showMark("receiver")
        advanceMs(2); LatencyMeter.showMark("install")
        LatencyMeter.showMark("nothing") // too quick to be worth a place in the line
        LatencyMeter.showStarted()
        advanceMs(30)
        LatencyMeter.drawn(3_000_000L)

        val first = LatencyMeter.sessionEnd()
        assertEquals("start-up line, then the showing's line", 2, first.size)
        val cold = first[0]
        assertTrue(cold, cold.startsWith("cold "))
        assertEquals("310", field(cold, "pre"))
        assertEquals("166", field(cold, "create"))
        assertEquals("22", field(cold, "start"))
        assertEquals("52", field(cold, "shown"))
        assertEquals("predict:120,inflate:40,compose:5", field(cold, "steps"))
        assertEquals("receiver:16.0,clip:4.0,install:2.0", field(cold, "first"))
        assertEquals("the showing's own line names its steps too",
            "receiver:16.0,clip:4.0,install:2.0", field(first[1], "steps"))

        // The process started once; a second showing must not report start-up again, and its
        // steps are its own, not the first showing's.
        LatencyMeter.showBegin()
        advanceMs(3); LatencyMeter.showMark("clip")
        LatencyMeter.showStarted()
        val second = LatencyMeter.sessionEnd()
        assertEquals(1, second.size)
        assertTrue(second[0], second[0].startsWith("session "))
        assertEquals("clip:3.0", field(second[0], "steps"))
    }

    @Test
    fun sessionLine_reportsWhatHappenedBetweenShowAndHide() {
        LatencyMeter.createBegin(-1)
        LatencyMeter.createEnd()
        LatencyMeter.sessionEnd() // nothing open: nothing to report
        assertTrue(LatencyMeter.sessionEnd().isEmpty())

        LatencyMeter.showBegin()
        LatencyMeter.attached()
        LatencyMeter.installed()
        advanceMs(10)
        LatencyMeter.showStarted()
        LatencyMeter.measured(); LatencyMeter.measured()
        advanceMs(15)
        LatencyMeter.drawn(4_000_000L)
        // two taps: down + up each, and one of the ups was slow
        LatencyMeter.touched(1_000_000L, press = true, move = false)
        LatencyMeter.touched(2_000_000L, press = false, move = false)
        LatencyMeter.touched(1_000_000L, press = true, move = false)
        LatencyMeter.touched(20_000_000L, press = false, move = false)
        LatencyMeter.touched(500_000L, press = false, move = true)
        LatencyMeter.drawn(2_000_000L)
        // total, wait, input, layout, draw, sync, gpu; the first is the frame that showed the window
        LatencyMeter.frame(10_000_000L, 1_000_000L, 0L, 1_000_000L, 1_000_000L, 2_000_000L, 4_000_000L,
            windowShown = true)
        LatencyMeter.frame(40_000_000L, 2_000_000L, 1_000_000L, 1_000_000L, 1_000_000L, 5_000_000L, 25_000_000L)
        // the keyboard's own tree: two measure passes and one layout pass
        LatencyMeter.treeMeasured(600_000L); LatencyMeter.treeMeasured(200_000L)
        LatencyMeter.treeLaidOut(300_000L)
        LatencyMeter.stripUpdated(1_500_000L)
        // the editor was re-read twice after the caret moved
        LatencyMeter.synced(3_000_000L); LatencyMeter.synced(9_000_000L)
        LatencyMeter.resized()

        val line = LatencyMeter.sessionEnd().last()
        assertEquals("1", field(line, "shows"))
        assertEquals("10.00/10.00", field(line, "start"))
        assertEquals("25.00/25.00", field(line, "shown"))
        assertEquals("2", field(line, "presses"))
        assertEquals("6.00/20.00", field(line, "touch"))
        assertEquals("1:0.50/0.50", field(line, "move"))
        assertEquals("2:3.00/4.00", field(line, "draw"))
        assertEquals("2", field(line, "frames"))
        assertEquals("25.00/40.00", field(line, "frame"))
        assertEquals("4.00/5.00", field(line, "ui"))
        assertEquals("18.00/30.00", field(line, "gpu"))
        assertEquals("1", field(line, "slow"))
        assertEquals("the average frame's stages", "1.5:0.5:1.0:1.0:3.5:14.5", field(line, "avg"))
        assertEquals("the slowest frame, stage by stage", "40:2:1:1:1:5:25", field(line, "worst"))
        assertEquals("the frame that showed the keyboard", "10:1:0:1:1:2:4", field(line, "appear"))
        assertEquals("2:0.40/0.60", field(line, "tm"))
        assertEquals("1:0.30/0.30", field(line, "tl"))
        assertEquals("none", field(line, "steps"))
        assertEquals("1:1.50/1.50", field(line, "strip"))
        assertEquals("2:6.00/9.00", field(line, "sync"))
        assertEquals("1", field(line, "attach"))
        assertEquals("2", field(line, "measure"))
        assertEquals("1", field(line, "install"))
        assertEquals("1", field(line, "resize"))
    }

    @Test
    fun onlyTheFirstWindowFrameOfAShowing_isTheOneThatShowedIt() {
        LatencyMeter.createBegin(0)
        LatencyMeter.createEnd()
        // A frame reported while nothing is showing (the tail of the last showing arrives late,
        // on its own thread) is not an appearance.
        LatencyMeter.frame(90_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, windowShown = true)
        LatencyMeter.showBegin()
        LatencyMeter.showStarted()
        LatencyMeter.frame(5_000_000L, 0L, 0L, 1_000_000L, 0L, 0L, 0L)                      // not flagged
        LatencyMeter.frame(30_000_000L, 4_000_000L, 0L, 20_000_000L, 1_000_000L, 0L, 3_000_000L, windowShown = true)
        LatencyMeter.frame(70_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, windowShown = true)        // a later one
        val line = LatencyMeter.sessionEnd().last()
        assertEquals("30:4:0:20:1:0:3", field(line, "appear"))

        // A showing with no such frame says so rather than repeating the last one.
        LatencyMeter.showBegin()
        LatencyMeter.showStarted()
        assertEquals("none", field(LatencyMeter.sessionEnd().last(), "appear"))
    }

    @Test
    fun aFieldChangeUnderAnOpenKeyboard_isAnotherShowingOfTheSameSession() {
        LatencyMeter.createBegin(0)
        LatencyMeter.createEnd()
        LatencyMeter.showBegin()
        advanceMs(8); LatencyMeter.showStarted()
        advanceMs(2); LatencyMeter.drawn(1_000_000L)
        LatencyMeter.showBegin() // the platform restarts input without hiding the keyboard
        advanceMs(4); LatencyMeter.showStarted()
        advanceMs(2); LatencyMeter.drawn(1_000_000L)
        val line = LatencyMeter.sessionEnd().last()
        assertEquals("2", field(line, "shows"))
        assertEquals("6.00/8.00", field(line, "start"))
        assertEquals("8.00/10.00", field(line, "shown"))
    }

    @Test
    fun aHeightChangeWhileHidden_isNotCounted() {
        LatencyMeter.createBegin(0)
        LatencyMeter.createEnd()
        LatencyMeter.resized() // the keyboard is not showing: nobody is resized
        LatencyMeter.showBegin()
        LatencyMeter.showStarted()
        assertEquals("0", field(LatencyMeter.sessionEnd().last(), "resize"))
    }

    @Test
    fun onlyTheFirstDrawAfterAShowingEndsIt() {
        LatencyMeter.createBegin(0)
        LatencyMeter.createEnd()
        LatencyMeter.showBegin()
        advanceMs(5); LatencyMeter.showStarted()
        advanceMs(5); LatencyMeter.drawn(1_000_000L)
        advanceMs(500); LatencyMeter.drawn(1_000_000L) // a key press much later
        val line = LatencyMeter.sessionEnd().last()
        assertEquals("10.00/10.00", field(line, "shown"))
        assertEquals("2:1.00/1.00", field(line, "draw"))
    }

    @Test
    fun buildLabel_namesTheVersionTheCommitAndWhetherTheBuildIsDebuggable() {
        assertEquals("1.2.3, abc1234, not debuggable", LatencyMeter.buildLabel("1.2.3", "abc1234", false))
        assertEquals("1.2.3, debuggable", LatencyMeter.buildLabel("1.2.3", null, true))
        assertEquals("1.2.3, not debuggable", LatencyMeter.buildLabel("1.2.3", " ", false))
        assertEquals("?, not debuggable", LatencyMeter.buildLabel(null, null, false))
    }

    @Test
    fun report_saysSoWhenNothingHasBeenMeasuredYet() {
        val lines = LatencyMeter.report("1.2.3")
        assertEquals("Build: 1.2.3", lines[0])
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].contains("has not been opened"))
    }

    @Test
    fun report_coversFinishedShowingsAndTheOneStillOpen() {
        LatencyMeter.createBegin(300)
        advanceMs(90); LatencyMeter.mark("predict")
        LatencyMeter.createEnd()

        LatencyMeter.showBegin()
        advanceMs(10); LatencyMeter.showStarted()
        advanceMs(10); LatencyMeter.drawn(1_000_000L)
        LatencyMeter.touched(2_000_000L, press = true, move = false)
        LatencyMeter.frame(8_000_000L, 1_000_000L, 0L, 0L, 1_000_000L, 1_000_000L, 5_000_000L,
            windowShown = true)
        LatencyMeter.treeMeasured(1_000_000L)
        LatencyMeter.synced(2_000_000L)
        LatencyMeter.sessionEnd()

        // Still open: the settings screen is usually read with the keyboard up.
        LatencyMeter.showBegin()
        advanceMs(20); LatencyMeter.showStarted()
        advanceMs(20); LatencyMeter.drawn(1_000_000L)
        LatencyMeter.touched(4_000_000L, press = true, move = false)
        LatencyMeter.frame(30_000_000L, 12_000_000L, 0L, 3_000_000L, 2_000_000L, 4_000_000L, 9_000_000L,
            windowShown = true)
        LatencyMeter.treeMeasured(3_000_000L)
        LatencyMeter.treeLaidOut(2_000_000L)
        LatencyMeter.synced(10_000_000L)
        LatencyMeter.resized()

        val text = LatencyMeter.report("9.9").joinToString("\n")
        assertTrue(text, text.contains("300 ms before set-up, set-up 90 ms"))
        assertTrue(text, text.contains("first picture 20 ms"))
        assertTrue(text, text.contains("Slowest set-up steps: predict 90 ms"))
        assertTrue(text, text.contains("Opening: 2 times, 30.00 ms on average, 40.00 ms at worst"))
        assertTrue(text, text.contains("Key presses: 2, handled in 3.00 ms on average, 4.00 ms at worst"))
        assertTrue(text, text.contains("Frames: 2, 19.00 ms on average, 30.00 ms at worst, 1 slower"))
        assertTrue(text, text.contains(
            "Slowest frame: 30 ms (waiting 12, input 0, layout 3, drawing 2, sync 4, graphics 9)"))
        assertTrue("of two showings, the slower appearance is the one reported", text.contains(
            "Slowest frame that showed the keyboard: 30 ms (waiting 12, input 0, layout 3, " +
                "drawing 2, sync 4, graphics 9)"))
        assertTrue(text, text.contains(
            "The keyboard's own layout: measured 2 times, 2.00 ms on average, 3.00 ms at worst; " +
                "laid out 1 times, 2.00 ms on average, 2.00 ms at worst"))
        assertTrue(text, text.contains(
            "Reading the app's text after the caret moved: 2 times, 6.00 ms on average, 10.00 ms at worst"))
        assertTrue(text, text.contains("height changed 1 times"))
        assertFalse(text, text.contains("No key has been pressed"))
    }

    @Test
    fun report_leavesTheEditorReadsOutWhenThereWereNone() {
        LatencyMeter.createBegin(-1); LatencyMeter.createEnd()
        LatencyMeter.showBegin(); LatencyMeter.showStarted(); LatencyMeter.drawn(1_000_000L)
        val text = LatencyMeter.report("9.9").joinToString("\n")
        assertFalse(text, text.contains("Reading the app's text"))
    }
}
