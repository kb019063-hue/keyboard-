package tribixbite.cleverkeys

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/** Clipboard regex is RE2 syntax, not Java regex or glob syntax. */
object ClipboardSearchUtils {
    private const val MAX_PATTERN_LENGTH = 256
    private const val MAX_REPEAT_PRODUCT = 1000

    /**
     * RE2/J matches in linear time, but its compiler expands counted repetitions, including
     * nested ones. Conservatively bound the product of *all* counts before compiling (also
     * covers nested counts), so even a short pattern cannot allocate an enormous program.
     * Escaped braces and braces in character classes are literals and do not count.
     */
    fun compileRegex(query: String): Pattern? {
        if (query.length > MAX_PATTERN_LENGTH || !boundedRepetitions(query)) return null
        return try {
            Pattern.compile(query, Pattern.CASE_INSENSITIVE)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    private fun boundedRepetitions(query: String): Boolean {
        var i = 0
        var inClass = false
        var product = 1
        while (i < query.length) {
            when (query[i]) {
                '\\' -> { i += 2; continue }
                '[' -> inClass = true
                ']' -> inClass = false
                '{' -> if (!inClass) {
                    var end = i + 1
                    while (end < query.length && query[end] != '}') end++
                    if (end < query.length) {
                        val count = query.substring(i + 1, end)
                        if (count.matches(Regex("[0-9]+(,[0-9]*)?"))) {
                            val parts = count.split(',')
                            val upper = (if (parts.size == 2 && parts[1].isNotEmpty()) parts[1] else parts[0])
                                .toIntOrNull() ?: return false
                            val factor = maxOf(1, upper)
                            if (factor > MAX_REPEAT_PRODUCT / product) return false
                            product *= factor
                            i = end
                        }
                    }
                }
            }
            i++
        }
        return true
    }
}
