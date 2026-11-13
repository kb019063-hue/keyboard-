package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import tribixbite.cleverkeys.gif.Gif
import tribixbite.cleverkeys.gif.GifAssetManager
import tribixbite.cleverkeys.gif.GifInsertPolicy
import tribixbite.cleverkeys.minimize.MinimizedStyle
import tribixbite.cleverkeys.gif.GifGridManager
import tribixbite.cleverkeys.gif.GifGroupButtonsBar
import tribixbite.cleverkeys.voice.DictationController
import tribixbite.cleverkeys.voice.DictationPolicy
import tribixbite.cleverkeys.voice.VoiceTypingConfig

/**
 * Handles keyboard events and state changes for CleverKeysService.
 *
 * This class centralizes logic for:
 * - Keyboard event handling (special keys, layout switching)
 * - View state management (shift, compose, selection)
 * - Layout switching (text, numeric, emoji, clipboard)
 * - Input method switching
 * - Clipboard and emoji pane management
 *
 * Responsibilities:
 * - Handle special key events (CONFIG, SWITCH_TEXT, SWITCH_NUMERIC, etc.)
 * - Manage keyboard view state updates
 * - Coordinate with managers for layout, clipboard, and input operations
 * - Bridge between KeyEventHandler and CleverKeysService
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService lifecycle methods
 * - Manager initialization
 * - Configuration management
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.368).
 */
class KeyboardReceiver(
    private val context: Context,
    private val keyboard2: CleverKeysService,
    private val keyboardViewProvider: () -> Keyboard2View,
    private val layoutManager: LayoutManager,
    private val clipboardManager: ClipboardManager,
    private val contextTracker: PredictionContextTracker,
    private val inputCoordinator: InputCoordinator,
    private val subtypeManager: SubtypeManager,
    private val handler: Handler
) : KeyEventHandler.IReceiver {

    /**
     * Audit A-2: the view is LATE-BOUND. `onThemeChanged` (and the stale-theme branch of
     * `onStartInputView`) replace the service's `_keyboardView` with a freshly inflated view;
     * a constructor-captured `Keyboard2View` val would keep dispatching layout switches and
     * shift-state updates to the detached OLD view for the rest of the process lifetime.
     * Resolving through the provider on every access guarantees calls land on the live view.
     * Pinned by [KeyboardViewLateBindingDriftTest].
     */
    private val keyboardView: Keyboard2View get() = keyboardViewProvider()

    /**
     * Phase 4 part 1: in-process dictation for the mic key. Created on first use and kept for the
     * lifetime of the receiver, because a `SpeechRecognizer` is expensive to create and holds the
     * audio route; [DictationController.cancel] is what the input-finish hook calls so a transcript
     * can never land in the next field the user focuses. [destroy] releases the recogniser itself
     * when the IME service goes away.
     */
    private var dictationController: DictationController? = null

    private fun dictation(): DictationController = dictationController ?: DictationController(
        context,
        // Dictate in the language the user is typing, not the language the phone is set to: the
        // keyboard carries its own per-layout language, so a device set to English while the
        // layout is Spanish used to dictate English. Read through a lambda, not captured, so a
        // layout switch between two presses is honoured. `globalConfigOrNull` rather than
        // `globalConfig` because the very first press can beat Config initialisation.
        keyboardLanguage = {
            Config.globalConfigOrNull()?.activeLanguages()?.primary
        }
    ).also { dictationController = it }

    /** The keyboard side of dictation: where text goes and where status lines are shown. */
    private val dictationSink = object : DictationController.Sink {
        override fun commitText(text: String) {
            keyboard2.currentInputConnection?.commitText(text, 1)
        }

        override fun textBeforeCursor(): String? =
            keyboard2.currentInputConnection?.getTextBeforeCursor(JOIN_CONTEXT_CHARS, 0)?.toString()

        override fun showMessage(message: String) {
            // Toasts are suppressed for IMEs on Android 13+, so the suggestion bar carries it.
            keyboard2.showSuggestionBarMessage(message)
        }
    }

    // View references
    private var emojiPane: ViewGroup? = null
    /** The tools panel (2026-11-09), reused across opens like the panes above. */
    private var toolsPanel: View? = null
    private var contentPaneContainer: android.widget.FrameLayout? = null
    private var topPane: android.widget.FrameLayout? = null
    private var scrollView: android.widget.HorizontalScrollView? = null
    private var suggestionBarHeight: Int = 0
    private var contentPaneHeight: Int = 0

    // Track if content pane is showing (to reset on keyboard hide)
    private var isContentPaneShowing: Boolean = false

    // Track which pane is currently visible for toggle behavior
    private var currentPaneType: PaneType = PaneType.NONE

    private enum class PaneType { NONE, EMOJI, CLIPBOARD, GIF, TOOLS }

    // #41: Emoji search manager (uses suggestion bar for status display)
    private var emojiSearchManager: EmojiSearchManager? = null

    // GIF search: reference to search EditText for key routing
    private var gifSearchInput: EditText? = null
    // GIF search active flag — like EmojiSearchManager.searchActive / ClipboardManager.searchMode
    private var gifSearchActive: Boolean = false

    /**
     * Sets references to views for content pane management.
     *
     * @param emojiPane Emoji pane view
     * @param contentPaneContainer Container for emoji/clipboard panes
     * @param topPane The FrameLayout that holds either scrollView or contentPaneContainer
     * @param scrollView The HorizontalScrollView containing suggestion bar
     * @param suggestionBarHeight Height of suggestion bar in pixels
     * @param contentPaneHeight Height of content pane in pixels
     */
    fun setViewReferences(
        emojiPane: ViewGroup?,
        contentPaneContainer: android.widget.FrameLayout?,
        topPane: android.widget.FrameLayout? = null,
        scrollView: android.widget.HorizontalScrollView? = null,
        suggestionBarHeight: Int = 0,
        contentPaneHeight: Int = 0
    ) {
        this.emojiPane = emojiPane
        this.contentPaneContainer = contentPaneContainer
        this.topPane = topPane
        this.scrollView = scrollView
        this.suggestionBarHeight = suggestionBarHeight
        this.contentPaneHeight = contentPaneHeight

        // Set up clipboard close button callback to trigger SWITCH_BACK_CLIPBOARD event
        clipboardManager.setOnCloseCallback {
            handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)
        }
        // The clipboard pane is as tall as the WHOLE keyboard, keys included (2026-11-13):
        // it takes their place instead of sitting over them. See ClipboardPanePolicy.
    }

    /**
     * The height the whole keyboard occupied, measured while the keys were showing (2026-11-13).
     *
     * The clipboard pane takes the keyboard's place, and it must take EXACTLY its place: if the
     * window grew or shrank when the pane opened, the app behind would lay itself out again —
     * which is the movement the owner has been reporting. So the total is measured, not derived:
     * one-handed, split and floating layouts all give the keyboard a different height, and a
     * screen percentage (what the pane used before) describes none of them.
     *
     * Cached because the keys are hidden while the pane is open, which zeroes the height that
     * would be measured then.
     */
    private var lastKeyboardTotalPx = 0

    private fun measuredKeyboardTotalPx(): Int {
        val containerHeight = (topPane?.parent as? View)?.height ?: 0
        if (keyboardView.visibility == View.VISIBLE && containerHeight > 0) {
            lastKeyboardTotalPx = containerHeight
        }
        return lastKeyboardTotalPx
    }

    /** The toolbar row's height now, or 0 when the toolbar is off. */
    private fun toolbarHeightPx(): Int =
        (topPane?.parent as? ViewGroup)?.findViewById<View?>(R.id.keyboard_toolbar)?.height ?: 0

    /**
     * Show content pane and hide suggestion bar.
     * Uses simple view swapping in topPane.
     */
    private fun showContentPane(height: Int = contentPaneHeight, hideKeys: Boolean = false) {
        val top = topPane ?: return
        val content = contentPaneContainer ?: return
        val scroll = scrollView ?: return

        SuggestionBarPane.switchToContentPaneMode(top, content, scroll, height)
        isContentPaneShowing = true
        // The clipboard owns the whole keyboard, keys included: hide the keys rather than let
        // them cover the bottom of the list (2026-11-13, the owner's report). They are hidden,
        // never removed — the typing state lives in the view, and removing it would drop shift,
        // the layer and any swipe in progress.
        keyboardView.visibility = if (hideKeys) View.GONE else View.VISIBLE
    }

    /**
     * The clipboard pane's height: everything above the keys' bottom edge, i.e. the toolbar plus
     * the band plus the keys — the window's height stays exactly what it was when the pane opens
     * (ClipboardPanePolicy). Before the first layout nothing has been measured, and the pane keeps
     * the percentage height it was built with.
     */
    private fun clipboardHeight(): Int {
        val total = measuredKeyboardTotalPx()
        if (total <= 0) return contentPaneHeight
        val available = (total - toolbarHeightPx()).coerceAtLeast(0)
        val height = ClipboardPanePolicy.paneHeightPx(
            keyboardHeightPx = available,
            screenHeightPx = context.resources.displayMetrics.heightPixels
        )
        return if (height > 0) height else contentPaneHeight
    }

    /**
     * Hide content pane and show suggestion bar.
     * Uses simple view swapping in topPane.
     */
    private fun hideContentPane() {
        // CRITICAL: Always reset state flag, even if views are null
        // Otherwise toggle logic will think pane is still showing
        isContentPaneShowing = false

        val top = topPane
        val content = contentPaneContainer
        val scroll = scrollView

        if (top == null || content == null || scroll == null) {
            return
        }

        // Back to typing: the keys come back before the band is restored, so the first tap after
        // the clipboard closes lands on a key rather than on nothing.
        keyboardView.visibility = View.VISIBLE

        SuggestionBarPane.switchToSuggestionBarMode(
            top, content, scroll, keyboard2.suggestionStripHeight(suggestionBarHeight)
        )
    }

    /**
     * Host the tools panel in the content-pane slot (2026-11-09).
     *
     * Kept next to the pane openers because that is what it is: the fourth pane, with the same
     * lifecycle. It is deliberately NOT an AlertDialog any more — see the note on
     * [MoreToolsPanelView] for why the dialog was thrown away. Being a pane buys three things a
     * dialog cannot have: the panel is as wide as the keyboard, it sits on the keyboard's own
     * surface instead of floating over the app, and it cannot obscure the row of keys it was
     * opened from.
     *
     * Re-binding rather than inflating fresh: unlike the emoji/GIF panes, this one holds no
     * search field whose state could go stale, so the same view is refreshed in place. That also
     * means a switch tap can rebuild its own grid from inside its callback without detaching the
     * view mid-dispatch (the classic `removeAllViews` inside an `OnClickListener` crash).
     * The one thing that does go stale is the theme, so a new one is built when it has changed.
     */
    private fun showToolsPanel(container: android.widget.FrameLayout) {
        // The panel reads its colours from the Theme it was built with. A theme change gives the
        // keyboard a new view with a new Theme and the service rebuilds the other panes, but
        // this one is kept here, so it went on wearing the old theme until the process died.
        // Reuse it only for the theme the keyboard is drawn with now.
        val theme = keyboardView.getTheme()
        val panel = (toolsPanel as? MoreToolsPanelView)?.takeIf { it.isBuiltFor(theme) }
            ?: MoreToolsPanelView(context, theme).also {
                toolsPanel = it
                it.setOnClose { handle_event_key(KeyValue.Event.SWITCH_BACK_TOOLS) }
            }

        container.removeAllViews()
        (panel.parent as? ViewGroup)?.removeView(panel)
        panel.layoutParams = paneLayoutParams(contentPaneHeight)
        container.addView(panel)
        showContentPane()
        currentPaneType = PaneType.TOOLS

        // Re-seed from Config on every open: whatever the panel last drew belongs to a previous
        // visit, and Config is authoritative once the listener has caught up.
        panelState = toolsState()
        renderToolsPanel(panel)

        // Evidence channel, kept from the dialog it replaces: the emulator's accessibility
        // dumps never carry IME-window nodes (run 40's DUMPDIAG), so logcat is the only witness
        // CI has that this opened and what it carried.
        android.util.Log.i(
            TAG_TOOLS,
            "shown rows=${MoreToolsPanel.rowCount(toolsState())} " +
                MoreToolsPanel.cells(toolsState()).joinToString(" ") {
                    "${it.key.name.lowercase()}=${if (it.checked) "on" else "off"}"
                }
        )
    }

    /** The five switches' current values, read the same way the dialog's checked array was. */
    private fun toolsState(): MoreToolsPanel.State {
        val cfg = Config.globalConfig()
        return MoreToolsPanel.State(
            incognito = cfg.incognito_enabled,
            floating = cfg.floating_enabled,
            split = cfg.split_enabled,
            oneHanded = cfg.one_handed_enabled,
            sound = cfg.sound_enabled,
        )
    }

    /**
     * The state the panel is currently DRAWING — which is not always what [Config] says yet.
     *
     * This is [BUG-AUDIT-2026-10-25.md] item 7, fixed for the dialog and re-pinned here because
     * the panel reintroduced it. A toggle writes a SharedPreferences value; `Config` is only
     * refreshed asynchronously by the listener. Reading `Config` back a moment later therefore
     * still reports the OLD value, and the next tap computed the same "next" again — so a quick
     * second tap on one tile wrote the same state twice while the tile showed the opposite.
     *
     * The fix is the old fix: the surface the user is looking at owns the value, not the
     * read-model behind it. Seeded from `Config` when the panel opens, then advanced here on
     * every tap. `Config` catches up on its own and agrees.
     */
    private var panelState: MoreToolsPanel.State? = null

    /**
     * Paint [panel] with the live switch values and route a tap.
     *
     * Switches go through [CleverKeysService.setToolsPanelSwitch], the same explicit setter each
     * tool's own command uses. Launchers reuse the keyboard's existing events, so the panel adds
     * no second dispatch path for emoji, clipboard, theme or settings.
     */
    private fun renderToolsPanel(panel: MoreToolsPanelView) {
        val state = panelState ?: toolsState().also { panelState = it }
        panel.bind(state) { key -> onToolTap(panel, key) }
    }

    private fun onToolTap(panel: MoreToolsPanelView, key: MoreToolsPanel.Key) {
        when (key) {
            MoreToolsPanel.Key.INCOGNITO,
            MoreToolsPanel.Key.FLOATING,
            MoreToolsPanel.Key.SPLIT,
            MoreToolsPanel.Key.ONE_HANDED,
            MoreToolsPanel.Key.SOUND -> {
                // Flip the value the panel is SHOWING, not what Config says: Config lags behind
                // the write (see [panelState]) and a double tap must not write the same value
                // twice. Mirrors the dialog's `isChecked` defence, audit item 7.
                val current = panelState ?: toolsState()
                val next = !current.checked(key)
                panelState = current.with(key, next)
                keyboard2.setToolsPanelSwitch(key, next)
                // Repaint in place so the tile lights immediately. Safe from inside the click
                // listener: rebuildGrid() replaces the grid's children, never the tile that is
                // currently dispatching.
                renderToolsPanel(panel)
            }
            MoreToolsPanel.Key.EMOJI -> {
                handle_event_key(KeyValue.Event.SWITCH_BACK_TOOLS)
                handle_event_key(KeyValue.Event.SWITCH_EMOJI)
            }
            MoreToolsPanel.Key.CLIPBOARD -> {
                handle_event_key(KeyValue.Event.SWITCH_BACK_TOOLS)
                handle_event_key(KeyValue.Event.SWITCH_CLIPBOARD)
            }
            MoreToolsPanel.Key.THEME -> {
                handle_event_key(KeyValue.Event.SWITCH_BACK_TOOLS)
                context.startActivity(Intent(context, ThemeSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            MoreToolsPanel.Key.SETTINGS -> {
                handle_event_key(KeyValue.Event.SWITCH_BACK_TOOLS)
                context.startActivity(Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /**
     * Reset content pane state when keyboard hides (e.g., app switch).
     * Call this from CleverKeysService.onFinishInputView().
     */
    fun resetContentPaneState() {
        // CRITICAL: Always reset state, even if views are null
        // This prevents stale state after app switches
        if (isContentPaneShowing) {
            hideContentPane()  // This now always resets isContentPaneShowing
        }

        // Always reset pane type to prevent toggle issues
        currentPaneType = PaneType.NONE
        closeCurrentPaneRoutingState()

        // Phase 4 part 1: a dictation session belongs to the field that started it. The editor is
        // going away here, so end the session rather than let a late transcript commit into
        // whatever the user focuses next.
        dictationController?.cancel()
    }

    /**
     * Release the receiver's process-lifetime resources. `onFinishInputView` only cancels a
     * dictation session; without this, the `SpeechRecognizer` and its service `Context` survive
     * the IME's own destruction.
     */
    fun destroy() {
        dictationController?.destroy()
        dictationController = null
    }

    /**
     * #41: Sets the emoji search manager.
     * Called from CleverKeysService after initialization.
     */
    fun setEmojiSearchManager(manager: EmojiSearchManager) {
        this.emojiSearchManager = manager
    }

    /**
     * Audit A-3/E-2: clears EVERY pane's key-routing state — the SWITCH_BACK_* prologue,
     * extracted so the three openers run it too. The openers physically evict the showing
     * pane (`removeAllViews()`) but used to leave its routing flag set; since
     * KeyEventHandler.sendText routes strictly by flag priority (tag → edit → search →
     * emoji → gif), a direct pane-to-pane switch left a stale higher-priority flag
     * shadowing the live pane: typing (and the DEL ladder) went into a DETACHED search
     * EditText, and the GIF key toggle-inverted (a stale `gifSearchActive` made it close
     * everything instead of opening GIF). Callers: the three SWITCH_* openers (after their
     * toggle/guard checks, before hosting the new pane) and SWITCH_BACK_*.
     */
    private fun closeCurrentPaneRoutingState() {
        // Exit clipboard search mode
        clipboardManager.resetSearchOnHide()
        // #41 v4: notify emoji search manager its pane is going away (clears searchActive)
        emojiSearchManager?.onPaneClosed()
        // Clear GIF search state so isGifPaneOpen() returns false
        gifSearchActive = false
        gifSearchInput = null
    }

    /**
     * Audit H-3 (the #130 class on the two remaining surfaces): paint a freshly inflated
     * emoji/GIF pane with the ACTIVE runtime theme's colors. Every color in those layouts
     * is a theme-attr (`?attr`) reference, but `Config.getThemeId` maps all `custom_*`/`decorative_*`
     * names to the hardcoded CleverKeysDark base style — so under any runtime theme the
     * panes rendered base-purple until this repaint (the clipboard pane got the same
     * treatment in a7940256). No-op for built-in XML themes, whose attrs resolve correctly.
     *
     * @param searchBarId/groupBarId/paginationBarId chrome bars painted colorKey
     * @param labelViewIds  ImageButtons tinted / TextViews colored with labelColor
     * @param subLabelViewIds TextViews colored with subLabelColor (hints handled inline)
     */
    private fun applyRuntimeThemeToPane(
        pane: ViewGroup,
        searchBarId: Int,
        groupBarId: Int,
        searchInputId: Int,
        gridId: Int,
        noResultsId: Int,
        labelViewIds: IntArray,
        paginationBarId: Int? = null,
        subLabelViewIds: IntArray = intArrayOf(),
    ) {
        val config = Config.globalConfig()
        if (!config.isRuntimeTheme()) return
        val theme = try {
            tribixbite.cleverkeys.theme.ThemeProvider.getInstance(context).getTheme(config.themeName)
        } catch (e: Exception) {
            // Dangling custom theme id (H-2 territory) — leave the XML base colors up.
            android.util.Log.w(TAG, "Runtime theme unavailable for pane repaint: ${e.message}")
            return
        }
        val bg = theme.colorKeyboardBackground
        val key = theme.colorKey
        val label = theme.labelColor
        val sub = theme.subLabelColor

        if (bg != 0) {
            pane.setBackgroundColor(bg)
            pane.findViewById<View?>(gridId)?.setBackgroundColor(bg)
            pane.findViewById<View?>(noResultsId)?.setBackgroundColor(bg)
        }
        if (key != 0) {
            pane.findViewById<View?>(searchBarId)?.setBackgroundColor(key)
            pane.findViewById<View?>(groupBarId)?.setBackgroundColor(key)
            paginationBarId?.let { pane.findViewById<View?>(it)?.setBackgroundColor(key) }
        }
        if (label != 0) {
            for (id in labelViewIds) {
                when (val v = pane.findViewById<View?>(id)) {
                    is ImageButton -> v.setColorFilter(label, android.graphics.PorterDuff.Mode.SRC_IN)
                    is TextView -> v.setTextColor(label)
                    else -> {}
                }
            }
            (pane.findViewById<View?>(searchInputId) as? TextView)?.setTextColor(label)
        }
        if (sub != 0) {
            (pane.findViewById<View?>(searchInputId) as? TextView)?.setHintTextColor(sub)
            (pane.findViewById<View?>(noResultsId) as? TextView)?.setTextColor(sub)
            for (id in subLabelViewIds) {
                (pane.findViewById<View?>(id) as? TextView)?.setTextColor(sub)
            }
        }
    }

    override fun handle_event_key(ev: KeyValue.Event) {
        when (ev) {
            KeyValue.Event.CONFIG -> {
                val intent = Intent(context, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }

            KeyValue.Event.SWITCH_TEXT -> {
                keyboardView.setKeyboard(layoutManager.clearSpecialLayout())
            }

            KeyValue.Event.SWITCH_NUMERIC -> {
                // Static resource name → direct R reference (no reflection needed).
                val numpad = layoutManager.loadNumpad(R.raw.numeric)
                if (numpad != null) {
                    keyboardView.setKeyboard(numpad)
                }
            }

            KeyValue.Event.SWITCH_EMOJI -> {
                // Toggle behavior: if emoji pane already visible, close it
                if (currentPaneType == PaneType.EMOJI && isContentPaneShowing) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_EMOJI)
                    return
                }

                // #148: refuse without a host container — NEVER setInputView(pane), which
                // replaced the whole keyboard and bypassed the inset-aware wrapper. The
                // container is built unconditionally since ARC-002 (cb7cebd4), so this only
                // fires on a propagation regression.
                val container = contentPaneContainer ?: run {
                    android.util.Log.e(TAG, "SWITCH_EMOJI with no contentPaneContainer — refusing (gh #148)")
                    return
                }

                // A-3/E-2: evicting another pane must also clear its routing flags
                closeCurrentPaneRoutingState()

                // Always inflate fresh to avoid stale view issues after app switch
                emojiPane = keyboard2.inflate_view(R.layout.emoji_pane) as ViewGroup

                // Capture for null safety
                val pane = emojiPane

                // Show emoji pane in content container (keyboard stays visible below)
                container.removeAllViews()
                // Detach pane from any existing parent first
                (pane?.parent as? ViewGroup)?.removeView(pane)
                // Set pane with explicit height
                pane?.layoutParams = paneLayoutParams(contentPaneHeight)
                container.addView(pane)
                showContentPane()

                // H-3: runtime (custom/decorative) themes need a programmatic repaint
                pane?.let {
                    applyRuntimeThemeToPane(
                        it,
                        searchBarId = R.id.emoji_search_bar,
                        groupBarId = R.id.emoji_group_buttons,
                        searchInputId = R.id.emoji_search_input,
                        gridId = R.id.emoji_grid,
                        noResultsId = R.id.emoji_no_results,
                        labelViewIds = intArrayOf(R.id.emoji_search_clear, R.id.emoji_close_button),
                    )
                }

                currentPaneType = PaneType.EMOJI

                // #41 v4: Initialize emoji search manager with the pane and notify pane opened
                emojiPane?.let { pane ->
                    emojiSearchManager?.initialize(pane)
                    // Auto-detect context word for initial search query
                    val textBeforeCursor = keyboard2.currentInputConnection
                        ?.getTextBeforeCursor(100, 0)
                    val contextWord = emojiSearchManager?.extractWordBeforeCursor(textBeforeCursor)
                    emojiSearchManager?.onPaneOpened(contextWord)

                    // Wire up search manager to category buttons
                    pane.findViewById<EmojiGroupButtonsBar>(R.id.emoji_group_buttons)
                        ?.setSearchManager(emojiSearchManager!!)

                    // #41 v8: Wire up search manager to emoji grid for selection bypass
                    pane.findViewById<EmojiGridView>(R.id.emoji_grid)?.let { grid ->
                        grid.setSearchManager(emojiSearchManager!!)
                        // #41 v10: Wire up service for suggestion bar messages on long-press
                        grid.setService(keyboard2)
                    }

                    // #41 v10: Close button callback to return to keyboard
                    emojiSearchManager?.setOnCloseCallback {
                        handle_event_key(KeyValue.Event.SWITCH_BACK_EMOJI)
                    }
                }
            }

            KeyValue.Event.SWITCH_CLIPBOARD -> {
                // Toggle behavior: if clipboard pane already visible, close it
                if (currentPaneType == PaneType.CLIPBOARD && isContentPaneShowing) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)
                    return
                }

                // SECURITY: Block clipboard access on lock screen (contains PII)
                if (DirectBootManager.getInstance(context).isDeviceLocked) {
                    return
                }

                // #148: refuse without a host container — see the SWITCH_EMOJI guard.
                val container = contentPaneContainer ?: run {
                    android.util.Log.e(TAG, "SWITCH_CLIPBOARD with no contentPaneContainer — refusing (gh #148)")
                    return
                }

                // A-3/E-2: evicting another pane must also clear its routing flags
                closeCurrentPaneRoutingState()

                // Get clipboard pane from manager (lazy initialization)
                val clipboardPane = clipboardManager.getClipboardPane(keyboard2.layoutInflater)

                // Reset search mode and clear any previous search when showing clipboard pane
                clipboardManager.resetSearchOnShow()

                // Show clipboard pane in content container (keyboard stays visible below)
                container.removeAllViews()
                // Detach pane from any existing parent first
                (clipboardPane.parent as? ViewGroup)?.removeView(clipboardPane)
                // The clipboard takes the whole keyboard: it is as tall as the toolbar, the band
                // and the keys together, and the keys are not drawn while it is up (2026-11-13,
                // ClipboardPanePolicy). Everything else keeps the percentage height it had.
                clipboardPane.layoutParams = paneLayoutParams(clipboardHeight())
                container.addView(clipboardPane)
                showContentPane(height = clipboardHeight(), hideKeys = true)

                currentPaneType = PaneType.CLIPBOARD
            }

            /**
             * The tools panel (2026-11-09). Opened from the toolbar's grid key (`more_tools`),
             * hosted exactly like the emoji, clipboard and GIF panes so it is part of the
             * keyboard instead of a dialog floating over the app.
             *
             * The toggle behaviour is the panel's own close route: pressing the grid key while
             * the panel is up puts it away, which is how Gboard's grid key behaves.
             */
            KeyValue.Event.SWITCH_TOOLS -> {
                // Evidence channel, unchanged from the dialog this replaced: the press is logged
                // BEFORE the panel is built and the panel logs "shown rows=" after, so a field
                // build where the command dispatches but nothing appears is one grep away. The
                // emulator's accessibility dumps never carry IME-window nodes (run 40's
                // DUMPDIAG), which leaves logcat as the only witness either surface has.
                android.util.Log.i(TAG_TOOLS, "press")

                if (currentPaneType == PaneType.TOOLS && isContentPaneShowing) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_TOOLS)
                    return
                }

                // #148: refuse without a host container — see the SWITCH_EMOJI guard.
                val container = contentPaneContainer ?: run {
                    android.util.Log.e(TAG, "SWITCH_TOOLS with no contentPaneContainer — refusing (gh #148)")
                    return
                }

                // A-3/E-2: evicting another pane must also clear its routing flags
                closeCurrentPaneRoutingState()

                showToolsPanel(container)
            }

            KeyValue.Event.SWITCH_GIF -> {
                // GIF panel is opt-in — ignore key if disabled in settings
                if (!Config.globalConfig().gif_enabled) return

                // Toggle behavior: if GIF pane already visible, close it
                if (gifSearchActive) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_GIF)
                    return
                }

                // #148: refuse without a host container — see the SWITCH_EMOJI guard.
                val container = contentPaneContainer ?: run {
                    android.util.Log.e(TAG, "SWITCH_GIF with no contentPaneContainer — refusing (gh #148)")
                    return
                }

                // A-3/E-2: evicting another pane must also clear its routing flags
                closeCurrentPaneRoutingState()

                // Inflate fresh GIF pane layout
                val gifPaneView = keyboard2.inflate_view(R.layout.gif_pane) as ViewGroup

                // Show GIF pane in content container (keyboard stays visible below)
                container.removeAllViews()
                (gifPaneView.parent as? ViewGroup)?.removeView(gifPaneView)
                gifPaneView.layoutParams = paneLayoutParams(contentPaneHeight)
                container.addView(gifPaneView)
                showContentPane()

                // H-3: runtime (custom/decorative) themes need a programmatic repaint
                applyRuntimeThemeToPane(
                    gifPaneView,
                    searchBarId = R.id.gif_search_bar,
                    groupBarId = R.id.gif_group_buttons,
                    searchInputId = R.id.gif_search_input,
                    gridId = R.id.gif_grid,
                    noResultsId = R.id.gif_no_results,
                    labelViewIds = intArrayOf(
                        R.id.gif_search_clear, R.id.gif_close_button,
                        R.id.gif_page_prev, R.id.gif_page_next
                    ),
                    paginationBarId = R.id.gif_pagination_bar,
                    subLabelViewIds = intArrayOf(R.id.gif_page_info),
                )

                currentPaneType = PaneType.GIF

                // Wire up GIF grid with RecyclerView + Coil
                val recyclerView = gifPaneView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.gif_grid)
                val gifColumns = Config.globalConfig().gif_thumbnail_columns
                val gifGrid = recyclerView?.let { GifGridManager(context, it, gifColumns) }
                gifGrid?.onGifSelected = { gif ->
                    // #149: primary delivery is the locally-shipped media via commitContent;
                    // a (case-preserved) Giphy URL is only the text fallback. See insertGif.
                    insertGif(gif)
                    // Record usage and close GIF pane
                    handle_event_key(KeyValue.Event.SWITCH_BACK_GIF)
                }

                // Long press: IME-safe PopupWindow with title + copy actions
                // (PopupMenu fails in IME context — no window token)
                gifGrid?.onGifLongPress = { gif, anchor ->
                    showGifPopup(gif, anchor)
                }

                // Wire up search bar — store reference and activate routing flag
                // (same pattern as EmojiSearchManager.searchActive / ClipboardManager.searchMode)
                val searchInput = gifPaneView.findViewById<EditText>(R.id.gif_search_input)
                gifSearchInput = searchInput
                gifSearchActive = true
                searchInput?.requestFocus()
                val searchClear = gifPaneView.findViewById<ImageButton>(R.id.gif_search_clear)
                val noResults = gifPaneView.findViewById<TextView>(R.id.gif_no_results)

                // Audit E-5: "No results" keys off the async results callback — reading
                // getResultCount() right after search() returns the PREVIOUS query's count
                // (search debounces 150 ms + queries on IO), so the indicator never showed
                // for the query actually typed.
                gifGrid?.onResultsChanged = { query, count ->
                    noResults?.visibility =
                        if (query.isNotEmpty() && count == 0) View.VISIBLE else View.GONE
                }

                searchInput?.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val query = s?.toString()?.trim() ?: ""
                        searchClear?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                        gifGrid?.search(query)
                    }
                })

                searchClear?.setOnClickListener {
                    searchInput?.text?.clear()
                }

                // Wire up close button
                gifPaneView.findViewById<ImageButton>(R.id.gif_close_button)?.setOnClickListener {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_GIF)
                }

                // Wire up pagination controls
                val paginationBar = gifPaneView.findViewById<android.widget.LinearLayout>(R.id.gif_pagination_bar)
                val pageInfo = gifPaneView.findViewById<TextView>(R.id.gif_page_info)
                val pagePrev = gifPaneView.findViewById<TextView>(R.id.gif_page_prev)
                val pageNext = gifPaneView.findViewById<TextView>(R.id.gif_page_next)
                // ◀/▶ swap under RTL, where the bar lays "previous" out on the right.
                PanePagerArrows.apply(pagePrev, pageNext)

                gifGrid?.onPaginationChanged = { needsPagination, currentPage, totalPages ->
                    paginationBar?.visibility = if (needsPagination) View.VISIBLE else View.GONE
                    pageInfo?.text = context.getString(R.string.page_indicator, currentPage, totalPages)
                    pagePrev?.alpha = if (gifGrid.hasPreviousPage()) 1.0f else 0.3f
                    pageNext?.alpha = if (gifGrid.hasNextPage()) 1.0f else 0.3f
                }

                pagePrev?.setOnClickListener { gifGrid?.previousPage() }
                pageNext?.setOnClickListener { gifGrid?.nextPage() }

                // Wire up category buttons — clear search + switch grid category
                val groupButtons = gifPaneView.findViewById<GifGroupButtonsBar>(R.id.gif_group_buttons)
                groupButtons?.setOnCategorySelectedListener {
                    searchInput?.text?.clear()
                }
                groupButtons?.onCategoryChanged = { category ->
                    gifGrid?.setCategory(category)
                }
            }

            KeyValue.Event.SWITCH_BACK_EMOJI,
            KeyValue.Event.SWITCH_BACK_CLIPBOARD,
            KeyValue.Event.SWITCH_BACK_GIF,
            KeyValue.Event.SWITCH_BACK_TOOLS -> {
                // A-3/E-2: shared routing-state clear (clipboard search, emoji, GIF)
                closeCurrentPaneRoutingState()

                // The panel's local copy of the switch values dies with the visit; the next open
                // re-seeds from Config.
                panelState = null

                // Reset pane tracking
                currentPaneType = PaneType.NONE

                // Swap back: hide content pane, show suggestion bar, resize wrapper.
                // #148: no setInputView restore here — a pane can no longer BE the input
                // view (the openers refuse without a container), so there is nothing to
                // restore; hideContentPane() is a safe no-op when the views are null.
                hideContentPane()
            }

            // gh #175: collapse to a thin bar / a floating button; tapping it expands again.
            KeyValue.Event.MINIMIZE_BAR -> keyboard2.minimizeKeyboard(MinimizedStyle.BAR)
            KeyValue.Event.MINIMIZE_FAB -> keyboard2.minimizeKeyboard(MinimizedStyle.FAB)
            // gh #175: hide the keyboard entirely (the app's own focus/tap brings it back).
            KeyValue.Event.HIDE_KEYBOARD -> keyboard2.hideKeyboardByUser()

            KeyValue.Event.CHANGE_METHOD_PICKER -> {
                subtypeManager.inputMethodManager.showInputMethodPicker()
            }

            KeyValue.Event.CHANGE_METHOD_AUTO -> {
                if (Build.VERSION.SDK_INT < 28) {
                    keyboard2.getConnectionToken()?.let { token ->
                        subtypeManager.inputMethodManager.switchToLastInputMethod(token)
                    }
                } else {
                    keyboard2.switchToNextInputMethod(false)
                }
            }

            KeyValue.Event.ACTION -> {
                keyboard2.currentInputConnection?.performEditorAction(keyboard2.actionId)
            }

            KeyValue.Event.SWITCH_FORWARD -> {
                if (layoutManager.getLayoutCount() > 1) {
                    keyboardView.setKeyboard(layoutManager.incrTextLayout(1))
                }
            }

            KeyValue.Event.SWITCH_BACKWARD -> {
                if (layoutManager.getLayoutCount() > 1) {
                    keyboardView.setKeyboard(layoutManager.incrTextLayout(-1))
                }
            }

            KeyValue.Event.SWITCH_GREEKMATH -> {
                val greekmath = layoutManager.loadNumpad(R.xml.greekmath)
                if (greekmath != null) {
                    keyboardView.setKeyboard(greekmath)
                }
            }

            KeyValue.Event.CAPS_LOCK -> {
                set_shift_state(true, true)
            }

            KeyValue.Event.SWITCH_VOICE_TYPING -> handleVoiceTypingKey()

            KeyValue.Event.SWITCH_VOICE_TYPING_CHOOSER -> {
                VoiceImeSwitcher.choose_voice_ime(
                    keyboard2,
                    subtypeManager.inputMethodManager,
                    Config.globalPrefs()
                )
            }

            else -> {} // Unhandled events
        }
    }

    /**
     * Phase 4 part 1: the mic key.
     *
     * It used to do exactly one thing -- [VoiceImeSwitcher.switch_to_voice_ime] -- which on a phone
     * with no cooperating voice IME shows a system toast and types nothing. The decision of what a
     * press means now lives in [DictationPolicy] (pure, unit-tested), and this is only the wiring:
     * read the two preferences, ask the policy, and act on the two answers that belong here because
     * they need the service ([keyboard2]) and the subtype manager. Dictation itself, the permission
     * request and the self-test commit all happen inside [DictationController].
     */
    private fun handleVoiceTypingKey() {
        val prefs = Config.globalPrefs()
        // A hand-edited or imported preference with the wrong type must not crash the mic key:
        // Config's safe readers fall back to the documented defaults on ClassCastException.
        val mode = VoiceTypingConfig.modeOf(
            Config.safeGetString(prefs, VoiceTypingConfig.PREF_MODE, VoiceTypingConfig.DEFAULT_MODE)
        )
        val selfTest = Config.safeGetBoolean(
            prefs,
            VoiceTypingConfig.PREF_SELF_TEST,
            VoiceTypingConfig.DEFAULT_SELF_TEST
        )
        val voiceImeAvailable = VoiceImeSwitcher.has_voice_ime(subtypeManager.inputMethodManager)
        when (dictation().press(mode, selfTest, voiceImeAvailable, dictationSink)) {
            DictationPolicy.Press.SWITCH_TO_VOICE_IME -> {
                if (!VoiceImeSwitcher.switch_to_voice_ime(
                        keyboard2,
                        subtypeManager.inputMethodManager,
                        prefs
                    )
                ) {
                    keyboard2.getConfig()?.shouldOfferVoiceTyping = false
                }
            }
            DictationPolicy.Press.REPORT_PERMISSION_REFUSED -> {
                // The recogniser is fine; the microphone permission is off. Say that, and where to
                // fix it: this is the one case the user can unblock themselves in ten seconds. The
                // voice-typing offer stays on, because granting it makes the key work again.
                keyboard2.showSuggestionBarMessage(
                    context.getString(R.string.voice_typing_permission_off)
                )
            }
            DictationPolicy.Press.REPORT_NO_VOICE_INPUT -> {
                // The point of this branch: no voice input at all is a message, not a dead key.
                keyboard2.showSuggestionBarMessage(
                    context.getString(R.string.voice_typing_nothing_available)
                )
                keyboard2.getConfig()?.shouldOfferVoiceTyping = false
            }
            // START_LISTENING / STOP_LISTENING / REQUEST_PERMISSION / COMMIT_SELF_TEST were already
            // acted on inside the controller.
            else -> Unit
        }
    }

    /**
     * #149: insert a tapped GIF.
     *
     * Delivery ladder (decided by the pure [GifInsertPolicy] so the matrix is unit-tested):
     *  1. COMMIT_MEDIA — the locally-shipped animated WebP via InputConnection.commitContent
     *     (API 25+, editor accepts image/webp). Works for EVERY pack, old and new, and is
     *     the only path that inserts the actual GIF; the pack ships the media offline and
     *     the app has no INTERNET permission.
     *  2. COMMIT_URL_TEXT — the case-preserved Giphy URL as text. Only available when the
     *     pack carries a marked ID ([Gif.getGiphyId]); legacy packs return null there,
     *     because their reconstructed URLs were dead 404s (case-smashed/compound tokens —
     *     the original #149 defect).
     *  3. COPY_MEDIA_TO_CLIPBOARD / COPY_URL_TO_CLIPBOARD — clipboard fallbacks when no
     *     InputConnection (or no URL) is available.
     *
     * Internal (not private) so the mock tier can drive it directly
     * (KeyboardReceiverPaneHostTest) — the grid-manager wiring needs a real RecyclerView.
     */
    internal fun insertGif(gif: Gif) {
        val ic = keyboard2.currentInputConnection
        val url = gif.getGiphyUrl()
        val fullFile = java.io.File(context.filesDir, gif.getFullPath())
        val hasLocalMedia = fullFile.exists() && fullFile.length() > 0
        val editorMimes: Array<String> = try {
            keyboard2.currentInputEditorInfo?.let {
                androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(it)
            } ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        val editorAcceptsWebp =
            GifInsertPolicy.editorAcceptsMime(editorMimes, GifInsertPolicy.MIME_WEBP)

        val action = GifInsertPolicy.decide(
            Build.VERSION.SDK_INT, ic != null, hasLocalMedia, editorAcceptsWebp, url != null
        )
        android.util.Log.d(
            "GifPanel",
            "onGifSelected: action=$action media=$hasLocalMedia editorWebp=$editorAcceptsWebp url=${url != null}"
        )
        when (action) {
            GifInsertPolicy.Action.COMMIT_MEDIA -> {
                if (!commitGifMedia(fullFile, gif, ic!!)) {
                    // Editor advertised support but refused at runtime — same ladder minus media.
                    when (GifInsertPolicy.afterFailedMediaCommit(true, url != null)) {
                        GifInsertPolicy.Action.COMMIT_URL_TEXT -> ic.commitText(url, 1)
                        else -> copyGifMediaToClipboard(fullFile, gif)
                    }
                }
            }
            GifInsertPolicy.Action.COMMIT_URL_TEXT -> ic!!.commitText(url, 1)
            GifInsertPolicy.Action.COPY_MEDIA_TO_CLIPBOARD -> copyGifMediaToClipboard(fullFile, gif)
            GifInsertPolicy.Action.COPY_URL_TO_CLIPBOARD -> {
                val clip = android.content.ClipData.newPlainText("GIF URL", url)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(clip)
                keyboard2.showSuggestionBarMessage(context.getString(R.string.gif_msg_url_copied))
            }
            GifInsertPolicy.Action.NONE ->
                // Legacy pack without full media in a URL-less world: nothing honest to
                // insert (the old behavior committed a dead link). Toasts are IME-suppressed
                // on Android 13+; the suggestion bar is the feedback surface (#156 pattern).
                keyboard2.showSuggestionBarMessage(context.getString(R.string.gif_msg_media_unavailable))
        }
    }

    /**
     * Commit the local animated WebP via commitContent — the same machinery as
     * KeyEventHandler.paste_media_from_clipboard_pane, re-implemented here because that
     * helper resolves paths through ClipboardMediaManager (clipboard_media/), not the
     * GIF store. Returns false when the editor refuses.
     */
    private fun commitGifMedia(file: java.io.File, gif: Gif, ic: InputConnection): Boolean {
        return try {
            val editorInfo = keyboard2.currentInputEditorInfo ?: return false
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val description = android.content.ClipDescription(
                gif.getDisplayName(), arrayOf(GifInsertPolicy.MIME_WEBP)
            )
            val inputContentInfo = androidx.core.view.inputmethod.InputContentInfoCompat(
                contentUri, description, null
            )
            val flags = androidx.core.view.inputmethod.InputConnectionCompat
                .INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            val committed = androidx.core.view.inputmethod.InputConnectionCompat.commitContent(
                ic, editorInfo, inputContentInfo, flags, null
            )
            android.util.Log.d("GifPanel", "commitContent result=$committed")
            committed
        } catch (e: Exception) {
            android.util.Log.w(TAG, "GIF commitContent failed: ${e.message}")
            false
        }
    }

    /** Put the local GIF media on the system clipboard (same shape as long-press "Copy GIF"). */
    private fun copyGifMediaToClipboard(file: java.io.File, gif: Gif) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val clip = android.content.ClipData.newUri(
                context.contentResolver, gif.getDisplayName(), uri
            )
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(clip)
            keyboard2.showSuggestionBarMessage(context.getString(R.string.gif_msg_gif_copied))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Copy GIF failed: ${e.message}")
        }
    }

    /**
     * Show an IME-safe popup for GIF long-press.
     * Uses PopupWindow (not PopupMenu) because IME views lack a window token
     * for standard menus. Same approach as EmojiTooltipManager.
     */
    private fun showGifPopup(gif: Gif, anchor: View) {
        val density = context.resources.displayMetrics.density
        val padding = (12 * density).toInt()
        val cornerRadius = 8 * density

        val background = GradientDrawable().apply {
            setColor(0xEE222222.toInt())
            this.cornerRadius = cornerRadius
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            this.background = background
        }

        // Title: display name of the GIF
        val titleView = TextView(context).apply {
            text = gif.getDisplayName()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            maxWidth = (220 * density).toInt()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(titleView)

        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = false // Must be false — focusable popup steals focus from content pane
            isTouchable = true
            isOutsideTouchable = true
            elevation = 8f
        }

        // Helper to create a tappable action row
        fun addAction(label: String, onClick: () -> Unit) {
            val actionView = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF90CAF9.toInt()) // Light blue
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                setOnClickListener {
                    onClick()
                    popup.dismiss()
                }
            }
            container.addView(actionView)
        }

        // "Copy URL" — copies the Giphy animated GIF URL
        val url = gif.getGiphyUrl()
        if (url != null) {
            addAction(context.getString(R.string.gif_action_copy_url)) {
                val clip = android.content.ClipData.newPlainText("GIF URL", url)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(clip)
                // E-10: suggestion-bar feedback — Toasts are IME-suppressed on Android 13+ (#156)
                keyboard2.showSuggestionBarMessage(context.getString(R.string.gif_msg_url_copied))
            }
        }

        // "Copy GIF" — only shown when full animated file exists on device
        val fullGifFile = java.io.File(context.filesDir, gif.getFullPath())
        if (fullGifFile.exists()) {
            addAction(context.getString(R.string.gif_action_copy_gif)) {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        fullGifFile
                    )
                    val clip = android.content.ClipData.newUri(
                        context.contentResolver,
                        gif.getDisplayName(),
                        uri
                    )
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(clip)
                    // E-10: suggestion-bar feedback — Toasts are IME-suppressed on Android 13+ (#156)
                    keyboard2.showSuggestionBarMessage(context.getString(R.string.gif_msg_gif_copied))
                } catch (e: Exception) {
                    android.util.Log.w("KeyboardReceiver", "Copy GIF failed: ${e.message}")
                }
            }
        }

        // "Copy keywords"
        val keywords = gif.getKeywords()
        if (keywords.isNotEmpty()) {
            addAction(context.getString(R.string.gif_action_copy_keywords)) {
                val clip = android.content.ClipData.newPlainText("GIF keywords", keywords.joinToString(", "))
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(clip)
                // E-10: suggestion-bar feedback — Toasts are IME-suppressed on Android 13+ (#156)
                keyboard2.showSuggestionBarMessage(context.getString(R.string.gif_msg_keywords_copied))
            }
        }

        // Show anchored above the tapped cell (same positioning as EmojiTooltipManager)
        try {
            container.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val popupWidth = container.measuredWidth
            val popupHeight = container.measuredHeight
            val offsetX = (anchor.width - popupWidth) / 2
            val offsetY = -anchor.height - popupHeight - (8 * density).toInt()
            popup.showAsDropDown(anchor, offsetX, offsetY, Gravity.TOP or Gravity.START)
        } catch (e: Exception) {
            android.util.Log.w("KeyboardReceiver", "GIF popup failed: ${e.message}")
        }
    }

    override fun set_shift_state(state: Boolean, lock: Boolean) {
        keyboardView.set_shift_state(state, lock)
    }

    override fun set_compose_pending(pending: Boolean) {
        keyboardView.set_compose_pending(pending)
    }

    override fun selection_state_changed(selectionIsOngoing: Boolean) {
        keyboardView.set_selection_state(selectionIsOngoing)
    }

    override fun getCurrentInputConnection(): InputConnection? {
        return keyboard2.currentInputConnection
    }

    override fun getCurrentEditorInfo(): EditorInfo? {
        return keyboard2.currentInputEditorInfo
    }

    override fun getContext(): Context {
        return context
    }

    // #156: private-copy feedback via the suggestion bar (Toasts are IME-suppressed on Android 13+).
    override fun onExplicitEditStarted() { inputCoordinator.resetSwipeData(); keyboard2.cancelContinuousSwipe() }

    override fun showPrivateCopyFeedback(message: String) {
        keyboard2.showSuggestionBarMessage(message)
    }

    override fun getHandler(): Handler {
        return handler
    }

    override fun handle_text_typed(text: String) {
        keyboard2.cancelContinuousSwipe()
        // Reset swipe tracking when regular typing occurs
        contextTracker.setWasLastInputSwipe(false)
        inputCoordinator.resetSwipeData()
        keyboard2.handleRegularTyping(text)
    }

    override fun handle_backspace() {
        keyboard2.handleBackspace()
    }

    override fun handle_non_text_input() {
        keyboard2.handleNonTextInput()
    }

    override fun handle_delete_last_word() {
        keyboard2.handleDeleteLastWord()
    }

    override fun isClipboardSearchMode(): Boolean {
        return clipboardManager.isInSearchMode()
    }

    override fun appendToClipboardSearch(text: String) {
        clipboardManager.appendToSearch(text)
    }

    override fun backspaceClipboardSearch() {
        clipboardManager.deleteFromSearch()
    }

    override fun exitClipboardSearchMode() {
        clipboardManager.clearSearch()
    }

    // Clipboard tag dialog mode — highest priority modal (checked before edit)
    override fun isClipboardTagMode(): Boolean {
        return clipboardManager.isInTagMode()
    }

    override fun insertToClipboardTag(text: String) {
        clipboardManager.insertToTag(text)
    }

    override fun backspaceClipboardTag() {
        clipboardManager.backspaceFromTag()
    }

    // Clipboard edit mode — delegates to ClipboardManager → ClipboardHistoryView
    override fun isClipboardEditMode(): Boolean {
        return clipboardManager.isInEditMode()
    }

    override fun insertToClipboardEdit(text: String) {
        clipboardManager.insertToEdit(text)
    }

    override fun backspaceClipboardEdit() {
        clipboardManager.backspaceFromEdit()
    }

    override fun pasteToClipboardEdit() {
        clipboardManager.pasteToEdit()
    }

    override fun cutFromClipboardEdit() {
        clipboardManager.cutFromEdit()
    }

    override fun selectAllClipboardEdit() {
        clipboardManager.selectAllInEdit()
    }

    override fun undoClipboardEdit() {
        clipboardManager.undoInEdit()
    }

    override fun redoClipboardEdit() {
        clipboardManager.redoInEdit()
    }

    override fun copyFromClipboardEdit() {
        clipboardManager.copyFromEdit()
    }

    override fun dispatchKeyToClipboardEdit(keyCode: Int) {
        clipboardManager.dispatchKeyToEdit(keyCode)
    }

    override fun navigateClipboardEdit(command: EditorTextNavigation.Command): Boolean =
        clipboardManager.navigateInEdit(command)

    // #41 v5: Emoji search routes typing to visible EditText (IME can't type into own views)
    override fun isEmojiPaneOpen(): Boolean {
        return emojiSearchManager?.isEmojiPaneOpen() ?: false
    }

    override fun appendToEmojiSearch(text: String) {
        emojiSearchManager?.appendToSearch(text)
    }

    override fun backspaceEmojiSearch() {
        emojiSearchManager?.backspaceSearch()
    }

    // GIF search routing — uses dedicated boolean flag like emoji/clipboard
    // (searchActive flag is independent of content pane state flags which get
    // reset by resetContentPaneState() on onFinishInputView)
    override fun isGifPaneOpen(): Boolean {
        return gifSearchActive
    }

    override fun appendToGifSearch(text: String) {
        // Use setText + setSelection (same pattern as EmojiSearchManager.appendToSearch)
        // EditText.append() doesn't reliably work when the IME owns the view
        val input = gifSearchInput ?: return
        val current = input.text?.toString() ?: ""
        val newText = current + text
        input.setText(newText)
        input.setSelection(newText.length)
    }

    override fun backspaceGifSearch() {
        val input = gifSearchInput ?: return
        val current = input.text?.toString() ?: ""
        if (current.isNotEmpty()) {
            val newText = current.dropLast(1)
            input.setText(newText)
            input.setSelection(newText.length)
        }
    }

    // Swipe typing into inline fields: each field's state / range replacement, read from and
    // written to the SAME views the tap-typing routes above use (setText + setSelection).
    override fun inlineFieldState(field: InlineField): InlineFieldState? = when (field) {
        InlineField.CLIPBOARD_TAG, InlineField.CLIPBOARD_EDIT, InlineField.CLIPBOARD_SEARCH ->
            clipboardManager.inlineFieldState(field)
        InlineField.EMOJI_SEARCH -> emojiSearchManager?.takeIf { it.isEmojiPaneOpen() }?.searchFieldState()
        InlineField.GIF_SEARCH -> gifSearchInput?.takeIf { gifSearchActive }?.let {
            InlineFieldState.appendOnly(it.text?.toString() ?: "", System.identityHashCode(it).toLong())
        }
    }

    override fun replaceInInlineField(field: InlineField, start: Int, end: Int, text: String): Boolean =
        when (field) {
            InlineField.CLIPBOARD_TAG, InlineField.CLIPBOARD_EDIT, InlineField.CLIPBOARD_SEARCH ->
                clipboardManager.replaceInInlineField(field, start, end, text)
            InlineField.EMOJI_SEARCH ->
                emojiSearchManager?.takeIf { it.isEmojiPaneOpen() }?.replaceSearchText(start, end, text) ?: false
            InlineField.GIF_SEARCH -> {
                val input = gifSearchInput?.takeIf { gifSearchActive }
                val newText = input?.let {
                    InlineFieldState.appendOnlyReplace(it.text?.toString() ?: "", start, end, text)
                }
                if (input != null && newText != null) {
                    input.setText(newText)
                    input.setSelection(newText.length)
                    true
                } else {
                    false
                }
            }
        }

    // #110: Backspace undo swipe — expose swipe state to KeyEventHandler
    override fun wasLastInputSwipe(): Boolean = contextTracker.wasLastInputSwipe()
    override fun getLastAutoInsertedWord(): String? = contextTracker.getLastAutoInsertedWord()
    override fun clearSwipeUndoState() {
        contextTracker.setWasLastInputSwipe(false)
        contextTracker.clearLastAutoInsertedWord()
        inputCoordinator.resetSwipeData()
    }

    // #110: Backspace undo autocorrect — expose autocorrect state to KeyEventHandler
    override fun getLastAutocorrectOriginalWord(): String? = contextTracker.getLastAutocorrectOriginalWord()
    override fun clearAutocorrectUndoState() {
        contextTracker.clearAutocorrectTracking()
        contextTracker.clearLastAutoInsertedWord()
        inputCoordinator.resetSwipeData()
    }

    companion object {
        private const val TAG = "KeyboardReceiver"

        /**
         * Logcat tag for the tools panel. Kept as its own constant because two different classes
         * log under it — this receiver (opens/closes) and nothing else — and because the CI smoke
         * script greps for it by name.
         */
        internal const val TAG_TOOLS = "MoreToolsPanel"

        /**
         * How much text before the cursor dictation looks at, purely to decide the joining space
         * ([DictationPolicy.commitPrefix]). One character would do; 32 keeps the call cheap to
         * reason about and is far below any editor's limit.
         */
        private const val JOIN_CONTEXT_CHARS = 32

        /**
         * Seam for the pane-sizing LayoutParams the three openers assign. Constructed here
         * rather than inline so the mock tier can intercept it (android.jar stub
         * constructors throw "Stub!"); behavior is identical to the previous inline
         * FrameLayout.LayoutParams(MATCH_PARENT, height).
         */
        @JvmStatic
        internal fun paneLayoutParams(height: Int): android.widget.FrameLayout.LayoutParams =
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                height
            )
    }
}
