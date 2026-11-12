package tribixbite.cleverkeys

import java.io.File

/**
 * When the keyboard was set up from nothing, for its last few dozen starts -- and whether it had
 * been shut down properly before each of them.
 *
 * "The keyboard appears late" has two causes that need opposite answers. While the keyboard's
 * process is alive, showing it is a few milliseconds of its own work plus the system's window.
 * When the phone has killed the process -- which phones short of memory, and several makers'
 * battery savers, do to anything not on screen -- every showing begins with starting a process,
 * reading the settings, the layouts and the dictionary, and building every view: on the emulator
 * about half a second, on a slow phone several times that. The first is this project's to
 * shave; the second is decided by how often the phone does it, and nothing in the code or on the
 * emulator can say what the owner's phone does. [LatencyMeter] forgets everything when the
 * process dies, which is exactly the event in question.
 *
 * So two facts are written down, a line each, in a small private file kept to the last [KEEP]:
 * the keyboard was created ([record]), and the keyboard was let go of in the ordinary way
 * ([recordStop]: another keyboard was chosen, or this one was switched off). A start that
 * follows a stop is ordinary. A start that follows another START means the keyboard in between
 * never got to say goodbye: it was killed -- by the phone, by a crash, by an update or a
 * restart. That distinction matters to an owner who switches to another keyboard to compare:
 * those starts are his own doing, and must not be read as the phone's.
 *
 * The Keyboard speed card reads the file and says how many starts fell in the last day and how
 * many of them came after a kill. An update, a restart: once or twice a day. Dozens after a kill
 * means the phone keeps killing the keyboard, and the remedy is in the phone's battery and
 * background settings rather than in this code.
 *
 * Times only; nothing about what was typed or where. No Android class is used: the caller
 * supplies the file and the clock.
 */
object ProcessStartLog {
    /** Lines remembered. A phone that kills the keyboard all day fills this within the day. */
    const val KEEP = 120

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
    private const val STOP = "stop"
    private val lock = Any()

    /** One line of the file: the keyboard was created at [at], or ([stop]) was let go of. */
    data class Event(val at: Long, val stop: Boolean = false)

    /** The events recorded in [file], oldest first. Never throws; an unreadable file is empty. */
    fun read(file: File): List<Event> = try {
        if (file.isFile) file.readLines().mapNotNull(::parse) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun parse(line: String): Event? {
        val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
        val at = parts.firstOrNull()?.toLongOrNull() ?: return null
        if (at <= 0L) return null
        return Event(at, stop = parts.getOrNull(1) == STOP)
    }

    /**
     * The keyboard was created at [nowMs]. Never throws: a start that cannot be written (storage
     * still locked after a reboot) is simply not counted. Call off the main thread.
     */
    fun record(file: File, nowMs: Long) = append(file, Event(nowMs))

    /**
     * The keyboard was let go of in the ordinary way at [nowMs] (`onDestroy`). A few hundred
     * bytes, written at once: the process may not live long enough for a thread to do it.
     */
    fun recordStop(file: File, nowMs: Long) = append(file, Event(nowMs, stop = true))

    private fun append(file: File, event: Event) {
        try {
            synchronized(lock) {
                // A time later than now was written before the clock was set back; it would
                // never age out of "the last 24 hours", so it goes.
                val all = (read(file).filter { it.at <= event.at } + event).takeLast(KEEP)
                val text = all.joinToString(separator = "\n", postfix = "\n") {
                    if (it.stop) "${it.at} $STOP" else it.at.toString()
                }
                file.parentFile?.mkdirs()
                val scratch = File(file.parentFile, file.name + ".new")
                scratch.writeText(text)
                if (!scratch.renameTo(file)) {
                    file.writeText(text)
                    scratch.delete()
                }
            }
        } catch (_: Exception) {
            // Diagnostics must never cost the keyboard anything.
        }
    }

    /**
     * The card's sentence for [events] as of [nowMs], or null when no start fell in the last day
     * (nothing recorded yet, or the file could not be read).
     *
     * "After being killed" counts the starts that directly follow another start: the keyboard
     * between the two never recorded its shutdown. The first line of the file has nothing before
     * it and is not counted as a kill.
     */
    fun summary(events: List<Event>, nowMs: Long): String? {
        val past = events.filter { it.at in 1..nowMs }
        val starts = past.filter { !it.stop }
        val day = starts.count { it.at > nowMs - DAY_MS }
        if (day == 0) return null
        val hour = starts.count { it.at > nowMs - HOUR_MS }
        var killed = 0
        for (i in 1 until past.size) {
            val e = past[i]
            if (!e.stop && !past[i - 1].stop && e.at > nowMs - DAY_MS) killed++
        }
        // The file holds KEEP lines at most: when even the oldest of a full file is inside the
        // day, there were at least this many starts and possibly more.
        val capped = past.size >= KEEP && past.first().at > nowMs - DAY_MS
        val count = if (day == 1) "once" else if (capped) "$day times or more" else "$day times"
        val lastHour = if (hour in 1 until day) " ($hour in the last hour)" else ""
        val kills = when {
            killed == 0 && day == 1 -> ", not after being killed"
            killed == 0 -> ", none after being killed"
            day == 1 -> ", after being killed"
            else -> ", $killed after being killed"
        }
        return "Set up from nothing: $count in the last 24 hours$lastHour$kills; " +
            "the latest ${ago(nowMs - starts.maxOf { it.at })} ago"
    }

    /** A duration in the two largest units that matter to a reader: `under a minute`, `12 min`, `3 h 05 min`. */
    internal fun ago(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 1L -> "under a minute"
            minutes < 60L -> "$minutes min"
            else -> "${minutes / 60L} h ${(minutes % 60L).toString().padStart(2, '0')} min"
        }
    }
}
