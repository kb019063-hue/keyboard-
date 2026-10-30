package tribixbite.cleverkeys

import android.util.Log

/**
 * Handles UI updates when SharedPreferences change.
 *
 * This handler consolidates UI update logic triggered by preference changes:
 * - Updates keyboard layout view when layout preferences change
 * - Updates suggestion bar opacity when opacity preference changes
 * - Schedules a swipe re-warm after a custom/disabled-word change (ARC-082)
 *
 * Language changes are NOT handled here any more (GH #186/#61): the active language now also
 * changes on a layout switch, which touches no language preference key, so the key-based
 * reload that lived here could not see it. Every language change — preference or layout —
 * goes through [ActiveLanguageSync], fed from `CleverKeysService.onConfigChanged`.
 *
 * Note: ConfigurationManager is the primary SharedPreferences listener and
 * handles config refresh. This handler focuses on UI-specific updates.
 *
 * Extracted from CleverKeysService.onSharedPreferenceChanged() to reduce main class size.
 *
 * @since v1.32.412
 *
 * The views are read through providers on every change, never captured: a theme change
 * (including a switch onto a layout bound to another theme) replaces the keyboard view and the
 * suggestion bar, and a captured reference kept applying opacity and layout to the detached
 * originals for the rest of the service's life (2026-10-17).
 */
class PreferenceUIUpdateHandler(
    private val config: Config?,
    private val layoutBridge: LayoutBridge?,
    private val keyboardView: () -> Keyboard2View?,
    private val suggestionBar: () -> SuggestionBar?,
    private val toolbar: () -> KeyboardToolbar?,
    private val oneHanded: () -> OneHandedRoot?
) {
    /**
     * Handle UI updates for preference changes.
     *
     * @param key The preference key that changed (nullable)
     */
    fun handlePreferenceChange(key: String?) {
        // Update keyboard layout view
        updateKeyboardLayout()

        // Update suggestion bar opacity
        updateSuggestionBarOpacity()

        // Update the toolbar (visibility and button set)
        updateToolbar()

        // Update one-handed mode (enable, side, scale)
        updateOneHanded()

        // Re-warm the swipe engine after a dictionary mutation
        rewarmAfterDictionaryEditIfNeeded(key)
    }

    /**
     * Update keyboard layout view with current layout.
     */
    private fun updateKeyboardLayout() {
        val layout = layoutBridge?.getCurrentLayout()
        if (layout != null) {
            keyboardView()?.setKeyboard(layout)
        }
    }

    /**
     * Update suggestion bar opacity from config.
     */
    private fun updateSuggestionBarOpacity() {
        config?.let { cfg ->
            suggestionBar()?.setOpacity(cfg.suggestion_bar_opacity)
        }
    }

    /**
     * Re-sync the toolbar with the current config, so toggling it or reordering its buttons in
     * settings takes effect without restarting the IME. Read through a provider for the reason in
     * the class header: a theme change rebuilds the hierarchy, and a captured reference would keep
     * updating the detached original.
     */
    private fun updateToolbar() {
        config?.let { cfg ->
            toolbar()?.setOpacity(cfg.suggestion_bar_opacity)
            toolbar()?.applyFromConfig(cfg)
        }
    }

    /**
     * Re-apply one-handed mode from the current config, so toggling it or changing its side or
     * scale in settings takes effect on the open keyboard without restarting the IME. Read through
     * a provider for the same reason as [updateToolbar] — and here the reason is stronger, because
     * this root is also what `setInputView` hands the platform on every theme change and pane swap.
     */
    private fun updateOneHanded() {
        config?.let { cfg -> oneHanded()?.applyFromConfig(cfg) }
    }

    /**
     * Schedule a coalesced swipe re-warm when a custom-words, disabled-words or swipe-priority
     * preference of any language changed (ARC-082). All are inputs to the swipe lexicon's
     * content version.
     *
     * @param key The preference key that changed
     */
    private fun rewarmAfterDictionaryEditIfNeeded(key: String?) {
        if (key == null) return

        try {
            // ARC-082: re-warm after a DICTIONARY mutation, for the same reason ARC-014 does
            // after a language switch. Adding a word writes `custom_words_<lang>` and toggling
            // one writes `disabled_words_<lang>`; either changes the swipe lexicon's content
            // version, which invalidates the memoized trie / template index. Correctness is
            // unaffected (both adapters re-derive from that version) but without this hook the
            // rebuild happens lazily inside the NEXT swipe, on the decode thread, immediately
            // after the user deliberately edited their dictionary — the worst possible moment.
            //
            // Routed through SwipeRewarmScheduler rather than calling requestGeometricRewarm
            // directly: DictionaryManager.saveUserWords rewrites the whole preference per add,
            // so adding five words fires five callbacks, and the scheduler coalesces the burst
            // into one rebuild against the FINAL state (it then delegates to the same single
            // entry point, which keeps the serving-engine selection and the background slot).
            // A swipe-priority change (2026-10-08) is a lexicon-version input as well.
            if (LanguagePreferenceKeys.languageFromCustomWordsKey(key) != null ||
                LanguagePreferenceKeys.languageFromDisabledWordsKey(key) != null ||
                LanguagePreferenceKeys.languageFromSwipePriorityKey(key) != null
            ) {
                SwipeRewarmScheduler.requestRewarm()
            }
        } catch (t: Throwable) {
            // Catch Throwable (not just Exception) to prevent OOM/Error from killing IME
            Log.e(TAG, "Failed to schedule swipe re-warm after a dictionary edit: ${t.message}", t)
        }
    }

    companion object {
        // Log tag kept <=23 chars so Log.isLoggable does not crash on API <26 (LongLogTag lint).
        private const val TAG = "PrefUIUpdateHandler"

        /**
         * Create a PreferenceUIUpdateHandler.
         *
         * @param config The configuration
         * @param layoutBridge The layout bridge (nullable)
         * @param keyboardView Supplies the CURRENT keyboard view (nullable result)
         * @param suggestionBar Supplies the CURRENT suggestion bar (nullable result)
     * @param toolbar Supplies the CURRENT toolbar row (nullable result)
     * @param oneHanded Supplies the CURRENT one-handed root wrapper (nullable result)
         * @return A new PreferenceUIUpdateHandler instance
         */
        @JvmStatic
        fun create(
            config: Config?,
            layoutBridge: LayoutBridge?,
            keyboardView: () -> Keyboard2View?,
            suggestionBar: () -> SuggestionBar?,
            toolbar: () -> KeyboardToolbar?,
            oneHanded: () -> OneHandedRoot?
        ): PreferenceUIUpdateHandler {
            return PreferenceUIUpdateHandler(
                config, layoutBridge, keyboardView, suggestionBar, toolbar, oneHanded
            )
        }
    }
}
