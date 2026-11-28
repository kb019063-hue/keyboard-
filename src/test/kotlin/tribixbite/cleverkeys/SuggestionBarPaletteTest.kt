package tribixbite.cleverkeys

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.w3c.dom.Element
import tribixbite.cleverkeys.theme.getAllPredefinedThemesList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Suggestion-bar legibility over every built-in theme (Pixel report 2026-10-17: suggestion text
 * the same colour as the bar).
 *
 * The bar draws three text roles on one background: ordinary chips ([Theme.suggestionTextColor]),
 * the highlighted commit chip ([Theme.suggestionHighConfidenceColor]) and the prompt chips
 * ("+word", "Don't ask": [Theme.suggestionSecondaryTextColor]). For XML themes [Theme] maps
 * them from `colorLabel`, `colorLabelActivated` and `colorSubLabel` on `colorKey`; but
 * `colorLabelActivated` was designed for `colorKeyActivated`, so on the bar it can equal the
 * background (Cobalt, Pine, ePaper Black: 1.00:1). Decorative themes carry the three roles
 * directly. [SuggestionBarPalette.resolve] is the single place both constructors pass through.
 *
 * Floor: [SuggestionBarPalette.MIN_CONTRAST] = 3:1 (WCAG 1.4.11 non-text / large-text
 * minimum). Measured 2026-10-17: ordinary chip text is 4.29:1 at worst (Everforest Light), so
 * every built-in already passes for that role and keeps its exact colour.
 */
class SuggestionBarPaletteTest {

    private data class Roles(val name: String, val text: Int, val highlight: Int, val secondary: Int, val background: Int)

    /** Each XML style's bar colours, resolved through `parent` and `?attr` references. */
    private fun xmlThemes(): List<Roles> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("res/values/themes.xml"))
        val styles = mutableMapOf<String, Pair<String?, Map<String, String>>>()
        val nodes = doc.getElementsByTagName("style")
        for (i in 0 until nodes.length) {
            val style = nodes.item(i) as Element
            val items = style.getElementsByTagName("item")
            val values = (0 until items.length).associate {
                val item = items.item(it) as Element
                item.getAttribute("name") to item.textContent.trim()
            }
            styles[style.getAttribute("name")] = style.getAttribute("parent").ifEmpty { null } to values
        }
        fun resolve(style: String, attr: String): String? {
            val (parent, values) = styles[style] ?: return null
            val value = values[attr] ?: return parent?.let { resolve(it.substringAfterLast('/'), attr) }
            return if (value.startsWith("?")) resolve(style, value.removePrefix("?").removePrefix("attr/")) else value
        }
        fun argb(hex: String): Int = (0xFF000000L or hex.removePrefix("#").takeLast(6).toLong(16)).toInt()
        return styles.keys.mapNotNull { style ->
            val label = resolve(style, "colorLabel") ?: return@mapNotNull null
            val key = resolve(style, "colorKey") ?: return@mapNotNull null
            // The bar's OWN background, which is what Theme passes to the resolver since
            // 2026-10-30 (it was the key colour, and on a translucent theme that made the
            // resolver pick black text for a near-black strip).
            val bar = resolve(style, "colorSuggestionBar") ?: key
            // Monet styles reference @android:color/system_* (wallpaper-derived at runtime):
            // not resolvable here; their palette is Material's own tonal contrast.
            if (!label.startsWith("#") || !key.startsWith("#")) return@mapNotNull null
            Roles(
                style, argb(label),
                argb(resolve(style, "colorLabelActivated") ?: label),
                argb(resolve(style, "colorSubLabel") ?: label),
                argb(bar),
            )
        }
    }

    private fun decorativeThemes(): List<Roles> = getAllPredefinedThemesList().map {
        val s = it.colorScheme
        Roles(it.id, s.suggestionText.toArgb(), s.suggestionHighConfidence.toArgb(),
            s.keySubLabel.toArgb(), s.suggestionBackground.toArgb())
    }

    private fun failures(themes: List<Roles>): List<String> = themes.flatMap { t ->
        val c = SuggestionBarPalette.resolve(t.text, t.highlight, t.secondary, t.background)
        listOf("text" to c.text, "highlight" to c.highlight, "secondary" to c.secondary).mapNotNull { (role, color) ->
            val ratio = SuggestionBarPalette.contrastRatio(color, t.background)
            if (ratio < SuggestionBarPalette.MIN_CONTRAST) "${t.name} $role %.2f:1".format(ratio) else null
        }
    }

    @Test
    fun everyBuiltInXmlThemeKeepsEverySuggestionRoleReadable() {
        val themes = xmlThemes()
        assertWithMessage("XML styles parsed").that(themes.map { it.name })
            .containsAtLeast("Dark", "Light", "Cobalt", "Pine", "ePaperBlack", "Desert", "Jungle", "Jewel")
        assertWithMessage("roles below ${SuggestionBarPalette.MIN_CONTRAST}:1").that(failures(themes)).isEmpty()
    }

    @Test
    fun everyDecorativeThemeKeepsEverySuggestionRoleReadable() {
        val themes = decorativeThemes()
        assertThat(themes.map { it.name }).contains("neon_cyberpunk")
        assertWithMessage("roles below ${SuggestionBarPalette.MIN_CONTRAST}:1").that(failures(themes)).isEmpty()
    }

    @Test
    fun translucentKeyColourDoesNotDecideTheTextColour() {
        // The 2026-10-30 regression: the Gboard-glass key is 15 % white, and resolving the bar's
        // text against THAT pixel colour made the resolver answer black — black text and black
        // toolbar icons on a near-black keyboard. Resolving against the strip's real backdrop
        // keeps the theme's white text.
        val glassText = SuggestionBarPalette.resolve(
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), 0xC4141619.toInt()
        )
        assertThat(glassText.text).isEqualTo(0xFFFFFFFF.toInt())
        // And the light variant keeps its ink.
        val lightGlass = SuggestionBarPalette.resolve(
            0xFF1F1F1F.toInt(), 0xFF1F1F1F.toInt(), 0xFF5F6368.toInt(), 0xCCF1F3F4.toInt()
        )
        assertThat(lightGlass.text).isEqualTo(0xFF1F1F1F.toInt())
    }

    @Test
    fun readableColoursAreKeptExactly() {
        // Light: black text and #0066CC highlight on #CCCCCC already pass; nothing may change.
        val light = SuggestionBarPalette.resolve(0xFF000000.toInt(), 0xFF0066CC.toInt(), 0xFF333333.toInt(), 0xFFCCCCCC.toInt())
        assertThat(light).isEqualTo(SuggestionBarPalette.Colors(0xFF000000.toInt(), 0xFF0066CC.toInt(), 0xFF333333.toInt()))
        // Ordinary chip text of every built-in passes, so its colour is never substituted.
        for (t in xmlThemes() + decorativeThemes()) {
            assertWithMessage(t.name)
                .that(SuggestionBarPalette.resolve(t.text, t.highlight, t.secondary, t.background).text)
                .isEqualTo(t.text)
        }
    }

    @Test
    fun anUnreadableHighlightOrPromptFallsBackToTheTextColour() {
        // Cobalt: activated label is ?colorKey, i.e. the bar background itself.
        val cobalt = SuggestionBarPalette.resolve(0xFF95C9FF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt())
        assertThat(cobalt.highlight).isEqualTo(0xFF95C9FF.toInt())
        assertThat(cobalt.secondary).isEqualTo(0xFF95C9FF.toInt())
    }

    @Test
    fun textTheSameColourAsTheBarBecomesBlackOrWhite() {
        // A DIY theme can set text == background; invisible text is never the intent.
        val onLight = SuggestionBarPalette.resolve(0xFFCCCCCC.toInt(), 0xFFCCCCCC.toInt(), 0xFFCCCCCC.toInt(), 0xFFCCCCCC.toInt())
        assertThat(onLight).isEqualTo(SuggestionBarPalette.Colors(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()))
        val onDark = SuggestionBarPalette.resolve(0xFF121225.toInt(), 0xFF121225.toInt(), 0xFF121225.toInt(), 0xFF121225.toInt())
        assertThat(onDark.text).isEqualTo(0xFFFFFFFF.toInt())
        // The bar's alpha (user opacity) does not enter the comparison.
        assertThat(SuggestionBarPalette.contrastRatio(0x00000000, 0xFF000000.toInt())).isWithin(1e-9).of(1.0)
    }

    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        assertThat(SuggestionBarPalette.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isWithin(1e-9).of(21.0)
        assertThat(SuggestionBarPalette.contrastRatio(0xFF777777.toInt(), 0xFFFFFFFF.toInt())).isWithin(0.01).of(4.48)
    }

    /** 2026-11-25: the owner picked the suggestion strip as the next surface to fix. */
    @Test
    fun threeWordsShareTheStripWidthAndTheEmptyRowIsCentred() {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt").readText()
        assertWithMessage("the normal strip must spread up to three words evenly")
            .that(source).contains("count in 1..MAX_EVENLY_SPACED_SUGGESTIONS")
        assertWithMessage("longer/debug sets must stay scrollable")
            .that(source).contains("ViewGroup.LayoutParams.WRAP_CONTENT")
        assertWithMessage("MAX_EVENLY_SPACED_SUGGESTIONS must be three")
            .that(source).contains("private const val MAX_EVENLY_SPACED_SUGGESTIONS = 3")
        val placeholder = source.substringAfter("private fun showPlaceholder(").substringBefore("\n    }")
        assertWithMessage("the echoed word / swipe hint must sit in the middle of the row")
            .that(placeholder).contains("gravity = Gravity.CENTER")
    }

    // ---------------------------------------------------------------------------------------
    // The owner's Gboard comparison, 2026-11-25: *"the suggestion strip and toolbar of mine and
    // Gboard's … Mine doesn't look right. Can you make it look like Gboard?"* Two answers were
    // confirmed with the owner, and each is the opposite of what the code did before:
    //   1. ONE row above the keys: the toolbar and the strip share one 40 dp band and take turns
    //      owning it — full toolbar while the field is empty, words with the toolbar's first and
    //      last commands at the ends while typing. It used to be a second row stacked on top.
    //   2. No pill, thin dividers: every word plain, same weight, same ink, separated by thin
    //      vertical rules, as in the owner's Gboard screenshots.
    // These are View classes, so — like the strip-width test above — the checks read the sources.
    // ---------------------------------------------------------------------------------------

    private fun toolbarSource() = File("src/main/kotlin/tribixbite/cleverkeys/KeyboardToolbar.kt").readText()

    private fun serviceSource() = File("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt").readText()

    @Test
    fun theStripPaintsNoPillAndSeparatesWordsWithThinRules() {
        val strip = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt").readText()
        assertWithMessage("the commit chip must not be filled: no pill on this surface")
            .that(strip).doesNotContain("SuggestionPillPolicy.look(")
        assertWithMessage("no rounded pill radius left behind")
            .that(strip).doesNotContain("PILL_RADIUS_DP")

        val highlight = strip.substringAfter("index == currentHighlightIndex ->")
            .substringBefore("\n            }\n")
        assertWithMessage("every word, including the commit one, uses the plain strip ink")
            .that(highlight).contains("theme?.suggestionTextColor")
        assertWithMessage("the commit word must not be bolded any more")
            .that(highlight).contains("Typeface.NORMAL")

        assertWithMessage("a divider is drawn between neighbouring words")
            .that(strip).contains("private fun createDividerView()")
        assertWithMessage("dividers are interleaved with the words, not appended after them")
            .that(strip).contains("desired.add(dividerViews[i])")
        assertWithMessage("one rule between each pair of words")
            .that(strip).contains("val dividerCount = if (needsDividers) count - 1 else 0")
        assertWithMessage("a single word has no divider")
            .that(strip).contains("val needsDividers = count > 1")
    }

    @Test
    fun theStripCarriesTheToolbarsEndsInTheSameRow() {
        val strip = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt").readText()
        assertWithMessage("the strip exposes the toolbar's two end commands")
            .that(strip).contains("fun setRowEnds(")
        assertWithMessage("the start end is placed before the first word")
            .that(strip).contains("if (showEnds) startEndButton?.let { desired.add(it) }")
        assertWithMessage("the end end is placed after the last word")
            .that(strip).contains("if (showEnds) endEndButton?.let { desired.add(it) }")
        assertWithMessage("ends frame words, so an empty strip shows none")
            .that(strip).contains("val showEnds = count > 0 && !hasCenteredPrompt")
        assertWithMessage("the mic end is drawn as Gboard's filled circle")
            .that(strip).contains("shape = GradientDrawable.OVAL")

        assertWithMessage("the toolbar publishes its ends, by position")
            .that(toolbarSource()).contains("fun rowEnds(): List<RowEnd>")
        assertWithMessage("a strip end routes through the toolbar's own dispatcher")
            .that(toolbarSource()).contains("fun dispatchCommand(name: String)")
        assertWithMessage("the service wires the ends from the toolbar")
            .that(serviceSource()).contains("wireStripRowEnds()")
        assertWithMessage("the wiring hands the ends to the strip")
            .that(serviceSource()).contains("bar.setRowEnds(")
    }

    @Test
    fun theBandAboveTheKeysIsOneRowNotTwo() {
        val toolbar = toolbarSource()
        val service = serviceSource()
        assertWithMessage("the toolbar steps aside while the strip owns the band")
            .that(toolbar).contains("fun setHiddenForStrip(hidden: Boolean)")
        assertWithMessage("and only one of the two is ever visible")
            .that(toolbar).contains("visibility = if (enabledRow && !hiddenForStrip) View.VISIBLE else View.GONE")

        val apply = service.substringAfter("private fun applyStripHeight(").substringBefore("\n    }\n")
        assertWithMessage("the strip's band collapses when the toolbar takes the row")
            .that(apply).contains("val target = if (stripTakesRow) full else 0")
        assertWithMessage("which view owns the row follows the strip's own report")
            .that(apply).contains("val stripTakesRow = occupied || !toolbarCanFill")
        assertWithMessage("the toolbar is handed the same decision, so 40 dp is the whole band")
            .that(apply).contains("toolbar?.setHiddenForStrip(stripTakesRow)")

        val strip = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt").readText()
        assertWithMessage("the strip reports the row it needs, including a word being typed")
            .that(strip).contains("fun ownsRow(): Boolean = isOccupied() || placeholderIsWord")
        assertWithMessage("the service lays the band out from that, not from bare occupancy")
            .that(service).contains("applyStripHeight(_suggestionBar?.ownsRow() == true)")

        assertWithMessage("the old compact second-row path is no longer driven")
            .that(service).doesNotContain("setCompactMode(")
    }

    @Test
    fun theToolbarsMicSitsInAFilledCircle() {
        val toolbar = toolbarSource()
        assertWithMessage("the mic gets Gboard's circle")
            .that(toolbar).contains("shape = android.graphics.drawable.GradientDrawable.OVAL")
        assertWithMessage("the circle's glyph is the bar background, so it reads as a cut-out")
            .that(toolbar).contains("private fun micGlyphColor()")
        assertWithMessage("the circle's fill is the strip's secondary ink")
            .that(toolbar).contains("private fun micFillColor()")
        assertWithMessage("the glyph is a shade smaller than a bare icon to leave the fill visible")
            .that(toolbar).contains("const val MIC_ICON_SIZE_DP = 20")
    }
}
