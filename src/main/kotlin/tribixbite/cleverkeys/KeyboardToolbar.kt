package tribixbite.cleverkeys

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * A row of one-tap command buttons, sharing ONE 40 dp band above the keys with the suggestion
 * strip.
 *
 * Since 2026-11-28 (the owner's Gboard comparison) this is not a second row: the toolbar owns the
 * band when the strip has nothing to show, and steps aside while the strip carries words, a
 * message or the password toggle — the strip then draws this toolbar's first and last commands at
 * its own ends ([rowEnds]). Either way the keyboard is exactly one row taller than its keys, which
 * is also why its total height never changes between the two states.
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
 * - `topPane` is the strip's band, and in the one-row layout exactly one of the two occupies it
 *   at a time; a toolbar living inside `topPane` would be thrown out by the next pane swap.
 *
 * ## Sizing
 *
 * A [HorizontalScrollView] with `isFillViewport = true` around a weighted row: when the buttons
 * fit they stretch to divide the width evenly, and when they do not the row scrolls instead of
 * shrinking buttons below [BUTTON_MIN_WIDTH_DP]. No button is ever unreachable or too small to
 * hit.
 *
 * The older `compact` mode — two end buttons pushed to the edges with the middle hidden, as a
 * stand-in for Gboard's row — is kept for the `ToolbarCompactPolicy` tests but no longer driven
 * by the service: the strip itself now draws those ends, and this toolbar is either the whole row
 * or not on screen.
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

    /**
     * Flexible gap between the two end buttons in compact mode. It is inserted after the
     * first button on every rebuild, so the row's other children keep their original order.
     */
    private val compactSpacer = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        visibility = View.GONE
    }

    /** Set by the caller; a tap builds a mapping and hands it over. Null until wired. */
    private var dispatcher: ((ShortSwipeMapping) -> Unit)? = null

    /** The command names currently rendered, so [applyFromConfig] can skip needless rebuilds. */
    private var keys: List<String> = emptyList()

    /**
     * Opacity (0..100) applied to the bar background, mirroring `SuggestionBar.setOpacity`.
     *
     * The strip has always multiplied its background by the user's `suggestion_bar_opacity`
     * (default 80 %) while the toolbar painted the raw colour, so the two rows sat one shade
     * apart even on an opaque theme — visible as a faint band across the top. With a translucent
     * theme the mismatch is not faint, so both rows now follow the same rule.
     */
    private var opacity = 100

    /**
     * Collapsed-row mode (phase 4 part 6/7): the two END buttons while the suggestion strip
     * carries words.
     *
     * History, because it was switched off and on again. It was wired from phase 4 until
     * 2026-11-04, when the owner's report was "part of the keyboard disappears when I type
     * something" — at the time the strip's band ALSO collapsed whenever it was empty, so the
     * keyboard lost two rows at once as soon as typing began. It was taken off. On 2026-11-13 the
     * owner asked for it by name (*"when the suggestions are active the only tools visible are
     * the more tools sheet on the left and mic on the right"*), and the two-row collapse it was
     * blamed for is gone: the strip keeps its height always, and its middle carries the word
     * being typed. What is left is exactly Gboard's row: tools on the left, words in the middle,
     * mic on the right.
     *
     * Positions, not command names, decide which buttons survive ([ToolbarCompactPolicy]), so a
     * customised toolbar keeps its own first and last buttons.
     */
    private var compact = false

    /** Whether the user's config gives this toolbar anything to show. */
    private var enabledRow = false

    /**
     * True while the strip is carrying the row and this toolbar must not take a band of its
     * own — the one-row layout. See [setHiddenForStrip].
     */
    private var hiddenForStrip = false

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
        // Icon buttons carry a tint rather than a text colour, so both shapes are handled here.
        for (i in 0 until row.childCount) {
            when (val child = row.getChildAt(i)) {
                is TextView -> child.setTextColor(textColor())
                is ImageView -> child.imageTintList = ColorStateList.valueOf(textColor())
                // Icon buttons are an ImageView inside a FrameLayout wrapper (the wrapper owns
                // the ripple and the click), so the tint lives one level down. The mic's circle
                // is the wrapper's own background, and its glyph is the bar's background.
                is FrameLayout -> {
                    val glyph = child.getChildAt(0) as? ImageView
                    if (child.tag == "voice_typing") {
                        glyph?.imageTintList = ColorStateList.valueOf(micGlyphColor())
                        (child.background as? android.graphics.drawable.GradientDrawable)
                            ?.setColor(micFillColor())
                    } else {
                        glyph?.imageTintList = ColorStateList.valueOf(textColor())
                    }
                }
            }
        }
    }

    /**
     * Sync visibility and buttons to the current [config]. Safe to call on every preference
     * change: the row is only rebuilt when the parsed key list actually differs.
     */
    fun applyFromConfig(config: Config) {
        val parsed = ToolbarConfig.parse(config.toolbar_keys)
        enabledRow = config.toolbar_enabled && parsed.isNotEmpty()
        if (parsed != keys) {
            keys = parsed
            rebuild()
        }
        applyVisibility()
    }

    /** Whether the toolbar has anything to show at all, whatever the strip is doing. */
    fun canOwnRow(): Boolean = enabledRow && keys.isNotEmpty()

    /**
     * One-row layout (2026-11-28): the toolbar and the suggestion strip are the same 40 dp
     * band, never two stacked ones. When the strip is carrying the row — words, a message,
     * the password toggle — the toolbar steps aside entirely and its two end commands are
     * drawn inside the strip instead ([rowEnds]). When the strip has nothing, this row is the
     * band, with the full set of commands the user configured.
     *
     * Kept as a separate flag from [compact]: compact was the two-row approximation of
     * Gboard's row (ends pushed apart with the middle buttons hidden, but still a second
     * row above the strip). The owner asked for the single row on 2026-11-25.
     */
    fun setHiddenForStrip(hidden: Boolean) {
        if (hiddenForStrip == hidden) return
        hiddenForStrip = hidden
        applyVisibility()
    }

    private fun applyVisibility() {
        visibility = if (enabledRow && !hiddenForStrip) View.VISIBLE else View.GONE
    }

    /**
     * The commands drawn at the strip's two ends while this toolbar is hidden: the first and
     * last of the configured list, in order (positions, not names, exactly like
     * [ToolbarCompactPolicy]). Empty when the toolbar is off or has a single command.
     */
    fun rowEnds(): List<RowEnd> {
        // A toolbar the user switched off lends nothing to the strip either: its commands are
        // meant to be gone from this keyboard.
        if (!enabledRow || keys.isEmpty()) return emptyList()
        val names = if (keys.size == 1) listOf(keys.first()) else listOf(keys.first(), keys.last())
        return names.map { name ->
            val command = CommandRegistry.getByName(name)
            RowEnd(
                name = name,
                iconRes = toolbarIcon(name),
                label = command?.nameRes?.let { context.getString(it) } ?: name
            )
        }
    }

    /** One command drawn at an edge of the suggestion row. */
    data class RowEnd(val name: String, val iconRes: Int, val label: String)

    private fun rebuild() {
        row.removeAllViews()
        for (name in keys) {
            createButton(name)?.let { row.addView(it) }
        }
        // Between the first and second button, so a visible spacer pushes the last button to
        // the opposite edge in compact mode without disturbing normal-mode button order.
        if (row.childCount > 1) row.addView(compactSpacer, 1)
        applyCompact()
    }

    /**
     * Keep the end buttons while [enabled], which is what Gboard shows once the strip is
     * carrying words. `setCompactMode` early-returns when the state is unchanged, so this is
     * called on occupancy transitions, not on every keystroke.
     */
    fun setCompactMode(enabled: Boolean) {
        if (compact == enabled) return
        compact = enabled
        applyCompact()
    }

    private fun applyCompact() {
        val buttons = ArrayList<View>(row.childCount)
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child !== compactSpacer) buttons.add(child)
        }

        var changed = false
        val buttonWeight = ToolbarCompactPolicy.buttonWeight(buttons.size, compact)
        for ((index, child) in buttons.withIndex()) {
            val visible = ToolbarCompactPolicy.buttonVisible(index, buttons.size, compact)
            val targetVisibility = if (visible) View.VISIBLE else View.GONE
            if (child.visibility != targetVisibility) {
                child.visibility = targetVisibility
                changed = true
            }
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.weight != buttonWeight) {
                lp.weight = buttonWeight
                changed = true
            }
        }

        val spacerVisibility =
            if (ToolbarCompactPolicy.spacerVisible(buttons.size, compact)) View.VISIBLE else View.GONE
        if (compactSpacer.visibility != spacerVisibility) {
            compactSpacer.visibility = spacerVisibility
            changed = true
        }
        val spacerLp = compactSpacer.layoutParams as LinearLayout.LayoutParams
        val spacerWeight = if (spacerVisibility == View.VISIBLE) 1f else 0f
        if (spacerLp.weight != spacerWeight) {
            spacerLp.weight = spacerWeight
            changed = true
        }
        // Mutating LayoutParams.weight needs a layout pass; visibility changes do not always
        // schedule one when the view is inside a HorizontalScrollView.
        if (changed) row.requestLayout()
    }

    /**
     * An icon button on Gboard's 24 dp glyph grid, tinted with the strip's text colour.
     *
     * The wrapper owns the ripple, the click, the long-press label and the 40 dp minimum hit
     * target. The [ImageView] is deliberately NOT clickable or focusable: a clickable child
     * consumes ACTION_UP before a parent OnClickListener can fire, which made every icon on
     * the default toolbar a dead button in part 6. The child also used to carry 8 dp of
     * horizontal padding inside a 22 dp box, leaving only 6 dp for the glyph itself.
     */
    private fun createIconButton(name: String, nameRes: Int, iconRes: Int): View {
        val isMic = name == "voice_typing"
        val size = dp(if (isMic) MIC_ICON_SIZE_DP else ICON_SIZE_DP)
        val button = ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(if (isMic) micGlyphColor() else textColor())
            // The wrapper is the interactive view. A clickable/focusable child would swallow
            // the tap and expose a duplicate, label-less node to TalkBack.
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = null
        }
        val label = context.getString(nameRes)
        val wrapper = FrameLayout(context).apply {
            tag = name
            isClickable = true
            isFocusable = true
            minimumWidth = dp(BUTTON_MIN_WIDTH_DP)
            if (isMic) {
                // Gboard's mic sits in a filled circle (owner's screenshots): secondary
                // ink as the fill, bar background as the glyph.
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(micFillColor())
                }
                val inset = dp(4)
                setPadding(inset, inset, inset, inset)
            } else {
                rippleBackground()
            }
            addView(button, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            setOnClickListener { dispatch(name, CommandRegistry.getDisplayInfo(name)) }
            contentDescription = label
        }
        nameOnLongPress(wrapper, label)
        wrapper.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        )
        return wrapper
    }

    /**
     * Name a button when it is long-pressed.
     *
     * From API 26 the platform does this: `tooltipText` shows a small label next to the view.
     * It does so only when no long-click listener claims the press, and until 2026-11-11 one
     * did, to show a Toast. A keyboard's toasts are dropped on Android 13+ (see
     * ImeToastBanDriftTest), so there a long-press showed nothing: the toast was suppressed and
     * the tooltip was pre-empted. Below API 26 there is no tooltip (`View.tooltipText` does not
     * exist; calling it throws NoSuchMethodError) and toasts still work, so the Toast is kept
     * there and only there.
     */
    private fun nameOnLongPress(view: View, label: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.tooltipText = label
        } else {
            view.setOnLongClickListener {
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun createButton(name: String): View? {
        val command = CommandRegistry.getByName(name) ?: return null
        val info = CommandRegistry.getDisplayInfo(name)
        val iconRes = toolbarIcon(name)
        // The command name stays on the view as a lightweight diagnostic tag. Compact mode
        // uses positions, so a custom toolbar no longer depends on particular command names.
        if (iconRes != 0) return createIconButton(name, command.nameRes, iconRes)

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
            // It is the accessible label on every version; the long-press label is decoration.
            contentDescription = context.getString(command.nameRes)
            rippleBackground()
            tag = name
        }

        button.setOnClickListener { dispatch(name, info) }
        // A reminder of what the glyph means; the label itself is only 4 characters.
        nameOnLongPress(button, context.getString(command.nameRes))

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

    /**
     * Run a command through this toolbar's own dispatcher, exactly as one of its buttons does.
     * The strip's end buttons call this ([rowEnds]), so a tap on the grid in the strip and a tap
     * on the grid in the toolbar can never route differently.
     */
    fun dispatchCommand(name: String) {
        dispatch(name, CommandRegistry.getDisplayInfo(name))
    }

    /** Same contract as `SuggestionBar.setOpacity`: clamps, ignores no-op updates. */
    fun setOpacity(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == opacity) return
        opacity = clamped
        applyThemeColors()
    }

    private fun applyThemeColors() {
        val bg = theme?.suggestionBackgroundColor ?: 0
        // KeyFillAlpha multiplies the colour's own alpha, so a translucent strip colour survives
        // and an opaque one keeps exactly the percentage it always had.
        setBackgroundColor(KeyFillAlpha.of(bg, opacity * 255 / 100))
    }

    private fun textColor(): Int = theme?.suggestionTextColor ?: 0xFF000000.toInt()

    /** Fill of the mic's circle: the strip's secondary ink, as on Gboard. */
    private fun micFillColor(): Int = theme?.suggestionSecondaryTextColor ?: 0xFF5F6368.toInt()

    /** Glyph inside the mic's circle: the bar background, so it reads as a cut-out. */
    private fun micGlyphColor(): Int = theme?.suggestionBackgroundColor ?: 0xFFFFFFFF.toInt()

    /**
     * Standard touch feedback, resolved from the platform theme so it follows light/dark.
     * Declared on View rather than TextView because icon buttons are ImageView inside a
     * FrameLayout wrapper, and all three shapes need the same ripple.
     */
    private fun View.rippleBackground() {
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
        /** Icon size on the 40 dp row: Gboard's 24 dp glyph box, with no internal padding. */
        /**
         * Glyph box. Material Symbols is drawn for a 24 dp optical size, but Gboard places
         * its icons at ~22 dp inside the same row, so 24 dp of Symbols reads a shade heavier
         * than Gboard's row. 22 dp inside the 40 dp hit target matches what the owner's
         * screenshot shows.
         */
        private const val ICON_SIZE_DP = 22

        /**
         * Glyph size inside the mic's filled circle. The circle is 40 dp; the glyph sits a
         * little smaller than a bare icon so the fill stays visible around it.
         */
        const val MIC_ICON_SIZE_DP = 20

        /**
         * Command name to drawable. A command with no entry keeps its font glyph, which is the
         * right answer for the ~230 commands that have no icon and are not on a default toolbar.
         */
        private fun toolbarIcon(name: String): Int = when (name) {
            "more_tools" -> R.drawable.ic_tool_more
            "undo" -> R.drawable.ic_tool_undo
            "redo" -> R.drawable.ic_tool_redo
            "select_word" -> R.drawable.ic_tool_select
            "switch_clipboard" -> R.drawable.ic_tool_clipboard
            "config" -> R.drawable.ic_tool_settings
            "switch_gif" -> R.drawable.ic_tool_gif
            "voice_typing" -> R.drawable.ic_tool_mic
            else -> 0
        }

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
