package tribixbite.cleverkeys

import java.util.Locale

/**
 * Where the keyboard's time goes, kept by the keyboard itself.
 *
 * "It is slow to appear and it lags while I type" was reported three times (2026-11-01, 11-10,
 * 11-12) and answered twice from reading the code alone, because nothing in this project could
 * say how long anything took: the sandbox that writes the code has no device, the emulator smoke
 * test asserted behaviour and no duration, and the owner's phone has no adb attached to it. This
 * object is the missing instrument. It records, with a handful of clock reads on paths that
 * already run:
 *
 *  - **start-up**, once per process: the time before `onCreate`, `onCreate` itself broken down by
 *    step, and the first showing;
 *  - **each showing**: `onStartInputView` and its steps, and from its entry to the end of the
 *    keyboard's first draw;
 *  - **typing**: how long each touch took to handle, how long the keys took to draw, and the
 *    platform's own per-frame timing for the keyboard window (`FrameMetrics`), stage by stage;
 *  - **the keyboard's own layout work**: every measure and layout pass of its outermost view,
 *    which is its share of a frame's layout stage (the platform's figure for that stage also
 *    holds the window manager's work);
 *  - **reading the app's text**: each re-read of the editor that follows a caret move -- two
 *    round trips to the app on the keyboard's main thread, so on a phone this is how long the
 *    app took to answer; the emulator's test field lives in the keyboard's own process and
 *    answers at once, which makes the COUNT the only part of this the emulator can show;
 *  - **suggestions**: from a typed letter being handed to the predictor to its suggestions being
 *    ready for the strip -- the dictionary search on its worker thread, and any wait for the
 *    previous letter's search to let go of that thread;
 *  - **work that should not repeat**: how often the key view was re-attached, re-measured, the
 *    input view re-installed, and the keyboard's height changed while it was open.
 *
 * Two readers. The emulator smoke test reads the `CKLatency` log lines ([sessionEnd]) and puts
 * them in a check-run annotation, so a change is compared on numbers. The owner reads the same
 * numbers on the settings screen ([report]) and can copy them out, so the next report from the
 * phone is a measurement rather than a feeling.
 *
 * No Android class is used here: the clock is `System.nanoTime` (monotonic, and replaceable in
 * tests), the service passes in the one value only Android knows (process start), and writing to
 * logcat is the caller's job. Nothing recorded is text the user typed -- only durations and
 * counts -- so the lines are safe to log on the installed build.
 *
 * Cost: one lock and a few additions per touch event, per draw and per frame. The lock exists
 * because frame timing arrives on its own thread; it is never held across anything but arithmetic.
 */
object LatencyMeter {
    const val TAG = "CKLatency"

    /** A frame, or a touch, that takes longer than this has cost the user a 60 Hz frame. */
    const val SLOW_NANOS = 16_666_667L

    /** Monotonic nanosecond clock. Tests replace it. */
    @Volatile
    internal var clock: () -> Long = { System.nanoTime() }

    /** One stream of durations: how many, their sum, the worst, and how many were slow. */
    class Stat(private val slowNanos: Long = SLOW_NANOS) {
        var count = 0
            private set
        var totalNanos = 0L
            private set
        var maxNanos = 0L
            private set
        var slow = 0
            private set

        fun add(nanos: Long) {
            if (nanos < 0) return
            count++
            totalNanos += nanos
            if (nanos > maxNanos) maxNanos = nanos
            if (nanos > slowNanos) slow++
        }

        fun add(other: Stat) {
            count += other.count
            totalNanos += other.totalNanos
            if (other.maxNanos > maxNanos) maxNanos = other.maxNanos
            slow += other.slow
        }

        fun clear() {
            count = 0
            totalNanos = 0L
            maxNanos = 0L
            slow = 0
        }

        val avgMs: Double get() = if (count == 0) 0.0 else totalNanos / 1e6 / count
        val maxMs: Double get() = maxNanos / 1e6

        /** `avg/max` in milliseconds, the form the log lines use. */
        fun pair(): String = "${ms(avgMs)}/${ms(maxMs)}"
    }

    /** Everything counted between a showing of the keyboard and its hiding. */
    private class Window {
        var shows = 0
        val start = Stat()   // onStartInputView, entry to exit
        val shown = Stat()   // onStartInputView entry to the end of the first key draw
        var presses = 0
        val touch = Stat()   // down / up / cancel handling
        val move = Stat()    // move handling (swipe typing lives here)
        val draw = Stat()    // Keyboard2View.onDraw
        val frame = Stat()   // FrameMetrics TOTAL_DURATION
        val ui = Stat()      // the UI-thread share of a frame
        val gpu = Stat()     // the render-thread share: sync, issuing commands, swapping buffers
        val stages = LongArray(6)    // summed over all frames: wait, input, layout, draw, sync, gpu
        var worst: LongArray? = null // the slowest frame, by stage (see [frame])
        var appear: LongArray? = null // the frame in which the window became visible, by stage
        val treeMeasure = Stat() // measure passes of the keyboard's outermost view
        val treeLayout = Stat()  // layout passes of the same
        val strip = Stat()   // suggestion strip rebinds
        val sync = Stat()    // re-reads of the editor after a caret move, on the main thread
        val predict = Stat() // a typed letter's wait for its suggestions (worker thread)
        var attaches = 0
        var measures = 0
        var installs = 0
        var resizes = 0

        fun add(o: Window) {
            shows += o.shows
            start.add(o.start); shown.add(o.shown)
            presses += o.presses
            touch.add(o.touch); move.add(o.move); draw.add(o.draw)
            frame.add(o.frame); ui.add(o.ui); gpu.add(o.gpu); strip.add(o.strip)
            for (i in stages.indices) stages[i] += o.stages[i]
            val theirs = o.worst
            if (theirs != null && theirs[0] > (worst?.get(0) ?: -1L)) worst = theirs.copyOf()
            // Over several showings, keep the slowest appearance: that is the one to explain.
            val theirAppear = o.appear
            if (theirAppear != null && theirAppear[0] > (appear?.get(0) ?: -1L)) appear = theirAppear.copyOf()
            treeMeasure.add(o.treeMeasure); treeLayout.add(o.treeLayout)
            sync.add(o.sync); predict.add(o.predict)
            attaches += o.attaches; measures += o.measures
            installs += o.installs; resizes += o.resizes
        }

        fun clear() {
            shows = 0; presses = 0; attaches = 0; measures = 0; installs = 0; resizes = 0
            start.clear(); shown.clear(); touch.clear(); move.clear(); draw.clear()
            frame.clear(); ui.clear(); gpu.clear(); strip.clear()
            stages.fill(0L)
            worst = null; appear = null
            treeMeasure.clear(); treeLayout.clear()
            sync.clear(); predict.clear()
        }

        /** One frame as `total:wait:input:layout:draw:sync:gpu`, whole milliseconds. */
        fun stagesOf(frame: LongArray?): String =
            frame?.joinToString(":") { (it / 1_000_000L).toString() } ?: "none"

        /** The average frame's stages, `wait:input:layout:draw:sync:gpu`, one decimal. */
        fun averageStages(): String =
            if (frame.count == 0) "none" else stages.joinToString(":") { ms1(it / frame.count) }

        fun line(): String =
            "shows=$shows start=${start.pair()} shown=${shown.pair()} presses=$presses " +
                "touch=${touch.pair()} move=${move.count}:${move.pair()} " +
                "draw=${draw.count}:${draw.pair()} frames=${frame.count} frame=${frame.pair()} " +
                "ui=${ui.pair()} gpu=${gpu.pair()} slow=${frame.slow} avg=${averageStages()} " +
                "worst=${stagesOf(worst)} appear=${stagesOf(appear)} " +
                "tm=${treeMeasure.count}:${treeMeasure.pair()} " +
                "tl=${treeLayout.count}:${treeLayout.pair()} " +
                "strip=${strip.count}:${strip.pair()} sync=${sync.count}:${sync.pair()} " +
                "predict=${predict.count}:${predict.pair()} " +
                "attach=$attaches measure=$measures install=$installs resize=$resizes"
    }

    private val lock = Any()

    // ── start-up, once per process ───────────────────────────────────────────────────────────────
    private var preCreateMs = -1L
    private var creating = false
    private var createBeginNanos = 0L
    private var lastMarkNanos = 0L
    private val steps = ArrayList<Pair<String, Long>>()
    private var createMs = -1L
    private var coldStartMs = -1L
    private var coldShownMs = -1L
    private var coldLogged = false

    // ── the current showing, and everything since the process started ───────────────────────────
    private val session = Window()
    private val total = Window()
    private var sessionOpen = false
    private var showBeginNanos = 0L
    private var awaitingDraw = false

    // The steps of the showing in progress (or the last one), and of the process's first.
    private var lastShowMarkNanos = 0L
    private val showSteps = ArrayList<Pair<String, Long>>()
    private var firstShowSteps: String? = null

    /**
     * `onCreate` has begun. [msSinceProcessStart] is how long the process had already been alive
     * (class loading, providers), or a negative value when the platform cannot say.
     */
    fun createBegin(msSinceProcessStart: Long) = synchronized(lock) {
        preCreateMs = msSinceProcessStart
        creating = true
        createBeginNanos = clock()
        lastMarkNanos = createBeginNanos
        steps.clear()
        createMs = -1L
    }

    /** The step of `onCreate` named [step] has just finished. */
    fun mark(step: String) = synchronized(lock) {
        if (!creating) return@synchronized
        val now = clock()
        steps.add(step to (now - lastMarkNanos) / 1_000_000L)
        lastMarkNanos = now
    }

    /** `onCreate` has returned. */
    fun createEnd() = synchronized(lock) {
        if (!creating) return@synchronized
        creating = false
        createMs = (clock() - createBeginNanos) / 1_000_000L
    }

    /** `onStartInputView` has been entered: a showing begins (or the field changed under one). */
    fun showBegin() = synchronized(lock) {
        sessionOpen = true
        session.shows++
        showBeginNanos = clock()
        lastShowMarkNanos = showBeginNanos
        showSteps.clear()
        awaitingDraw = true
    }

    /** The step of `onStartInputView` named [step] has just finished. */
    fun showMark(step: String) = synchronized(lock) {
        if (!sessionOpen) return@synchronized
        val now = clock()
        showSteps.add(step to (now - lastShowMarkNanos))
        lastShowMarkNanos = now
    }

    /** `onStartInputView` has returned. */
    fun showStarted() = synchronized(lock) {
        if (!sessionOpen) return@synchronized
        val took = clock() - showBeginNanos
        session.start.add(took)
        if (coldStartMs < 0L) {
            coldStartMs = took / 1_000_000L
            firstShowSteps = stepList(showSteps)
        }
    }

    /** `name:ms` for the slowest steps first; steps under half a millisecond are left out. */
    private fun stepList(steps: List<Pair<String, Long>>): String =
        steps.filter { it.second >= 500_000L }.sortedByDescending { it.second }.take(8)
            .joinToString(",") { "${it.first}:${ms1(it.second)}" }
            .ifEmpty { "none" }

    /** The key view finished a draw that took [nanos]. The first one after a showing ends it. */
    fun drawn(nanos: Long) = synchronized(lock) {
        session.draw.add(nanos)
        if (awaitingDraw) {
            awaitingDraw = false
            val took = clock() - showBeginNanos
            session.shown.add(took)
            if (coldShownMs < 0L) coldShownMs = took / 1_000_000L
        }
    }

    /** A touch event was handled in [nanos]. [press] marks a finger going down; [move] a move. */
    fun touched(nanos: Long, press: Boolean, move: Boolean) = synchronized(lock) {
        if (press) session.presses++
        if (move) session.move.add(nanos) else session.touch.add(nanos)
    }

    /**
     * The platform timed one frame of the keyboard window, stage by stage: [waitNanos] is how
     * long the frame waited for the UI thread to get to it, [inputNanos]..[drawNanos] are the UI
     * thread's own work, and [syncNanos] and [gpuNanos] belong to the render thread.
     *
     * [windowShown] is the platform's flag for the frame in which the window became visible
     * (`FrameMetrics.FIRST_DRAW_FRAME`). The first such frame of a showing is the one the user
     * is waiting for after tapping a text field, so its stages are kept separately. Its layout
     * stage includes the window manager preparing the window, which is not the keyboard's code;
     * [treeMeasured] and [treeLaidOut] say how much of it is.
     */
    fun frame(
        totalNanos: Long,
        waitNanos: Long,
        inputNanos: Long,
        layoutNanos: Long,
        drawNanos: Long,
        syncNanos: Long,
        gpuNanos: Long,
        windowShown: Boolean = false
    ) = synchronized(lock) {
        session.frame.add(totalNanos)
        session.ui.add(waitNanos + inputNanos + layoutNanos + drawNanos)
        session.gpu.add(syncNanos + gpuNanos)
        val stages = longArrayOf(waitNanos, inputNanos, layoutNanos, drawNanos, syncNanos, gpuNanos)
        for (i in stages.indices) if (stages[i] > 0L) session.stages[i] += stages[i]
        if (totalNanos > (session.worst?.get(0) ?: -1L)) {
            session.worst = longArrayOf(totalNanos) + stages
        }
        if (windowShown && sessionOpen && session.appear == null) {
            session.appear = longArrayOf(totalNanos) + stages
        }
    }

    /** The keyboard's outermost view finished a measure pass of [nanos], children included. */
    fun treeMeasured(nanos: Long) = synchronized(lock) { session.treeMeasure.add(nanos) }

    /** The keyboard's outermost view finished a layout pass of [nanos], children included. */
    fun treeLaidOut(nanos: Long) = synchronized(lock) { session.treeLayout.add(nanos) }

    /** The suggestion strip rebound its views in [nanos]. */
    fun stripUpdated(nanos: Long) = synchronized(lock) { session.strip.add(nanos) }

    /**
     * The editor was re-read after a caret move, which held the main thread for [nanos]: the
     * time the app took to answer, plus handing the word to the predictor.
     */
    fun synced(nanos: Long) = synchronized(lock) { session.sync.add(nanos) }

    /**
     * A typed letter's suggestions were ready [nanos] after they were asked for. Called from the
     * prediction worker thread, for searches that ran to the end (one that a newer letter
     * overtook is not counted: nobody waited for it).
     */
    fun predicted(nanos: Long) = synchronized(lock) { session.predict.add(nanos) }

    /** The key view was attached to a window. Once per view is the healthy number. */
    fun attached() = synchronized(lock) { session.attaches++ }

    /** The key view was measured. */
    fun measured() = synchronized(lock) { session.measures++ }

    /** The input view was handed to the platform again (a detach and attach of every view). */
    fun installed() = synchronized(lock) { session.installs++ }

    /** The keyboard changed height while it was open: the app behind it was resized too. */
    fun resized() = synchronized(lock) { if (sessionOpen) session.resizes++ }

    /**
     * The showing is over (`onFinishInputView`). Returns the lines to log -- the start-up line
     * once per process, then this showing's line -- and folds the showing into the totals.
     */
    fun sessionEnd(): List<String> = synchronized(lock) {
        if (!sessionOpen) return@synchronized emptyList()
        val out = ArrayList<String>(2)
        if (!coldLogged && createMs >= 0L) {
            coldLogged = true
            out.add(coldLine())
        }
        out.add("session ${session.line()} steps=${stepList(showSteps)}")
        total.add(session)
        session.clear()
        sessionOpen = false
        awaitingDraw = false
        out
    }

    private fun coldLine(): String {
        val slowest = steps.sortedByDescending { it.second }.take(8)
            .joinToString(",") { "${it.first}:${it.second}" }
        return "cold pre=$preCreateMs create=$createMs start=$coldStartMs shown=$coldShownMs " +
            "steps=$slowest first=${firstShowSteps ?: "none"}"
    }

    /**
     * What the settings screen shows: everything since the keyboard's process started, including
     * a showing that is still open. Plain sentences with the numbers in them, because the reader
     * is the owner and the next reader is whoever they paste it to.
     */
    fun report(build: String): List<String> = synchronized(lock) {
        val all = Window().also { it.add(total); it.add(session) }
        val out = ArrayList<String>()
        out.add("Build: $build")
        if (createMs < 0L) {
            out.add("The keyboard has not been opened since it was installed or restarted.")
            return@synchronized out
        }
        val pre = if (preCreateMs >= 0L) "$preCreateMs ms before set-up, " else ""
        out.add("Starting: ${pre}set-up $createMs ms" +
            (if (coldShownMs >= 0L) ", first picture $coldShownMs ms after being asked" else ""))
        if (steps.isNotEmpty()) {
            out.add("Slowest set-up steps: " + steps.sortedByDescending { it.second }.take(4)
                .joinToString(", ") { "${it.first} ${it.second} ms" })
        }
        if (all.shown.count > 0) {
            out.add("Opening: ${all.shown.count} times, ${ms(all.shown.avgMs)} ms on average, " +
                "${ms(all.shown.maxMs)} ms at worst")
        }
        if (all.presses > 0) {
            out.add("Key presses: ${all.presses}, handled in ${ms(all.touch.avgMs)} ms on " +
                "average, ${ms(all.touch.maxMs)} ms at worst")
        } else {
            out.add("No key has been pressed yet.")
        }
        if (all.frame.count > 0) {
            out.add("Frames: ${all.frame.count}, ${ms(all.frame.avgMs)} ms on average, " +
                "${ms(all.frame.maxMs)} ms at worst, ${all.frame.slow} slower than 16.7 ms")
            fun stages(w: LongArray): String {
                fun part(i: Int) = w[i] / 1_000_000L
                return "${part(0)} ms (waiting ${part(1)}, input ${part(2)}, layout ${part(3)}, " +
                    "drawing ${part(4)}, sync ${part(5)}, graphics ${part(6)})"
            }
            all.appear?.let { out.add("Slowest frame that showed the keyboard: ${stages(it)}") }
            all.worst?.let { out.add("Slowest frame: ${stages(it)}") }
        }
        if (all.treeMeasure.count + all.treeLayout.count > 0) {
            out.add("The keyboard's own layout: measured ${all.treeMeasure.count} times, " +
                "${ms(all.treeMeasure.avgMs)} ms on average, ${ms(all.treeMeasure.maxMs)} ms at " +
                "worst; laid out ${all.treeLayout.count} times, ${ms(all.treeLayout.avgMs)} ms " +
                "on average, ${ms(all.treeLayout.maxMs)} ms at worst")
        }
        if (all.sync.count > 0) {
            out.add("Reading the app's text after the caret moved: ${all.sync.count} times, " +
                "${ms(all.sync.avgMs)} ms on average, ${ms(all.sync.maxMs)} ms at worst")
        }
        if (all.predict.count > 0) {
            out.add("From a letter to its suggestions: ${all.predict.count} times, " +
                "${ms(all.predict.avgMs)} ms on average, ${ms(all.predict.maxMs)} ms at worst")
        }
        out.add("While open: height changed ${all.resizes} times, keys re-attached " +
            "${all.attaches} times, view re-installed ${all.installs} times")
        out
    }

    /**
     * The build line of [report]: version, commit when the build recorded one, and whether the
     * build is debuggable -- the one build property that changes how fast the same code runs,
     * and so the first thing to check when two reports disagree.
     */
    fun buildLabel(version: String?, commit: String?, debuggable: Boolean): String =
        listOfNotNull(
            version?.takeIf { it.isNotBlank() } ?: "?",
            commit?.takeIf { it.isNotBlank() },
            if (debuggable) "debuggable" else "not debuggable"
        ).joinToString(", ")

    /** Tests only: forget everything, as a new process would. */
    internal fun resetForTest() = synchronized(lock) {
        preCreateMs = -1L; creating = false; createBeginNanos = 0L; lastMarkNanos = 0L
        steps.clear()
        createMs = -1L; coldStartMs = -1L; coldShownMs = -1L; coldLogged = false
        session.clear(); total.clear(); sessionOpen = false; showBeginNanos = 0L
        awaitingDraw = false
        lastShowMarkNanos = 0L; showSteps.clear(); firstShowSteps = null
        clock = { System.nanoTime() }
    }

    private fun ms(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    /** Nanoseconds as milliseconds with one decimal. */
    private fun ms1(nanos: Long): String = String.format(Locale.ROOT, "%.1f", nanos / 1e6)
}
