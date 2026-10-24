package tribixbite.cleverkeys

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The floating-keyboard wrapper: the keyboard content plus the two handles that make a small
 * window usable -- a grip across the top to drag it, a knob at the bottom-right corner to change
 * its width.
 *
 * Sits OUTSIDE [OneHandedRoot] in the setInputView chain (the service hosts the one-handed root
 * inside this one) so the grip and knob are never scaled with the keys, and while floating is on
 * the service suppresses one-handed scaling altogether: two independent shrinks stacking would
 * leave the keyboard smaller than either setting says.
 *
 * While floating is OFF this view is invisible in every sense that matters: the grip and knob are
 * GONE and the content holder wraps the keyboard exactly, so a non-floating keyboard measures and
 * draws as if this wrapper were not there. That is what lets the service keep the wrapper
 * installed for the whole session and merely flip [setState] on toggle, instead of re-wrapping the
 * input view mid-keystroke -- the same reason [OneHandedRoot.host] is idempotent.
 *
 * The handles report MOVEMENT, not geometry: [onDrag] and [onResize] carry pixel deltas and the
 * service translates them into the persisted fractions and the window's layout params. Keeping
 * the arithmetic out of the view is what makes [FloatingConfig] the one place the maths lives.
 */
class FloatingRoot(context: Context) : FrameLayout(context) {

    private val grip = View(context).apply {
        setBackgroundColor(HANDLE_COLOUR)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(GRIP_HEIGHT_DP)
        )
    }
    private val contentHolder = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(grip)
        addView(contentHolder)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private val knob = View(context).apply {
        setBackgroundColor(HANDLE_COLOUR)
        layoutParams = LayoutParams(dp(KNOB_SIZE_DP), dp(KNOB_SIZE_DP)).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        }
    }

    /** Pixel deltas while the grip is dragged; the service maps them onto the stored fractions. */
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null
    /** The grip was released: the service persists the position it just moved to. */
    var onDragEnd: (() -> Unit)? = null
    /** Pixel deltas while the knob is dragged horizontally; vertical movement is ignored. */
    var onResize: ((dx: Int) -> Unit)? = null
    /** The knob was released: the service persists the width it just changed. */
    var onResizeEnd: (() -> Unit)? = null

    init {
        addView(column)
        addView(knob)
        setState(floating = false)
        grip.setOnTouchListener { _, event ->
            track(event, lastDrag, { dx, dy -> onDrag?.invoke(dx, dy) }, { onDragEnd?.invoke() })
        }
        knob.setOnTouchListener { _, event ->
            track(event, lastResize, { dx, _ -> onResize?.invoke(dx) }, { onResizeEnd?.invoke() })
        }
    }

    /**
     * Make [v] the wrapped keyboard content. Idempotent, like [OneHandedRoot.host]: theme changes,
     * pane swaps and minimize/expand all reach setInputView again, and nesting a holder inside a
     * holder would shrink the keyboard a little more on every pass.
     */
    fun host(v: View): FloatingRoot {
        if (contentHolder.childCount == 1 && contentHolder.getChildAt(0) === v) return this
        contentHolder.removeAllViews()
        val parent = v.parent
        if (parent is android.view.ViewGroup) parent.removeView(v)
        contentHolder.addView(v)
        return this
    }

    /** Show or hide the handles; the content is unaffected either way. */
    fun setState(floating: Boolean) {
        val vis = if (floating) View.VISIBLE else View.GONE
        grip.visibility = vis
        knob.visibility = vis
    }

    private val lastDrag = FloatArray(2)
    private val lastResize = FloatArray(2)

    /**
     * One touch stream as deltas: seed the last position on DOWN, report movement on MOVE, and
     * fire the matching end callback on UP or CANCEL so a persisted fraction never lags the
     * window by one gesture.
     */
    private fun track(
        event: MotionEvent,
        last: FloatArray,
        move: (Int, Int) -> Unit,
        end: () -> Unit
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last[0] = event.rawX
                last[1] = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - last[0]).toInt()
                val dy = (event.rawY - last[1]).toInt()
                last[0] = event.rawX
                last[1] = event.rawY
                if (dx != 0 || dy != 0) move(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Only fire the end callback for a stream this handle actually seeded; a stray
                // UP (a tap that began elsewhere) must not persist anything.
                if (last[0] != 0f || last[1] != 0f) end()
                last[0] = 0f
                last[1] = 0f
            }
        }
        return true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val GRIP_HEIGHT_DP = 26
        const val KNOB_SIZE_DP = 26
        /** Translucent mid-grey: visible on both the light and the dark keyboard themes. */
        const val HANDLE_COLOUR = 0x33808080
    }
}
