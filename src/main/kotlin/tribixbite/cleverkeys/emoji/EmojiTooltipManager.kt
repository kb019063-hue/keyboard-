package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Manages tooltip popup for displaying emoji names on long-press.
 *
 * Uses PopupWindow instead of Toast for reliable display within IME context:
 * - PopupWindow is anchored to views within the IME window
 * - No queuing issues like Toast
 * - Full control over positioning and styling
 * - Works reliably across all Android API levels (21+)
 *
 * Creates views programmatically to avoid resource lookup issues with themed contexts.
 *
 * @since v1.2.6
 */
class EmojiTooltipManager(context: Context) {

    private val tooltipView: LinearLayout
    private val emojiCharView: TextView
    private val emojiNameView: TextView
    private val popupWindow: PopupWindow

    init {
        // Create tooltip view programmatically to avoid resource lookup issues
        val density = context.resources.displayMetrics.density
        val padding = (8 * density).toInt()
        val cornerRadius = 8 * density

        // Create background drawable
        val background = GradientDrawable().apply {
            setColor(0xDD333333.toInt())
            this.cornerRadius = cornerRadius
        }

        // Create container
        tooltipView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            this.background = background
        }

        // Create emoji char text view
        emojiCharView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
        }
        tooltipView.addView(emojiCharView)

        // Create emoji name text view
        emojiNameView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            maxWidth = (200 * density).toInt()
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val marginStart = (8 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(marginStart, 0, 0, 0)
            }
        }
        tooltipView.addView(emojiNameView)

        popupWindow = PopupWindow(
            tooltipView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // Non-interactive tooltip
            isFocusable = false
            isTouchable = false
            isOutsideTouchable = false
            // Elevation for shadow on API 21+
            elevation = 8f
        }
    }

    /**
     * Show tooltip anchored above the given view.
     *
     * @param anchor The view to anchor the tooltip to (the emoji cell)
     * @param emojiChar The emoji character to display
     * @param emojiName The emoji name to display
     */
    fun show(anchor: View, emojiChar: String, emojiName: String) {
        // Update content
        emojiCharView.text = emojiChar
        emojiNameView.text = emojiName

        // Measure tooltip to calculate position
        tooltipView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val tooltipWidth = tooltipView.measuredWidth
        val tooltipHeight = tooltipView.measuredHeight

        // Dismiss any existing popup first
        if (popupWindow.isShowing) {
            popupWindow.dismiss()
        }

        // Use showAsDropDown for reliable positioning within IME window
        // showAtLocation uses screen coords which don't work well in keyboard context
        try {
            // Always drawing the bubble above and centred on the cell clipped it: the top row has
            // the pane's search bar above it, and the end cells of every row overhang the side
            // edges. EmojiTooltipPlacement owns the flip-or-slide rule; this call only supplies
            // the rectangles and the offsets the platform expects.
            val placement = placeInsidePane(anchor, tooltipWidth, tooltipHeight)
            if (placement != null) {
                val anchorLoc = IntArray(2)
                anchor.getLocationInWindow(anchorLoc)
                val offsetX = placement.x - anchorLoc[0]
                val offsetY = EmojiTooltipPlacement.dropDownOffsetY(
                    anchorLoc[1], anchor.height, placement.y
                )
                popupWindow.showAsDropDown(anchor, offsetX, offsetY, Gravity.TOP or Gravity.START)
            } else {
                // No window bounds to reason about (detached, or a bare test host): keep the
                // pre-existing above-and-centred behaviour rather than showing nothing.
                val offsetX = (anchor.width - tooltipWidth) / 2
                val offsetY = -anchor.height - tooltipHeight - 8
                popupWindow.showAsDropDown(anchor, offsetX, offsetY, Gravity.TOP or Gravity.START)
            }
        } catch (e: Exception) {
            android.util.Log.e("EmojiTooltipManager", "Failed to show tooltip", e)
        }
    }

    /**
     * The window rectangle the bubble must stay inside, or null when there is none to ask.
     *
     * `getWindowVisibleDisplayFrame` is the visible area of the window the anchor lives in — for
     * an IME that is the keyboard surface, which is exactly the region Gboard keeps its bubble
     * within, and it already excludes the navigation bar and system insets.
     */
    private val visibleFrame = android.graphics.Rect()

    private fun placeInsidePane(anchor: View, width: Int, height: Int): EmojiTooltipPlacement.Result? {
        if (!anchor.isAttachedToWindow) return null
        @Suppress("DEPRECATION")
        if (!anchor.getWindowVisibleDisplayFrame(visibleFrame)) return null
        if (visibleFrame.isEmpty) return null

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        return EmojiTooltipPlacement.place(
            anchorX = loc[0],
            anchorY = loc[1],
            anchorWidth = anchor.width,
            anchorHeight = anchor.height,
            width = width,
            height = height,
            boundsLeft = visibleFrame.left,
            boundsTop = visibleFrame.top,
            boundsRight = visibleFrame.right,
            boundsBottom = visibleFrame.bottom,
        )
    }

    /**
     * Dismiss the tooltip if showing.
     */
    fun dismiss() {
        if (popupWindow.isShowing) {
            try {
                popupWindow.dismiss()
            } catch (e: Exception) {
                android.util.Log.w("EmojiTooltipManager", "Error dismissing popup", e)
            }
        }
    }

    /**
     * Check if tooltip is currently showing.
     */
    fun isShowing(): Boolean = popupWindow.isShowing
}
