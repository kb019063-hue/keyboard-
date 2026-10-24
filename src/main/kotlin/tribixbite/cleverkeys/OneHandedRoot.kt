package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The IME window's root while one-handed mode may be active: it hosts whatever the keyboard wants
 * to show, scales it toward one edge, and puts a stop control in the gap that opens up.
 *
 * Phase 2 of the HeliBoard gap analysis (design: `docs/PHASE2-ONE-HANDED-DESIGN.md`).
 *
 * ## Why a wrapper is needed at all
 *
 * `PredictionViewSetup` returns `inputViewContainer ?: keyboardView` as the view handed to
 * `setInputView`, so the root *is* the thing that has to shrink — there is no parent left to hang
 * a gap control on. HeliBoard reaches the same place from the other direction: its
 * `KeyboardWrapperView` is a `FrameLayout` around the keyboard. The difference is that HeliBoard
 * re-measures the keyboard narrower and so needs a custom `onLayout` to position both keyboard and
 * buttons; this only transforms, so the stock [FrameLayout] layout is enough and the stop control
 * is placed with `gravity` like any other child.
 *
 * The window itself stays full width and this root has no background, so the gap is transparent and
 * shows the app behind — the point of one-handed mode. `WindowLayoutUtils` is unaffected: it
 * configures `android.R.id.inputArea`, the *platform* container that this root sits inside.
 *
 * ## Why the transform and not a re-measure
 *
 * Scaling leaves layout bounds untouched, so key geometry, hit-testing, the swipe engine and
 * [KeyboardDimensionsHelper] all keep working unchanged, and
 * `ViewGroup.dispatchTransformedTouchEvent` applies the inverse matrix before an event reaches the
 * child — a tap on a visually-shrunk key arrives as the coordinate it would have unscaled. See
 * [OneHandedConfig] for the arithmetic, which is unit-tested separately.
 *
 * ## Lifetime
 *
 * Created once by the service and kept for the life of the IME. [host] is called on every
 * `setInputView` — theme change, pane open/close, minimize/expand — and swaps the child rather than
 * rebuilding the root, which is why holding a direct reference here is safe even though
 * `PreferenceUIUpdateHandler` deliberately refuses to hold references to the views *inside* it.
 */
class OneHandedRoot(
    context: Context,
    /**
     * Supplies the CURRENT theme, read at paint time. A provider rather than a constructor value
     * for the reason `PreferenceUIUpdateHandler`'s header gives: a theme change replaces the
     * keyboard view, and this root deliberately outlives it, so a captured [Theme] would keep
     * colouring the stop control with the palette the user just switched away from.
     */
    private val themeProvider: (() -> Theme?)? = null
) : FrameLayout(context) {

    /** The view currently being shown, scaled when one-handed mode is on. */
    private var content: View? = null

    private var theme: Theme? = null
    private var enabled = OneHandedConfig.DEFAULT_ENABLED
    private var side = OneHandedConfig.parseSide(OneHandedConfig.DEFAULT_SIDE)
    private var scalePercent = OneHandedConfig.DEFAULT_SCALE

    /**
     * The exit control drawn in the gap. It is a sibling of [content], never a child, so it is not
     * scaled with it and stays a comfortable target.
     *
     * Its glyph comes from a resource rather than a literal: `HardcodedUiStringTest` treats a
     * string assigned to `text` as UI prose that must live in resources, and a symbol shown
     * identically in every locale belongs in a `translatable="false"` string the same way
     * `app_name` does.
     */
    private val stopButton = TextView(context)

    /** Set by the service; invoked when the stop control is tapped. */
    var onStopOneHanded: (() -> Unit)? = null

    /**
     * Show content at full width even while one-handed mode is on. The service sets this for the
     * minimized bar and floating button (gh #175): shrinking those would make them harder to tap,
     * which is the opposite of what one-handed mode is for.
     */
    var scaleSuppressed: Boolean = false
        set(value) {
            field = value
            applyState()
        }

    init {
        stopButton.gravity = Gravity.CENTER
        stopButton.textSize = STOP_TEXT_SP
        stopButton.setText(R.string.one_handed_stop_symbol)
        stopButton.contentDescription = context.getString(R.string.one_handed_stop_desc)
        stopButton.visibility = View.GONE
        stopButton.setOnClickListener { onStopOneHanded?.invoke() }
    }

    /** Re-theme in place. A theme change normally re-hosts the content, so this is a guard. */
    fun setTheme(theme: Theme?) {
        this.theme = theme
        applyThemeColors()
    }

    /**
     * Make [newContent] the hosted child and return this root, ready to be passed to
     * `super.setInputView`. Idempotent: re-hosting the view that is already attached only refreshes
     * the transform, because `setInputView` runs on every theme change, pane swap and
     * minimize/expand and must not nest a root inside a root.
     */
    fun host(newContent: View): OneHandedRoot {
        if (content === newContent && newContent.parent === this) {
            applyState()
            return this
        }
        (newContent.parent as? ViewGroup)?.removeView(newContent)
        removeAllViews()
        content = newContent
        addView(newContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Gravity supplied here, not left to applyState: assigning layoutParams calls
        // requestLayout, and applyState runs from inside onLayout, so a first layout pass that had
        // to correct the gravity would request another pass from within the one it is in.
        addView(stopButton, LayoutParams(dp(STOP_WIDTH_DP), dp(STOP_HEIGHT_DP), stopGravity()))
        applyState()
        return this
    }

    /** Apply a new one-handed state. Safe to call before the first layout; [onLayout] reapplies. */
    fun setState(
        enabled: Boolean,
        side: OneHandedConfig.Side,
        scalePercent: Int,
        theme: Theme? = this.theme
    ) {
        this.enabled = enabled
        this.side = side
        this.scalePercent = OneHandedConfig.clampScale(scalePercent)
        this.theme = theme
        applyState()
    }

    /** Whether the keyboard is currently shown shrunken. Used by diagnostics and tests. */
    val isActive: Boolean get() = enabled && !scaleSuppressed && content != null

    /**
     * Sync to [config]: the enable flag, which edge to hug, and the scale. Mirrors
     * `KeyboardToolbar.applyFromConfig` so `PreferenceUIUpdateHandler` can drive both the same way.
     *
     * Cheap enough to call on every preference change: assigning `scaleX`/`pivotX` invalidates the
     * render node but does not request a layout, and [applyState] writes the stop control's
     * layoutParams only when something actually differs.
     */
    fun applyFromConfig(config: Config) {
        setState(
            enabled = config.one_handed_enabled,
            side = OneHandedConfig.parseSide(config.one_handed_side),
            scalePercent = config.one_handed_scale
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // pivotX and the gap width both depend on the content's laid-out width, which is 0 until
        // the first layout completes, so the transform has to be reapplied here.
        applyState()
    }

    private fun applyState() {
        val c = content ?: return
        if (!enabled || scaleSuppressed) {
            c.scaleX = 1f
            c.pivotX = 0f
            stopButton.visibility = View.GONE
            return
        }
        val width = c.width
        // scaleX/pivotX are render-node properties: assigning them invalidates but does NOT call
        // requestLayout, so reapplying from onLayout is safe.
        c.scaleX = OneHandedConfig.scaleFraction(scalePercent)
        c.pivotX = OneHandedConfig.pivotXFor(side, width)

        val gap = OneHandedConfig.gapWidth(scalePercent, width)
        val wantW = dp(STOP_WIDTH_DP)
        val wantH = dp(STOP_HEIGHT_DP)
        // At MAX_SCALE the gap is zero and there is nowhere to put the control; hide it rather than
        // draw it over the keys. The toolbar command and Settings both still exit one-handed mode.
        if (width <= 0 || gap < wantW) {
            stopButton.visibility = View.GONE
            return
        }
        val wantGravity = stopGravity()
        // Assign layoutParams only when something actually differs: assigning calls requestLayout,
        // and applyState runs from inside onLayout, so an unconditional write would request a
        // layout from within a layout pass.
        val lp = stopButton.layoutParams as? LayoutParams
        if (lp == null || lp.width != wantW || lp.height != wantH || lp.gravity != wantGravity) {
            stopButton.layoutParams = LayoutParams(wantW, wantH, wantGravity)
        }
        stopButton.visibility = View.VISIBLE
        applyThemeColors()
    }

    private fun applyThemeColors() {
        // The control floats over whatever app is behind the gap, so it needs the keyboard's own
        // background rather than transparency to stay legible. The provider wins when it can
        // answer; [theme] is what an explicit [setTheme] left behind, and the literals are the
        // last resort before the first keyboard view exists.
        val t = themeProvider?.invoke() ?: theme
        stopButton.setBackgroundColor(t?.suggestionBackgroundColor ?: 0xFF202020.toInt())
        stopButton.setTextColor(t?.suggestionTextColor ?: 0xFFFFFFFF.toInt())
    }

    /**
     * Where the stop control sits: on the gap edge, vertically centred.
     *
     * Absolute LEFT/RIGHT, not START/END — the setting is about which *physical* edge the keyboard
     * hugs, and mirroring it in an RTL locale would put the gap on the side the user did not pick.
     * HeliBoard annotates the same choice `@SuppressLint("RtlHardcoded")`.
     */
    @SuppressLint("RtlHardcoded")
    private fun stopGravity(): Int =
        (if (OneHandedConfig.gapSide(side) == OneHandedConfig.Side.LEFT) {
            Gravity.LEFT
        } else {
            Gravity.RIGHT
        }) or Gravity.CENTER_VERTICAL

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    companion object {
        /** Wide enough to hit with a thumb, narrow enough to fit a 70%-scale gap on a small phone. */
        private const val STOP_WIDTH_DP = 40
        private const val STOP_HEIGHT_DP = 56
        private const val STOP_TEXT_SP = 18f
    }
}
