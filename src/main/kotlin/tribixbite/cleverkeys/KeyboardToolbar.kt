package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * A row of one-tap command buttons, sitting above the suggestion strip.
 *
 * Phase 1 of the HeliBoard gap analysis (`docs/HELIBOARD-GAP-ANALYSIS.md`): HeliBoard has a
 * toolbar and this codebase did not, but every command a toolbar would run already existed in
 * [CommandRegistry] and already dispatched through `Keyboard2View.onCustomShortSwipe`. This view
 * is therefore deliberately thin — it holds command *names*, renders each one's existing symbol,
 * and on tap hands a [ShortSwipeMapping] to a dispatcher that the caller points at
 * `onCustomShortSwipe`. All routing decisions (Editing vs Event vs executor vs typed) stay in the
 * one place that already makes them, so a toolbar button and a short-swipe binding can never
 * disagree about what a command does.
 *
 * ## Placement
 *
 * This is a sibling of `topPane` inside the input-view container, **not** a child of it. Two
 * reasons, both from reading `SuggestionBarPane`:
 * - `topPane` swaps its only child between the suggestion strip and a content pane, so anything
 *   inside it is removed the moment the clipboard or emoji pane opens.
 * - with word prediction and swipe typing both off, `topPane` is collapsed to height 0, so a
 *   toolbar living there would vanish exactly when the user has no suggestion strip and wants the
 *   buttons most.
 *
 * ## Sizing
 *
 * A [HorizontalScrollView] with `isFillViewport = true` around a weighted row: when the buttons
 * fit they stretch to divide the width evenly, and when they do not the row scrolls instead of
 * shrinking buttons below [BUTTON_MIN_WIDTH_DP]. No button is ever unreachable or too small to
 * hit.
 *
 * ## Theming
 *
 * Reuses the suggestion strip's own theme colours (`suggestionTextColor` /
 * `suggestionBackgroundColor`) and the shared `special_font.ttf` key font via
 * [Theme.getKeyFont], so the toolbar matches whatever theme is active with no new theme fields.
 */
class KeyboardToolbar(
    context: Context,
    private var theme: Theme?
) : HorizontalScrollView(context) {

    /** The weighted row the buttons live in. */
    private val row = LinearLayout(context)

    /** Set by the caller; a tap builds a mapping and hands it over. Null until wired. */
    private var dispatcher: ((ShortSwipeMapping) -> Unit)? = null

    /** The command names currently rendered, so [applyFromConfig] can skip needless rebuilds. */
    private var keys: List<String> = emptyList()

    init {
        // A stable id so PreferenceUIUpdateHandler can re-find this view after a preference
        // change instead of capturing a reference that a theme change would leave detached.
        id = R.id.keyboard_toolbar
        isHorizontalScrollBarEnabled = false
        isFillViewport = true

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        applyThemeColors()
    }

    /** How many buttons are currently rendered. Used by diagnostics and tests. */
    val keyCount: Int get() = keys.size

    /** The command names currently rendered, in order. */
    fun currentKeys(): List<String> = keys

    /**
     * Point taps at the keyboard view's existing command seam. Called once per (re)build of the
     * input-view hierarchy, mirroring how the suggestion bar is given its selection listener.
     */
    fun setCommandDispatcher(dispatch: (ShortSwipeMapping) -> Unit) {
        dispatcher = dispatch
    }

    /** Re-theme in place. A theme change normally rebuilds the whole hierarchy, so this is a guard. */
    fun setTheme(theme: Theme?) {
        this.theme = theme
        applyThemeColors()
        // Glyphs are drawn with the theme's colours, so existing buttons must be repainted.
        for (i in 0 until row.childCount) {
            (row.getChildAt(i) as? TextView)?.setTextColor(textColor())
        }
    }

    /**
     * Sync visibility and buttons to the current [config]. Safe to call on every preference
     * change: the row is only rebuilt when the parsed key list actually differs.
     */
    fun applyFromConfig(config: Config) {
        val parsed = ToolbarConfig.parse(config.toolbar_keys)
        visibility = if (config.toolbar_enabled && parsed.isNotEmpty()) View.VISIBLE else View.GONE
        if (parsed != keys) {
            keys = parsed
            rebuild()
        }
    }

    private fun rebuild() {
        row.removeAllViews()
        for (name in keys) {
            createButton(name)?.let { row.addView(it) }
        }
    }

    /**
     * One command button, or null when [name] no longer resolves. Labels and symbols both come
     * from the registry, so nothing here hardcodes a command's appearance.
     */
    private fun createButton(name: String): View? {
        val command = CommandRegistry.getByName(name) ?: return null
        val info = CommandRegistry.getDisplayInfo(name)

        val button = TextView(context).apply {
            text = info.displayText
            typeface = if (info.useKeyFont) Theme.getKeyFont(context) else Typeface.DEFAULT
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SP)
            minWidth = dp(BUTTON_MIN_WIDTH_DP)
            isSingleLine = true
            isClickable = true
            isFocusable = true
            // Localized command name — the registry already carries one per command, so the
            // toolbar adds no strings of its own and is translated wherever the command is.
            contentDescription = context.getString(command.nameRes)
            // View.tooltipText is API 26+ and minSdk is 24: compileSdk 34 lets this compile, but
            // an unguarded call throws NoSuchMethodError on API 24-25. contentDescription above
            // is the accessible label on every version; the tooltip is decoration.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tooltipText = context.getString(command.nameRes)
            }
            rippleBackground()
        }

        button.setOnClickListener { dispatch(name, info) }
        button.setOnLongClickListener {
            // A reminder of what the glyph means; the label itself is only 4 characters.
            Toast.makeText(context, command.nameRes, Toast.LENGTH_SHORT).show()
            true
        }

        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        )
        return button
    }

    /**
     * Run [name] through the same seam a short-swipe binding uses.
     *
     * `keyCode` and `direction` are identity fields for a persisted (key, direction) slot and
     * carry no meaning for a toolbar tap; `onCustomShortSwipe` only logs them. They are set to
     * fixed sentinels so a toolbar action is distinguishable in logcat from a real swipe.
     *
     * `displayText` is clamped because [ShortSwipeMapping] enforces a 4-character maximum, while
     * `CommandRegistry.getDisplayInfo` may return a longer declared symbol (the pinned-clipboard
     * entries declare multi-code-point emoji symbols). Without the clamp a valid registry command
     * could throw on construction and take the IME down.
     */
    private fun dispatch(name: String, info: CommandRegistry.CommandDisplayInfo) {
        val sink = dispatcher ?: return
        sink(
            ShortSwipeMapping(
                keyCode = TOOLBAR_KEY_CODE,
                direction = SwipeDirection.N,
                displayText = info.displayText.take(ShortSwipeMapping.MAX_DISPLAY_LENGTH),
                actionType = ActionType.COMMAND,
                actionValue = name,
                useKeyFont = info.useKeyFont
            )
        )
    }

    private fun applyThemeColors() {
        setBackgroundColor(theme?.suggestionBackgroundColor ?: 0)
    }

    private fun textColor(): Int = theme?.suggestionTextColor ?: 0xFF000000.toInt()

    /** Standard touch feedback, resolved from the platform theme so it follows light/dark. */
    private fun TextView.rippleBackground() {
        val outValue = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            setBackgroundResource(outValue.resourceId)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    companion object {
        /** Synthetic key code a toolbar tap reports as, so logcat can tell it from a real swipe. */
        const val TOOLBAR_KEY_CODE = "toolbar"

        /** Minimum button width/height, so a crowded row scrolls rather than shrinking below a hittable size. */
        private const val BUTTON_MIN_WIDTH_DP = 40

        /** Glyph size, matching the suggestion strip's visual weight. */
        private const val LABEL_TEXT_SP = 18f

        /**
         * The toolbar's row height, matching the 40 dp the suggestion strip uses so the two rows
         * read as one unit.
         */
        const val HEIGHT_DP = 40
    }
}
