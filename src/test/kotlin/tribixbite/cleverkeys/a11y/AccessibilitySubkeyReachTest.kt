package tribixbite.cleverkeys.a11y

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData

/**
 * What TalkBack can and cannot reach on a key that carries more than one symbol.
 *
 * Found 2026-11-21 while auditing `a11y/`. [KeyboardAccessibilityHelper] builds one virtual view per
 * non-placeholder key, and [KeyboardGeometry.computeAccessibilityKeyRects] takes `keys[0]` — the key
 * face. The eight corner slots (`nw`, `ne`, `sw`, `se`, `w`, `e`, `n`, `s` in the layout grammar)
 * get no virtual view at all, so their symbols are not in the accessibility tree.
 *
 * Why that matters, measured on the shipped `latn_qwerty_es` layout:
 *
 *  - `<key key0="d" key1="accent_grave" key3="accent_aigu"/>`
 *  - `<key key0="c" key1="loc accent_cedille" key2="&lt;" key3="."/>`
 *  - `<key key0="ñ" key1="·" key3="ç"/>`
 *
 * `ñ` has its own key, so it is fine. The acute accent (é), the grave accent (è) and the cedilla
 * (ç) exist ONLY in corner slots. A sighted user reaches them by swiping from the key; a TalkBack
 * user cannot, because touch exploration turns a swipe into a focus move. So on the Spanish layout
 * a screen-reader user cannot type `é`, `è` or `ç` from the keyboard's key tree at all.
 *
 * Note what is NOT the cause: `KeyModifier.modify_long_press` deliberately returns character keys
 * unchanged (long-press on a letter is key-repeat, not a symbol popup), so long-press offers no
 * second route either, for any user.
 *
 * This file pins the behaviour that exists — so the claim above is a fact, and a future change to
 * `keys[0]` shows up as a failure here — rather than asserting a fix. A fix means deciding what a
 * screen reader should say for "this key also types é", which is a design call; it is written up
 * as V6 in `docs/BUG-HUNT-2026-11-15-PART-8.md`. The US layout is affected far less: its corners
 * hold punctuation, not accented letters.
 */
class AccessibilitySubkeyReachTest {

    /** `Key` slots are centre, then nw, ne, sw, se, w, e, n, s. Only slot 0 becomes a node. */
    private fun key(vararg values: KeyValue?): KeyboardData.Key {
        val slots = MutableList<KeyValue?>(9) { null }
        values.forEachIndexed { i, kv -> slots[i] = kv }
        return KeyboardData.Key(slots.toList(), null, 0, 1f, 1f, null)
    }

    private val params = KeyboardGeometry.Params(
        keyWidth = 100f,
        rowHeight = 160f,
        marginTop = 0f,
        marginLeft = 0f,
    )

    /**
     * The real `latn_qwerty_es` 'd' key, copied slot for slot:
     * `<key key0="d" key1="accent_grave" key3="accent_aigu"/>` — face `d`, grave accent in the
     * NORTH-WEST corner (key1), acute accent in the SOUTH-WEST corner (key3). Neither accented
     * letter exists as a primary key anywhere on that layout.
     */
    private val dWithAccents = key(
        KeyValue.makeCharKey('d'),
        KeyValue.makeCharKey('è'),   // key1 = nw = accent_grave
        null,                        // key2 = ne
        KeyValue.makeCharKey('é'),   // key3 = sw = accent_aigu
        null, null, null, null, null,
    )

    @Test
    fun `a corner symbol produces no virtual view of its own`() {
        val rows = listOf(KeyboardData.Row(listOf(dWithAccents), 1f, 0f))
        val rects = KeyboardGeometry.computeAccessibilityKeyRects(rows, params, hostWidth = 100f)

        // ONE node per key, not one per symbol: the two corner symbols add no node, so a key
        // carrying d/è/é yields exactly one rect -- which is the finding.
        assertThat(rects).hasSize(1)
        assertThat(rects.map { it.kv.getString() }).containsExactly("d")

        // Two DISTINCT keys side by side still give exactly two nodes. (The two keys must differ:
        // KeyboardData.Key is a data class, so the same fixture twice is one value, and the walk
        // produces a single rect for it. My first version of this line used dWithAccents twice
        // and failed exactly that way -- see V8 in the report for the run that caught it.)
        val other = key(KeyValue.makeCharKey('f'), KeyValue.makeCharKey('è'), null,
            KeyValue.makeCharKey('é'), null, null, null, null, null)
        val pair = listOf(KeyboardData.Row(listOf(dWithAccents, other), 1f, 0f))
        assertThat(KeyboardGeometry.computeAccessibilityKeyRects(pair, params, hostWidth = 200f))
            .hasSize(2)
    }

    @Test
    fun `the parent node announces only the key face`() {
        val rows = listOf(KeyboardData.Row(listOf(dWithAccents), 1f, 0f))
        val rect = KeyboardGeometry.computeAccessibilityKeyRects(rows, params, hostWidth = 100f).single()

        // What a screen reader speaks: "d". Nothing tells the user this key also types è and é.
        assertThat(KeyLabels.describe(rect.kv) { "" }).isEqualTo("d")

        // The symbols really are on the key — they are reachable by a swipe, not by the tree.
        assertThat(dWithAccents.keys[1]?.getString()).isEqualTo("è")   // nw
        assertThat(dWithAccents.keys[3]?.getString()).isEqualTo("é")   // sw
    }

    @Test
    fun `the accented letters a Spanish user needs are unreachable through the node tree`() {
        val rows = listOf(KeyboardData.Row(listOf(dWithAccents), 1f, 0f))
        val exposed = KeyboardGeometry.computeAccessibilityKeyRects(rows, params, hostWidth = 100f)
            .map { it.kv.getString() }
            .toSet()

        // 'd' is typeable; 'è' and 'é' are not, because no node carries them, so no ACTION_CLICK
        // can produce them. This is the finding, asserted rather than described.
        assertThat(exposed).contains("d")
        assertThat(exposed).doesNotContain("è")
        assertThat(exposed).doesNotContain("é")
    }

    @Test
    fun `a key with no corner symbols exposes exactly its face, so nothing is lost`() {
        // The control case: when keys[1..8] are null the single node IS the whole key. This is why
        // a plain letter is complete, and why the gap is specifically about corner symbols.
        val plain = key(KeyValue.makeCharKey('ñ'))
        val rows = listOf(KeyboardData.Row(listOf(plain), 1f, 0f))
        val rect = KeyboardGeometry.computeAccessibilityKeyRects(rows, params, hostWidth = 100f).single()

        assertThat(rect.key.keys.drop(1).filterNotNull()).isEmpty()
        assertThat(KeyLabels.describe(rect.kv) { "" }).isEqualTo("ñ")
    }
}
