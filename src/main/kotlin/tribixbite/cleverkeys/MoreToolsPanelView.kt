package tribixbite.cleverkeys

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The tools panel that the toolbar's grid key opens — a keyboard surface, not a dialog box.
 *
 * ## What was here before, and why it was thrown away
 *
 * The old builder in `CleverKeysService` (retired 2026-11-09, still described in
 * `docs/TOOLS-PANEL-2026-11-09.md`) constructed the five tools with
 * `AlertDialog.setMultiChoiceItems`: a stock list of five checkboxes in a floating card. That is
 * an Android *settings form*. Gboard's panel — the thing the owner keeps comparing against — is a
 * launcher drawn on the keyboard: four round icon tiles to a row, a word under each, on the same
 * surface as the keys, dismissed by its own X or by the grid key again. A checkbox list cannot be
 * restyled into that; it has no icons, no grid, no surface, and it puts the tools in a box that
 * floats over the app instead of under the thumb.
 *
 * So this view replaces it, in the slot the emoji / clipboard / GIF panes already use: a child of
 * `contentPaneContainer`, shown by swapping `topPane` to content-pane mode. That reuse is the
 * point — the panel is then the keyboard's own surface at the keyboard's own width, it cannot
 * cover the keys it was opened from, and it inherits the pane height the user already controls
 * (`clipboard_pane_height_percent`, default 30 % of the screen).
 *
 * ## Look
 *
 * Everything comes from the active [Theme], exactly like [KeyboardToolbar] does, so a theme change
 * needs no new field and no new string:
 * - field: `colorKeyboardBackground` — the same colour the keys sit on, so the panel reads as part
 *   of the keyboard rather than a card laid over it;
 * - tiles: `colorKey` at rest and `colorKeyActivated` when the switch is on, which is the same
 *   pressed/active pair a key uses (Gboard lights a switch's tile the same way);
 * - glyphs: `labelColor`; captions: `labelColor` when lit, `subLabelColor` when not, mirroring the
 *   way a key's main label outranks its hint;
 * - ripple: the platform's `selectableItemBackground`, as on the toolbar.
 *
 * ## Sizing
 *
 * The grid is [MoreToolsPanel.COLUMNS] wide and scrolls vertically if a user's pane percentage is
 * too short for three rows — the tile never shrinks below a 48 dp touch target, because a tool
 * panel that cannot be hit is the same defect as one that does not open. Captions are capped at
 * two lines; Gboard's own labels are short for exactly this reason.
 *
 * ## Wiring
 *
 * Constructing this view performs no action. [bind] hands it the state and one callback; the
 * caller maps a [MoreToolsPanel.Key] to whatever that tool does (the service's existing toggle
 * setters for switches, the existing pane events for launchers). Nothing here knows about
 * `Config`, events or the service, which is what lets [MoreToolsPanel] and this class's geometry
 * be tested off-device.
 */
class MoreToolsPanelView(context: Context, private val theme: Theme?) : LinearLayout(context) {

    /** Re-invoked after every tap so a switch's lit state and caption track the real value. */
    private var onToolTap: ((MoreToolsPanel.Key) -> Unit)? = null

    /** Grid rebuilt from this on every [bind]; null until the caller supplies one. */
    private var state: MoreToolsPanel.State? = null

    /** The X in the header. Set by [setOnClose] before the panel is shown. */
    private var onClose: (() -> Unit)? = null

    private val header = LinearLayout(context)
    private val title = TextView(context)
    private val grid = LinearLayout(context)
    private val scroll = android.widget.ScrollView(context)

    init {
        orientation = VERTICAL
        // The IME window background is transparent and this view is the panel's whole surface,
        // so it must paint the field colour itself (same contract as res/layout/keyboard.xml).
        setBackgroundColor(fieldColor())
        buildHeader()
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(
            grid,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT_DP))
        )
        addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
        grid.orientation = VERTICAL
    }

    /**
     * Whether this panel was built with [candidate] (the same object, not an equal one). Every
     * colour here is read from that Theme, so a panel built for another theme cannot be
     * re-bound into the new one: the host builds a fresh panel instead.
     */
    fun isBuiltFor(candidate: Theme?): Boolean = theme === candidate

    /**
     * Show [state] and route taps to [onTap]. Safe to call repeatedly: the grid is rebuilt from
     * scratch, so a toggle that flipped the value of one switch cannot leave a stale tile behind.
     */
    fun bind(state: MoreToolsPanel.State, onTap: (MoreToolsPanel.Key) -> Unit) {
        this.state = state
        this.onToolTap = onTap
        rebuildGrid()
    }

    /**
     * The panel's own dismiss affordance. The service passes its close action here rather than
     * overloading one of the nine tools with double duty.
     */
    fun setOnClose(action: () -> Unit) {
        onClose = action
    }

    private fun buildHeader() {
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(HEADER_PADDING_DP), 0, dp(HEADER_PADDING_DP), 0)

        title.text = context.getString(R.string.more_tools_sheet_title)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SP)
        title.setTextColor(labelColor())
        title.typeface = Typeface.DEFAULT_BOLD
        title.isSingleLine = true
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        header.addView(
            title,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL }
        )

        // A separate view for the glyph so the 48 dp hit target is the wrapper, not the icon —
        // the same rule KeyboardToolbar learned the hard way (a clickable child eats ACTION_UP).
        val glyph = ImageView(context).apply {
            setImageResource(R.drawable.ic_tool_close)
            imageTintList = ColorStateList.valueOf(subLabelColor())
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val wrapper = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            minimumWidth = dp(TOUCH_MIN_DP)
            minimumHeight = dp(TOUCH_MIN_DP)
            rippleBackground()
            addView(glyph, FrameLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP), Gravity.CENTER))
            setOnClickListener { onClose?.invoke() }
        }
        header.addView(wrapper, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun rebuildGrid() {
        val current = state ?: return
        grid.removeAllViews()
        for (row in MoreToolsPanel.gridRows(current)) {
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                // Equal weight per cell is what keeps every tile the same width even when the
                // last row carries only one tool and three blanks.
                gravity = Gravity.TOP
            }
            for (cell in row) {
                if (cell == null) {
                    // A blank cell carries the same weight and height as a real one, so the
                    // tools above keep their width on the ragged last row.
                    rowView.addView(
                        View(context),
                        LayoutParams(0, dp(TILE_HEIGHT_DP), 1f)
                    )
                } else {
                    rowView.addView(
                        createTile(cell),
                        LayoutParams(0, dp(TILE_HEIGHT_DP), 1f)
                    )
                }
            }
            grid.addView(
                rowView,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(TILE_HEIGHT_DP))
            )
        }
    }

    /**
     * One tool cell: a rounded tile with the tool's glyph and, under it, the tool's name.
     *
     * The whole cell is the touch target and owns the click/ripple — the `ImageView` and the
     * `TextView` inside are explicitly not clickable, for the same ACTION_UP reason as the
     * toolbar's icon buttons.
     */
    private fun createTile(cell: MoreToolsPanel.Cell): View {
        val isSwitch = MoreToolsPanel.isSwitch(cell.key)
        val lit = isSwitch && cell.checked

        val stateWord = if (isSwitch) {
            " — " + context.getString(
                if (cell.checked) R.string.keyboard_tools_state_on else R.string.keyboard_tools_state_off
            )
        } else {
            ""
        }

        val tile = FrameLayout(context).apply {
            // One background, chosen once: a lit switch takes the KEY-PRESSED colour, which is
            // exactly the visual Gboard gives an active tool, while keeping the ripple that an
            // unlit tile has. Setting a ripple after the fill would have overwritten it.
            background = if (lit) {
                pressedRippleBackground() ?: tileBackground(true)
            } else {
                rippleBackground() ?: tileBackground(false)
            }
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(labelRes(cell.key)) + stateWord
            setOnClickListener { onToolTap?.invoke(cell.key) }
            setPadding(dp(TILE_PADDING_DP), dp(TILE_PADDING_DP), dp(TILE_PADDING_DP), dp(TILE_PADDING_DP))
        }

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
        }
        val glyph = ImageView(context).apply {
            setImageResource(iconRes(cell.key))
            imageTintList = ColorStateList.valueOf(if (lit) labelColor() else glyphColor())
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = false
            isFocusable = false
        }
        val caption = TextView(context).apply {
            text = context.getString(labelRes(cell.key))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CAPTION_TEXT_SP)
            setTextColor(if (lit) labelColor() else subLabelColor())
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = false
            isFocusable = false
        }
        column.addView(glyph, LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        column.addView(
            caption,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(CAPTION_GAP_DP)
            }
        )
        tile.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        return tile
    }

    /**
     * Gboard's panel tiles are filled rounded rectangles when idle and take the key's pressed
     * colour when their switch is on. Radius follows the theme's own key corner so the panel and
     * the keyboard do not disagree about how round a key is.
     */
    private fun tileBackground(lit: Boolean): GradientDrawable {
        val fill = when {
            lit -> themeColor({ colorKeyActivated }, labelColor())
            else -> themeColor({ colorKey }, 0)
        }
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = theme?.keyBorderRadius?.takeIf { it > 0f } ?: dp(TILE_RADIUS_DP).toFloat()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Theme colours, each with a fallback that stays legible if a theme leaves one unset (0).
    // ---------------------------------------------------------------------------------------

    private fun themeColor(pick: Theme.() -> Int, fallback: Int): Int {
        val theme = this.theme ?: return fallback
        val value = pick(theme)
        return if (value != 0) value else fallback
    }

    private fun fieldColor(): Int = themeColor({ colorKeyboardBackground }, 0xCC141619.toInt())

    private fun labelColor(): Int = themeColor({ labelColor }, 0xFFFFFFFF.toInt())

    private fun subLabelColor(): Int = themeColor({ subLabelColor }, 0x99FFFFFF.toInt())

    /** Glyph colour on an unlit tile: same as the caption's, so icon and word agree. */
    private fun glyphColor(): Int = subLabelColor()

    private fun View.rippleBackground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            setBackgroundResource(outValue.resourceId)
            return background
        }
        return null
    }

    /**
     * The ripple a KEY gets when pressed, used as the fill of a switch that is on. Android has no
     * "selectable item background + my colour" primitive, so the ripple drawable is layered over
     * the theme's activated-key fill — the same pair `Keyboard2View` uses for a pressed key.
     */
    private fun pressedRippleBackground(): android.graphics.drawable.Drawable? {
        val fill = tileBackground(true)
        val outValue = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            return null
        }
        val ripple = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            ?: return null
        return android.graphics.drawable.LayerDrawable(arrayOf(fill, ripple))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    /** Localised tool name. Reuses the command registry's own strings wherever one exists. */
    private fun labelRes(key: MoreToolsPanel.Key): Int = when (key) {
        MoreToolsPanel.Key.INCOGNITO -> R.string.cmd_incognito_toggle
        MoreToolsPanel.Key.FLOATING -> R.string.cmd_floating_toggle
        MoreToolsPanel.Key.SPLIT -> R.string.cmd_split_toggle
        MoreToolsPanel.Key.ONE_HANDED -> R.string.cmd_one_handed_toggle
        MoreToolsPanel.Key.SOUND -> R.string.settings_key_sound_title
        MoreToolsPanel.Key.EMOJI -> R.string.cmd_switch_emoji
        MoreToolsPanel.Key.CLIPBOARD -> R.string.cmd_switch_clipboard
        MoreToolsPanel.Key.THEME -> R.string.activities_theme_title
        MoreToolsPanel.Key.SETTINGS -> R.string.cmd_config
    }

    /** Material Symbols glyph per tool, added alongside the existing `ic_tool_*` set. */
    private fun iconRes(key: MoreToolsPanel.Key): Int = when (key) {
        MoreToolsPanel.Key.INCOGNITO -> R.drawable.ic_tool_incognito
        MoreToolsPanel.Key.FLOATING -> R.drawable.ic_tool_floating
        MoreToolsPanel.Key.SPLIT -> R.drawable.ic_tool_split
        MoreToolsPanel.Key.ONE_HANDED -> R.drawable.ic_tool_one_handed
        MoreToolsPanel.Key.SOUND -> R.drawable.ic_tool_sound
        MoreToolsPanel.Key.EMOJI -> R.drawable.ic_tool_emoji
        MoreToolsPanel.Key.CLIPBOARD -> R.drawable.ic_tool_clipboard
        MoreToolsPanel.Key.THEME -> R.drawable.ic_tool_theme
        MoreToolsPanel.Key.SETTINGS -> R.drawable.ic_tool_settings
    }

    companion object {
        /** Header row: title on the left, close on the right. 44 dp stays inside the pane. */
        private const val HEADER_HEIGHT_DP = 44
        private const val HEADER_PADDING_DP = 16

        /** Caption size matches the toolbar's, so the panel and the row above it agree. */
        private const val TITLE_TEXT_SP = 15f
        private const val CAPTION_TEXT_SP = 11f

        /** Glyph box. The toolbar draws the same Symbols set at 22 dp. */
        private const val ICON_DP = 26

        /** One tile: glyph, gap, up to two caption lines, and the padding around them. */
        private const val TILE_HEIGHT_DP = 88
        private const val TILE_PADDING_DP = 8
        private const val CAPTION_GAP_DP = 6
        private const val TILE_RADIUS_DP = 12

        /** Never smaller than a comfortable hit target, per the toolbar's own minimum. */
        private const val TOUCH_MIN_DP = 48
    }
}
