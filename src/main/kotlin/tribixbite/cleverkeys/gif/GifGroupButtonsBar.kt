package tribixbite.cleverkeys.gif

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatButton
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import tribixbite.cleverkeys.R

/**
 * Scrollable horizontal bar displaying GIF emotion category buttons.
 *
 * Wraps a LinearLayout inside a HorizontalScrollView so all 18 categories
 * (recently used + 17 emotions) are accessible without being squeezed.
 *
 * Fires [onCategoryChanged] when user taps a category. The parent wires
 * this callback to the GifGridManager to switch displayed GIFs.
 */
class GifGroupButtonsBar(context: Context, attrs: AttributeSet) : HorizontalScrollView(context, attrs) {

    private val buttonContainer: LinearLayout

    /** Every category button, so the bar can show which one is showing. Insertion order. */
    private val buttons = mutableListOf<GifGroupButton>()

    /** Fired when a category button is tapped — parent should call gifGrid.setCategory(). */
    var onCategoryChanged: ((GifCategory) -> Unit)? = null

    /** Fired alongside onCategoryChanged — used to clear search input. */
    private var onCategorySelected: (() -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false

        buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }

        // Add "recently used" clock button first
        buttons += GifGroupButton(context, GifCategory.RECENTLY_USED)
        buttonContainer.addView(
            buttons.last(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = dpToPx(44)
            }
        )

        // Add "all" globe button
        buttons += GifGroupButton(context, GifCategory.ALL)
        buttonContainer.addView(
            buttons.last(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = dpToPx(44)
            }
        )

        // Add a button for each browsable emotion category
        for (category in GifCategory.browsableCategories()) {
            buttons += GifGroupButton(context, category)
            buttonContainer.addView(
                buttons.last(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    width = dpToPx(44)
                }
            )
        }

        addView(buttonContainer)
    }

    /**
     * Mark [category] as the one on screen, dimming the others.
     *
     * 2026-11-21 (`gif`/look pass): the bar had no selected state at all. Eighteen emotion emoji
     * sat in a scrollable row and, after a tap, nothing on it changed — the grid swapped but the
     * bar looked identical, so there was no way to tell which category you were looking at or
     * that the tap had registered. (The grid's own fallback made this worse: opening the pane on
     * a fresh install lands on ALL while the visually-first button is RECENTLY_USED, so the one
     * button that looked "current" was never the one loaded.)
     *
     * Alpha is the repository's existing idiom for exactly this - the clipboard pane's tabs use
     * 1.0 for the active tab and 0.5 for the rest (`ClipboardManager.updateTabHighlighting`), and
     * the pane pagers use 1.0 / 0.3. Alpha also survives every runtime theme, which a background
     * tint would not: the panes are repainted from `ThemeProvider` at open time.
     */
    fun setSelectedCategory(category: GifCategory) {
        for (button in buttons) {
            button.alpha = if (button.category == category) ACTIVE_ALPHA else INACTIVE_ALPHA
        }
    }

    /**
     * Set callback for when a category button is tapped.
     * Used to clear search query when user browses by category.
     */
    fun setOnCategorySelectedListener(listener: () -> Unit) {
        this.onCategorySelected = listener
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        /** The category on screen. Matches ClipboardManager.updateTabHighlighting. */
        const val ACTIVE_ALPHA = 1.0f
        /** The others. Dim, not invisible: they must stay readable and tappable. */
        const val INACTIVE_ALPHA = 0.45f
    }

    /**
     * Individual category button showing the emoji icon for each GIF emotion.
     */
    inner class GifGroupButton(
        context: Context,
        internal val category: GifCategory
    ) : AppCompatButton(ContextThemeWrapper(context, R.style.emojiTypeButton), null, 0),
        View.OnTouchListener {

        // Audit E-6: selection commits on UP-within-slop, never on DOWN — the bar always
        // overflows its HorizontalScrollView, and firing on DOWN made every scroll gesture
        // switch category and wipe the in-progress search (the HSV can only intercept from
        // MOVE onward, so the button sees every gesture's DOWN). The parent's interception
        // delivers ACTION_CANCEL, which the detector treats as "not a tap".
        private val tapDetector = GifCategoryTapDetector(
            android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        )

        init {
            text = category.icon
            contentDescription = category.displayName
            setOnTouchListener(this)
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (tapDetector.feed(event.action, event.x, event.y)) {
                setSelectedCategory(category)
                // Notify search clear callback
                onCategorySelected?.invoke()
                // Notify category change callback
                onCategoryChanged?.invoke(category)
            }
            return true
        }
    }
}
