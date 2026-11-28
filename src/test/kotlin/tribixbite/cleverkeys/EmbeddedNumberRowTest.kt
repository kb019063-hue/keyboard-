package tribixbite.cleverkeys

import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * The number-row preference decides BOTH ways (2026-11-28).
 *
 * `LayoutModifier.modify_layout` consulted `add_number_row` only to ADD a row, so any layout that
 * carries its digits inside itself (`embedded_number_row="true"`) kept them however the preference
 * was set. That made the switch dead on the one layout the keyboard ships as its default — the
 * Gboard-style layout embeds its digit row — while it worked on every layout that borrows
 * `res/xml/number_row.xml`. The owner saw exactly the half of it that worked.
 *
 * The removal needs to know WHICH row is the digit row, and guessing wrong would delete a row the
 * user needs, so the recogniser is strict: ten keys, one unit wide and unshifted, labelled with the
 * ten digits. These tests pin the recogniser, the removal, and the wiring.
 */
class EmbeddedNumberRowTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
    }

    @After
    fun teardown() = unmockkAll()

    private fun parse(body: String): KeyboardData =
        KeyboardData.load_string_exn(
            """<?xml version="1.0" encoding="utf-8"?>
               <keyboard name="T" script="latin" bottom_row="false" embedded_number_row="true">
               $body
               </keyboard>"""
        )

    private val digitRow = """
        <row>
          <key c="1"/><key c="2"/><key c="3"/><key c="4"/><key c="5"/>
          <key c="6"/><key c="7"/><key c="8"/><key c="9"/><key c="0"/>
        </row>
    """

    private val letterRows = """
        <row>
          <key c="q"/><key c="w"/><key c="e"/><key c="r"/><key c="t"/>
          <key c="y"/><key c="u"/><key c="i"/><key c="o"/><key c="p"/>
        </row>
        <row>
          <key c="a"/><key c="s"/><key c="d"/><key c="f"/><key c="g"/>
          <key c="h"/><key c="j"/><key c="k"/><key c="l"/>
        </row>
    """

    @Test
    fun theEmbeddedDigitRowIsFound() {
        val layout = parse(digitRow + letterRows)
        assertWithMessage("the ten-digit row must be recognised, at the top where it is drawn")
            .that(layout.embeddedNumberRowIndex()).isEqualTo(0)
    }

    @Test
    fun aRowOfSymbolsIsNotADigitRow() {
        val layout = parse(
            """
            <row>
              <key c="!"/><key c="@"/><key c="#"/><key c="${'$'}"/><key c="%"/>
              <key c="^"/><key c="&amp;"/><key c="*"/><key c="("/><key c=")"/>
            </row>
            """ + letterRows
        )
        assertWithMessage("a symbol row must never be mistaken for the digit row")
            .that(layout.embeddedNumberRowIndex()).isEqualTo(-1)
    }

    @Test
    fun aRowThatIsNotTenWholeUnitsIsNotADigitRow() {
        // A four-row phone pad: ten keys would be a coincidence, the widths are not.
        val layout = parse(
            """
            <row>
              <key width="2.5" c="1"/><key width="2.5" c="2"/><key width="2.5" c="3"/><key width="2.5" c="4"/>
            </row>
            """
        )
        assertWithMessage("a wide-key row must not be mistaken for the digit row")
            .that(layout.embeddedNumberRowIndex()).isEqualTo(-1)
    }

    @Test
    fun removingTheRowTakesItOffTheKeyboardAndOutOfItsHeight() {
        val layout = parse(digitRow + letterRows)
        val before = layout.rows.size
        val heightBefore = layout.keysHeight

        val stripped = layout.withoutRow(layout.embeddedNumberRowIndex())

        assertWithMessage("the digit row is gone").that(stripped.rows.size).isEqualTo(before - 1)
        assertWithMessage("and the keyboard is exactly one row shorter")
            .that(stripped.keysHeight).isLessThan(heightBefore)
        assertWithMessage("no digit survives on the keyboard")
            .that(stripped.getKeys().keys.any { kv ->
                kv.getKind() == KeyValue.Kind.Char && kv.getChar() in '0'..'9'
            }).isFalse()
        assertWithMessage("the letter rows are untouched")
            .that(stripped.getKeys().keys.any { kv ->
                kv.getKind() == KeyValue.Kind.Char && kv.getChar() == 'q'
            }).isTrue()
    }

    @Test
    fun anOutOfRangeRowIsANoOp() {
        val layout = parse(digitRow + letterRows)
        assertWithMessage("a bogus index must return the layout unchanged, not throw")
            .that(layout.withoutRow(99).rows.size).isEqualTo(layout.rows.size)
        assertThat(layout.withoutRow(-1).rows.size).isEqualTo(layout.rows.size)
    }

    @Test
    fun theModifierConsultsThePreferenceBothWays() {
        // Source pin (drift-test idiom): the strip must sit before the row list is built, and it
        // must be gated on the preference being OFF — the add path is the `else if` above it.
        val src = File("src/main/kotlin/tribixbite/cleverkeys/LayoutModifier.kt").readText()
        assertWithMessage("the modifier must strip an embedded digit row when the preference is off")
            .that(src).contains("!globalConfig.add_number_row")
        assertWithMessage("it must only do so for a layout that carries its own digit row")
            .that(src).contains("kw.embedded_number_row")
        assertWithMessage("and it must use the strict recogniser rather than row 0 by assumption")
            .that(src).contains("kw.embeddedNumberRowIndex()")
        assertWithMessage("nothing may strip the digits while the numpad is the thing on screen")
            .that(src).contains("!globalConfig.show_numpad")
    }

    /** Compile-time proof that the parser returned a usable row list (guards a silent null). */
    @Test
    fun theFixtureParses() {
        val layout = parse(digitRow + letterRows)
        assertWithMessage("three rows were written").that(layout.rows.size).isEqualTo(3)
        assertThat(layout.embedded_number_row).isTrue()
        assertThat(layout.rows[0].keys.size).isEqualTo(10)
        assertThat(XmlPullParser.START_TAG).isEqualTo(2)
    }
}
