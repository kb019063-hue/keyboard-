package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages keyboard configuration and notifies listeners of changes.
 *
 * This class centralizes configuration management and uses the observer pattern
 * to decouple config refresh from config propagation. Components that need to
 * respond to config changes should implement ConfigChangeListener.
 *
 * Responsibilities:
 * - Own and manage the Config instance
 * - Own and manage the FoldStateTracker
 * - Listen to SharedPreferences changes
 * - Refresh config when needed
 * - Notify registered listeners of changes
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.345).
 */
class ConfigurationManager(
    private val context: Context,
    private val config: Config,
    private val foldStateTracker: FoldStateTracker
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val listeners = mutableListOf<ConfigChangeListener>()

    // ── R2/N2: coalesced preference refreshes ────────────────────────────────────────────────
    // Every preference write used to rebuild the whole Config and fan out to every listener,
    // on the caller's (usually main) thread. A slider drag writes once per increment, so
    // dragging the keyboard height slider rebuilt the Config ~60x a second, between the frames
    // the finger was moving through. Now the FIRST write of a burst applies immediately (a
    // lone setting change behaves exactly as before, theme notification included) and writes
    // that follow within BURST_WINDOW_MS are merged into one trailing refresh.
    //
    // Trade-off, stated plainly: the second and later writes of a burst become visible ~100 ms
    // after the writes stop instead of instantly. Nothing reads a pref back between the writes
    // of a drag, and 100 ms is below the threshold where a settings change looks unresponsive.

    /** Monotonic millis of the write that started the current burst; 0 = no burst. */
    private var burstStartedAt = 0L

    /** `null` when the process has no main Looper (plain-JVM tests): no coalescing then. */
    private val handler: Handler? by lazy {
        try {
            Looper.getMainLooper()?.let { Handler(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "No main Looper available — preference bursts refresh synchronously", t)
            null
        }
    }

    /** Runs the one trailing refresh for a burst, then re-arms the leading edge. */
    private val trailingRefresh = Runnable {
        burstStartedAt = 0L
        refreshNow(null)
    }

    init {
        // Set up fold state callback to refresh config when device is folded/unfolded
        foldStateTracker.setChangedCallback {
            refresh(context.resources)
        }
    }

    /**
     * Gets the current Config instance.
     *
     * @return Current Config object (never null)
     */
    fun getConfig(): Config = config

    /**
     * Gets the FoldStateTracker instance.
     *
     * @return FoldStateTracker for monitoring device fold state
     */
    fun getFoldStateTracker(): FoldStateTracker = foldStateTracker

    /**
     * Registers a listener for config changes.
     *
     * @param listener ConfigChangeListener to be notified of changes
     */
    fun registerConfigChangeListener(listener: ConfigChangeListener?) {
        if (listener != null && listener !in listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @param listener ConfigChangeListener to remove
     */
    fun unregisterConfigChangeListener(listener: ConfigChangeListener?) {
        listeners.remove(listener)
    }

    /**
     * Refreshes the configuration from SharedPreferences.
     *
     * This method:
     * 1. Captures the previous theme
     * 2. Refreshes the Config object
     * 3. Notifies all registered listeners
     * 4. Sends theme change notification if theme changed
     *
     * @param res Resources for config loading
     */
    fun refresh(res: Resources) {
        val prevTheme = config.theme
        val prevThemeName = config.themeName

        // Refresh config from SharedPreferences
        try {
            config.refresh(res, foldStateTracker.isUnfolded())
        } catch (t: Throwable) {
            Log.e(TAG, "Config refresh failed", t)
            return // Don't notify listeners if config refresh itself failed
        }

        // Notify listeners of config change
        // Catch Throwable (not just Exception) to prevent OOM/Error from killing IME process
        for (listener in listeners) {
            try {
                listener.onConfigChanged(config)
            } catch (t: Throwable) {
                Log.e(TAG, "Config listener ${listener.javaClass.simpleName} failed", t)
            }
        }

        // Special notification for theme changes (requires view recreation)
        // Check both theme (style ID) and themeName (for runtime themes that share base style)
        val themeChanged = prevTheme != config.theme || prevThemeName != config.themeName
        if (themeChanged) {
            for (listener in listeners) {
                try {
                    listener.onThemeChanged(prevTheme, config.theme)
                } catch (t: Throwable) {
                    Log.e(TAG, "Theme change listener ${listener.javaClass.simpleName} failed", t)
                }
            }
        }
    }

    /**
     * Called when a SharedPreference changes.
     *
     * This is the entry point for all preference changes. It triggers
     * a config refresh which will notify all registered listeners.
     *
     * @param prefs SharedPreferences that changed
     * @param key Key of the preference that changed (may be null)
     */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        val now = System.nanoTime() / 1_000_000L
        if (shouldRefreshImmediately(burstStartedAt, now)) {
            // First change of a burst: apply it synchronously, exactly as before.
            burstStartedAt = now
            handler?.removeCallbacks(trailingRefresh)
            refreshNow(key)
        } else {
            // Still inside a burst (a slider drag writes on every increment, and a multi-key
            // editor commit fires once per key): collapse the rest into one trailing refresh.
            val h = handler
            if (h == null) {
                refreshNow(key)
            } else {
                h.removeCallbacks(trailingRefresh)
                h.postDelayed(trailingRefresh, TRAILING_DELAY_MS)
            }
        }
    }

    private fun refreshNow(key: String?) {
        try {
            refresh(context.resources)
        } catch (t: Throwable) {
            Log.e(TAG, "onSharedPreferenceChanged failed for key=$key", t)
        }
    }

    /**
     * Gets a debug string showing current state.
     * Useful for logging and troubleshooting.
     *
     * @return Human-readable state description
     */
    fun getDebugState(): String {
        return "ConfigurationManager{theme=${config.theme}, listeners=${listeners.size}, isUnfolded=${foldStateTracker.isUnfolded()}}"
    }

    companion object {
        private const val TAG = "ConfigurationManager"

        /** Consecutive preference writes closer together than this count as one burst. */
        private const val BURST_WINDOW_MS = 150L

        /**
         * `true` when a write at [now] (monotonic ms) starts a new burst and must be applied
         * synchronously — i.e. no burst is in progress (`lastBurstStart == 0`) or the previous
         * one began more than [BURST_WINDOW_MS] ago.
         *
         * Public only so the timing rule can be unit-tested without a Looper.
         */
        fun shouldRefreshImmediately(lastBurstStart: Long, now: Long): Boolean =
            lastBurstStart == 0L || now - lastBurstStart > BURST_WINDOW_MS

        /** How long after the last write of a burst the single trailing refresh runs. */
        private const val TRAILING_DELAY_MS = 100L
    }
}
