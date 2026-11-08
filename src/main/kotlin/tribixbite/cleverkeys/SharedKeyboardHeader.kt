package tribixbite.cleverkeys

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView

/**
 * One fixed-height typing row shared by the toolbar and suggestion strip. Content panes keep
 * their existing host below the toolbar; they are not overlaid on the typing header.
 */
class SharedKeyboardHeader(
    context: Context,
    private val toolbar: KeyboardToolbar,
    private val topPane: FrameLayout,
    private val scrollView: HorizontalScrollView,
    private val suggestionBar: SuggestionBar,
    private val rowHeight: Int
) : FrameLayout(context) {
    private var occupied = false
    private var stripHeight = rowHeight

    init {
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        addView(topPane, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        toolbar.onLayoutStateChanged = { refresh() }
        refresh()
    }

    /** Called even while a content pane is open, so closing it restores the latest state. */
    fun setSuggestionState(occupied: Boolean, stripHeight: Int) {
        this.occupied = occupied
        this.stripHeight = stripHeight.coerceAtLeast(0)
        refresh()
    }

    /** Re-evaluate after the receiver swaps a content pane for the strip or vice versa. */
    fun refresh() {
        val showingStrip = scrollView.parent === topPane
        val toolbarVisible = toolbar.visibility == View.VISIBLE
        val compact = showingStrip && occupied && toolbarVisible
        toolbar.setCompactMode(compact)
        suggestionBar.setSharedToolbarBackground(compact)

        val topParams = topPane.layoutParams as? LayoutParams ?: return
        if (showingStrip) {
            // A visible toolbar already reserves the header's height. Otherwise the user's
            // always-visible preference controls whether an empty strip reserves it.
            val targetHeight = if (occupied || !toolbarVisible) stripHeight else 0
            if (topParams.height != targetHeight || topParams.topMargin != 0) {
                topParams.height = targetHeight
                topParams.topMargin = 0
                topPane.layoutParams = topParams
            }
            scrollView.visibility = if (targetHeight > 0) View.VISIBLE else View.GONE
            val (start, end) = if (compact) toolbar.compactEdgeWidths() else 0 to 0
            val scrollParams = scrollView.layoutParams as? LayoutParams ?: return
            if (scrollParams.marginStart != start || scrollParams.marginEnd != end ||
                scrollParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                scrollParams.marginStart = start
                scrollParams.marginEnd = end
                scrollParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                scrollView.layoutParams = scrollParams
            }
        } else {
            // Keep the toolbar above the existing clipboard/emoji/GIF pane, not on top of it.
            val margin = if (toolbarVisible) rowHeight else 0
            if (topParams.topMargin != margin) {
                topParams.topMargin = margin
                topPane.layoutParams = topParams
            }
        }
    }
}
