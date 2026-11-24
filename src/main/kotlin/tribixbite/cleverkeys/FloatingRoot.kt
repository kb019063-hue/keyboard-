package tribixbite.cleverkeys

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The floating-keyboard wrapper: the keyboard content plus the two handles that make a small
 * window usable -- a grip along the BOTTOM to drag it, a knob at the bottom-right corner to change
 * its width.
 *
 * ## Why the grip is at the bottom (2026-11-15)
 *
 * It used to be across the top. Gboard's is at the bottom, and that is the arrangement to copy, for
 * a reason beyond taste: dragging the handle *down* is how you dock, and with the handle already at
 * the bottom edge that is one gesture rather than a hunt. Five independent sources across 2018-2024
 * describe it that way ("drag the keyboard around by the bump on the bottom"; "drag the handle at
 * the bottom"; "drag it to the bottom edge and it will magnetize") -- see PART-18's P5.
 *
 * There is a mechanical payoff too. A band above the content sits INSIDE the input-method window,
 * so it pushed the toolbar 26dp down along with every y coordinate in the window. That is what put
 * the smoke test's computed toolbar tap into the grip and left the tools panel unverified. Below
 * the content the grip costs the toolbar nothing.
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
        // Right margin = the knob. With the grip at the BOTTOM the knob's frame
        // (bottom|end, same HANDLE_COLOUR) sits exactly on top of the grip's last 26dp, so it
        // would paint an identical grey square over an identical grey band and the resize
        // affordance would vanish. Stopping the grip short keeps both handles at full size and
        // leaves the two touch zones disjoint: the band drags, the corner resizes — which is
        // Gboard's own split (a "bump on the bottom" to drag, corner handles to resize).
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(GRIP_HEIGHT_DP)
        ).apply { rightMargin = dp(KNOB_SIZE_DP) }
    }
    private val contentHolder = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Content FIRST, grip BELOW it (2026-11-15). Gboard puts its drag handle at the bottom —
        // "drag the keyboard around by the bump on the bottom", and dragging that handle down is
        // how you dock, one gesture because the handle is already at the edge you dock to. Five
        // independent sources across 2018–2024 agree; see PART-18's P5 for the receipts.
        //
        // The position is not cosmetic. A grip above the content is a band INSIDE the input-method
        // window, so it pushes the toolbar 26dp down and every y coordinate inside the window with
        // it — which is precisely how the smoke test's computed toolbar tap ended up in the grip
        // and why the tools panel went unverified for months. Below the content the grip costs the
        // toolbar nothing.
        addView(contentHolder)
        addView(grip)
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

    // This is the outermost view of the keyboard, so timing its measure and its layout times the
    // whole tree's: the keys, the suggestion strip, the toolbar, a content pane. That is the
    // keyboard's own share of a frame's layout stage -- the platform's figure for the stage also
    // contains the window manager's work, and the two could not be told apart (LatencyMeter).
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val began = System.nanoTime()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        LatencyMeter.treeMeasured(System.nanoTime() - began)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val began = System.nanoTime()
        super.onLayout(changed, left, top, right, bottom)
        LatencyMeter.treeLaidOut(System.nanoTime() - began)
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
