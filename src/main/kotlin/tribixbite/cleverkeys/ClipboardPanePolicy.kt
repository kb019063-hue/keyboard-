package tribixbite.cleverkeys

/**
 * How tall the clipboard pane is, and what the row of keys under it shows (2026-11-13).
 *
 * The owner's report, in his words: *"The clipboard: it's arrangement, order, I can't even go
 * back to normal keyboard typing because there's no back to press. Also… the keyboard is still
 * active there (the letter keyboard is covering off the clipboard)… also, there's no easy way or
 * even a way to delete or discard the saved clipboards I don't need."*
 *
 * Three separate causes, and this object decides the first:
 *
 *  - The pane was fixed at a **percentage of the screen** (30 % by default) that the user set for
 *    a list of a few rows; the letter keyboard kept its own space underneath. Scrolling a long
 *    list inside five or six visible rows, over a live keyboard, is what he was doing.
 *  - Its close control was a **small down-arrow between the search field and the filter icon**,
 *    which is not where anyone looks for "back to typing".
 *  - Deleting was reachable only after entering edit mode on one row, and clearing many meant
 *    entering selection mode first.
 *
 * What changed, all of it the owner's choices of 2026-11-13:
 *
 *  1. The clipboard takes the **whole keyboard area**: the pane is as tall as the keyboard
 *     (toolbar + suggestion row + keys) and the keys are not shown while it is open. The row of
 *     keys itself is not rebuilt or hidden from code that reads its state — it keeps its place in
 *     the hierarchy and only stops being drawn — so the typing state (shift, layers, swipe trails)
 *     survives a visit to the clipboard untouched.
 *  2. **A back arrow, top-left**, closes it and returns to typing. That is where Gboard's own
 *     clipboard, emoji and GIF panes put theirs.
 *  3. **Holding a clip** opens Paste / Pin / Edit / Delete, and **swiping it sideways** deletes it
 *     with the existing undo offer. Both existed for one of the two and not the other.
 *  4. **One tap pastes** a clip and closes the pane, as Gboard does; browsing (expanding a long
 *     clip) is a long-press away, so a tap can never do nothing useful.
 *
 * The height is taken from the keyboard, not from the screen, so it follows one-handed, split and
 * floating layouts instead of assuming full width and a known screen size.
 */
object ClipboardPanePolicy {

    /**
     * The height the clipboard pane takes: the keyboard's own height, which is what the pane
     * replaces. Never less than [minimumHeightPx], because a pane shorter than a few rows cannot
     * show what it was opened for (a very short keyboard, e.g. a docked keyboard at the bottom of
     * a split screen, would otherwise give a one-row list).
     *
     * @param keyboardHeightPx the height of the keyboard view plus the toolbar and strip rows.
     * @param screenHeightPx the display height, for the minimum.
     */
    fun paneHeightPx(keyboardHeightPx: Int, screenHeightPx: Int): Int {
        if (keyboardHeightPx <= 0) return 0
        val minimum = (screenHeightPx * MINIMUM_SCREEN_FRACTION).toInt()
        return if (keyboardHeightPx >= minimum) keyboardHeightPx else minimum
    }

    /**
     * Whether the row of letter keys is drawn while the pane is open. False for the clipboard
     * (full height), true for every other pane, which keeps today's behaviour.
     */
    fun keysDrawnFor(pane: PaneKind): Boolean = pane != PaneKind.CLIPBOARD

    /**
     * Whether the toolbar above the pane stays usable. It does: it is the only way to reach
     * settings, the emoji pane and the microphone without leaving the clipboard, and Gboard keeps
     * its own controls in that position. The clipboard's own back arrow is beside it.
     */
    fun toolbarUsableFor(pane: PaneKind): Boolean = true

    /** The pane that is open. Mirrors `KeyboardReceiver.PaneType` without depending on it. */
    enum class PaneKind { NONE, EMOJI, CLIPBOARD, GIF, TOOLS }

    /** The pane is never shorter than this share of the screen. */
    const val MINIMUM_SCREEN_FRACTION = 0.34f

    /**
     * What a tap on a clip does. The owner chose "paste it at once", so a tap pastes and closes;
     * a long-press opens the actions, which is where Expand/Edit lives now.
     */
    const val TAP_PASTES = true
}
