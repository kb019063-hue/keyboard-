package tribixbite.cleverkeys

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatButton
import android.widget.LinearLayout

/**
 * Horizontal bar displaying emoji group category buttons.
 *
 * #41 v4: Category selection now clears search query via EmojiSearchManager.
 */
class EmojiGroupButtonsBar(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private var emojiGrid: EmojiGridView? = null
    private var searchManager: EmojiSearchManager? = null

    /** Every group button, in display order, so the bar can show which group is showing. */
    private val buttons = mutableListOf<EmojiGroupButton>()

    init {
        orientation = HORIZONTAL
        Emoji.init(context.resources)

        // Add clock button for recently used emojis
        buttons += EmojiGroupButton(context, EmojiGridView.GROUP_LAST_USE, "\uD83D\uDD59")
        addView(buttons.last(), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // Add group buttons for each emoji category
        for (i in 0 until Emoji.getNumGroups()) {
            val first = Emoji.getEmojisByGroup(i)[0]
            buttons += EmojiGroupButton(context, i, first.kv().getString())
            addView(buttons.last(), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        // The pane opens on the last-used group, so mark it before the first tap.
        setSelectedGroup(EmojiGridView.GROUP_LAST_USE)
    }

    /**
     * Mark [groupId] as the group on screen, dimming the others.
     *
     * 2026-11-21 (`gif`/look pass, applied here for the same reason): the bar showed no selected
     * state, so tapping a group swapped the grid and left the bar looking unchanged. Alpha is the
     * repository's idiom for this - the clipboard tabs use 1.0 / 0.5
     * (`ClipboardManager.updateTabHighlighting`) - and it survives every runtime theme, unlike a
     * background tint on a pane repainted from `ThemeProvider` at open time.
     */
    fun setSelectedGroup(groupId: Int) {
        for (button in buttons) {
            button.alpha = if (button.groupId == groupId) ACTIVE_ALPHA else INACTIVE_ALPHA
        }
    }

    /**
     * Set the search manager for clearing search on category selection.
     */
    fun setSearchManager(manager: EmojiSearchManager) {
        this.searchManager = manager
    }

    private fun getEmojiGrid(): EmojiGridView {
        return emojiGrid ?: run {
            val grid = (parent as ViewGroup).findViewById<EmojiGridView>(R.id.emoji_grid)
            emojiGrid = grid
            grid
        }
    }

    inner class EmojiGroupButton(
        context: Context,
        internal val groupId: Int,
        symbol: String
    ) : AppCompatButton(ContextThemeWrapper(context, R.style.emojiTypeButton), null, 0),
        View.OnTouchListener {

        init {
            text = symbol
            setOnTouchListener(this)
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN) {
                return false
            }
            // Clear search when category is selected
            searchManager?.onCategorySelected()
            getEmojiGrid().setEmojiGroup(groupId)
            setSelectedGroup(groupId)
            return true
        }
    }

    private companion object {
        /** The group on screen. Matches ClipboardManager.updateTabHighlighting. */
        const val ACTIVE_ALPHA = 1.0f
        /** The others. Dim, not invisible: they must stay readable and tappable. */
        const val INACTIVE_ALPHA = 0.45f
    }
}
