package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClipboardSearchRegexTest {
    private fun matches(pattern: String, text: String): Boolean =
        ClipboardSearchUtils.compileRegex(pattern)!!.matcher(text).find()

    @Test fun uppercaseEscapesKeepTheirMeaning() {
        assertThat(matches("\\D+", "abc")).isTrue()
        assertThat(matches("\\D+", "123")).isFalse()
        assertThat(matches("\\S+", "   ")).isFalse()
        assertThat(matches("\\W+", "abc")).isFalse()
    }

    @Test fun regexSyntaxIsNotRewrittenAsGlob() {
        assertThat(matches("^(?:cat|dog)$", "CAT")).isTrue()
        assertThat(matches("^a*$", "aaa")).isTrue()
        assertThat(matches("^a*$", "abc")).isFalse()
        assertThat(matches("^colou?r$", "color")).isTrue()
        assertThat(matches("^[?*]$", "?")).isTrue()
        assertThat(matches("^[?*]$", "*")).isTrue()
        assertThat(matches("hello.*world", "hello beautiful world")).isTrue()
    }

    @Test fun invalidUnsupportedAndExcessivePatternsAreRejected() {
        for (pattern in listOf("[", "(?=a)", "(a)\\1", "a".repeat(257),
            "((a{1000}){1000}){1000}", "a{1001}", "a{999999999999999}")) {
            assertThat(ClipboardSearchUtils.compileRegex(pattern)).isNull()
        }
        assertThat(ClipboardSearchUtils.compileRegex("a{10}(b{10})")).isNotNull()
        assertThat(ClipboardSearchUtils.compileRegex("[{}]\\{10000\\}")).isNotNull()
    }

    @Test fun nestedRepetitionMatchesWithoutBacktrackingExplosion() {
        assertThat(matches("^(.+)+$", "a".repeat(20_000) + "\n")).isFalse()
    }

    @Test fun clippingSizeCountsUtf8WithoutAllocatingEncodedCopies() {
        for (text in listOf("", "abc", "é", "বাংলা", "😀", "x\uD800y", "\uDC00", "a\u0000b")) {
            assertThat(ClipboardSizePolicy.utf8Bytes(text)).isEqualTo(text.toByteArray(Charsets.UTF_8).size.toLong())
        }
    }

    @Test fun sizeBoundsIncludeEndpointsAndSupportLargePayloads() {
        assertThat(ClipboardSizePolicy.matches(1024, 1024, 2048)).isTrue()
        assertThat(ClipboardSizePolicy.matches(2048, 1024, 2048)).isTrue()
        assertThat(ClipboardSizePolicy.matches(1023, 1024, 2048)).isFalse()
        assertThat(ClipboardSizePolicy.matches(2049, 1024, 2048)).isFalse()
        assertThat(ClipboardSizePolicy.matches(5_000_000_000, 1024, null)).isTrue()
    }
}
