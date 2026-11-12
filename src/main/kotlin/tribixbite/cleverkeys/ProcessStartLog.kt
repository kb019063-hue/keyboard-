package tribixbite.cleverkeys

import java.io.File

/**
 * When the keyboard was set up from nothing, for its last few dozen starts.
 *
 * "The keyboard appears late" has two causes that need opposite answers. While the keyboard's
 * process is alive, showing it is a few milliseconds of its own work plus the system's window.
 * When the phone has killed the process -- which phones short of memory, and several makers'
 * battery savers, do to anything not on screen -- every showing begins with starting a process,
 * reading the settings, the layouts and the dictionary, and building every view: on the emulator
 * about half a second, on a slow phone more. The first is this project's to shave; the second is
 * decided by how often the phone does it, and nothing the code or the emulator can say what the
 * owner's phone does. [LatencyMeter] forgets everything when the process dies, which is exactly
 * the event in question.
 *
 * So the one fact is written down: a line per start, the wall-clock time in milliseconds, in a
 * small private file, kept to the last [KEEP]. The Keyboard speed card reads it and says how
 * many of them fell in the last day. Once or twice a day is a reboot, an update, a switch of
 * keyboards. Dozens means the phone keeps killing it, and the remedy is in the phone's battery
 * and background settings rather than in this code.
 *
 * Times only; nothing about what was typed or where. No Android class is used: the caller
 * supplies the file and the clock, and calls [record] off the main thread.
 */
object ProcessStartLog {
    /** Starts remembered. A phone that kills the keyboard all day fills this within the day. */
    const val KEEP = 60

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** The starts recorded in [file], oldest first. Never throws; an unreadable file is empty. */
    fun read(file: File): List<Long> = try {
        if (file.isFile) file.readLines().mapNotNull { it.trim().toLongOrNull() }.filter { it > 0L }
        else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Adds a start at [nowMs] to [file] and trims it to the last [KEEP]. Never throws: a start
     * that cannot be written (storage still locked after a reboot) is simply not counted.
     */
    fun record(file: File, nowMs: Long) {
        try {
            // A time later than now was written before the clock was set back; it would never
            // age out of "the last 24 hours", so it goes.
            val all = (read(file).filter { it <= nowMs } + nowMs).takeLast(KEEP)
            file.parentFile?.mkdirs()
            val scratch = File(file.parentFile, file.name + ".new")
            scratch.writeText(all.joinToString(separator = "\n", postfix = "\n"))
            if (!scratch.renameTo(file)) {
                file.writeText(all.joinToString(separator = "\n", postfix = "\n"))
                scratch.delete()
            }
        } catch (_: Exception) {
            // Diagnostics must never cost the keyboard anything.
        }
    }

    /**
     * The card's sentence for [starts] as of [nowMs], or null when none fell in the last day
     * (nothing recorded yet, or the file could not be read).
     */
    fun summary(starts: List<Long>, nowMs: Long): String? {
        val past = starts.filter { it in 1..nowMs }
        val day = past.count { it > nowMs - DAY_MS }
        if (day == 0) return null
        val hour = past.count { it > nowMs - HOUR_MS }
        // The file holds KEEP starts at most: when even the oldest of a full file is inside the
        // day, there were at least this many and possibly more.
        val capped = past.size >= KEEP && past.min() > nowMs - DAY_MS
        val count = if (day == 1) "once" else if (capped) "$day times or more" else "$day times"
        val lastHour = if (hour in 1 until day) " ($hour in the last hour)" else ""
        return "Set up from nothing: $count in the last 24 hours$lastHour; " +
            "the latest ${ago(nowMs - past.max())} ago"
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
