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
 * - Tell the one after-refresh consumer about each preference change, once [Config] holds it
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

    /** Monotonic milliseconds. Replaced only by the internal test constructor. */
    private var clock: () -> Long = { System.nanoTime() / 1_000_000L }

    /** Set only by the internal test constructor; production resolves the main Looper lazily. */
    private var injectedScheduler: ConfigRefreshScheduler? = null

    /**
     * Constructor-scoped test seam (the [ConfigPropagator] idiom): a plain-JVM test has no main
     * Looper, so without this the deferred half of a burst could never be exercised off-device.
     */
    internal constructor(
        context: Context,
        config: Config,
        foldStateTracker: FoldStateTracker,
        scheduler: ConfigRefreshScheduler,
        clock: () -> Long
    ) : this(context, config, foldStateTracker) {
        this.injectedScheduler = scheduler
        this.clock = clock
    }

    /** `null` when the process has no main Looper (plain-JVM tests): no coalescing then. */
    private val scheduler: ConfigRefreshScheduler? by lazy {
        injectedScheduler ?: try {
            Looper.getMainLooper()?.let { MainLooperConfigRefreshScheduler(Handler(it)) }
        } catch (t: Throwable) {
            Log.w(TAG, "No main Looper available — preference bursts refresh synchronously", t)
            null
        }
    }

    // ── After-refresh delivery (2026-11-11) ──────────────────────────────────────────────────
    // The service has preference handling of its own — the one-handed root, the toolbar, the
    // strip opacity and height, floating and split — and every piece of it READS Config. It used
    // to be a second, independent SharedPreferences listener, which was wrong in two ways:
    //
    //  1. Android promises no order between listeners. SharedPreferencesImpl copies them into a
    //     HashSet built from a WeakHashMap's keys, so which of the two ran first depended on
    //     object hash codes and could differ from one process start to the next. Whenever the
    //     service ran first it applied the value from BEFORE the change.
    //  2. The burst coalescing above postpones this class's refresh for the 2nd..nth write of a
    //     burst — but the service's listener still ran at once, against a Config that would not
    //     be refreshed for another 100 ms, and was never run again when it finally was.
    //
    // Either way the open keyboard ended up one change behind the saved setting: one-handed mode
    // did nothing, or switched on when it had just been switched off. And its stop control
    // could not recover, because writing `false` over a stored `false` reports no change at all.
    // So that handling is now driven from HERE, strictly after the refresh it depends on, on
    // both the immediate and the trailing path. Pinned by ConfigurationBurstCoalescingTest.

    /** The one consumer told about each changed key once [Config] holds the new value. */
    private var afterRefreshListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Keys written since the last delivery, in first-write order. A set, so a slider that writes
     * one key thirty times in a drag costs its consumer one update rather than thirty.
     */
    private val owedKeys = LinkedHashSet<String?>()

    /** The preferences the owed keys belong to (the listener's first argument). */
    private var owedPrefs: SharedPreferences? = null

    /** Runs the one trailing refresh for a burst, then re-arms the leading edge. */
    private val trailingRefresh = Runnable {
        burstStartedAt = 0L
        refreshThenDeliver(null)
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
     * Sets the ONE consumer that handles preference changes by reading [Config].
     *
     * It is called with each changed key AFTER the refresh that covers it — at once for the
     * first write of a burst, after the single trailing refresh for the rest. Register it here
     * instead of on the SharedPreferences: a second listener there runs in an unspecified
     * order relative to this class and so can read a [Config] that has not been refreshed yet.
     *
     * @param listener the consumer, or `null` to stop deliveries (service teardown)
     */
    fun setAfterRefreshListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        afterRefreshListener = listener
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
        val now = clock()
        owedPrefs = prefs
        owedKeys.add(key)
        val s = scheduler
        if (s == null || shouldRefreshImmediately(burstStartedAt, now)) {
            // First change of a burst (or no Looper to wait on): apply it synchronously, exactly
            // as before. Keys still owed from a trailing refresh this one supersedes are
            // delivered with it — the refresh re-reads every preference, so it covers them too.
            burstStartedAt = now
            s?.cancel(trailingRefresh)
            refreshThenDeliver(key)
        } else {
            // Still inside a burst (a slider drag writes on every increment, and a multi-key
            // editor commit fires once per key): collapse the rest into one trailing refresh.
            // The key stays owed until that refresh has run.
            s.cancel(trailingRefresh)
            s.postDelayed(trailingRefresh, TRAILING_DELAY_MS)
        }
    }

    /**
     * Refresh [Config], then hand the after-refresh listener every key that refresh covered.
     *
     * The owed keys are taken BEFORE the refresh starts, for two reasons. A key written while
     * the refresh is running (a config listener that itself saves a preference, e.g. the current
     * layout) may or may not have been read by it, so it stays owed to the next refresh instead
     * of being delivered against a value that could be stale. And the consumer may write a
     * preference too, which re-enters [onSharedPreferenceChanged] and must find a clean slate
     * rather than a set that is being iterated.
     */
    private fun refreshThenDeliver(key: String?) {
        val covered = owedKeys.toList()
        owedKeys.clear()
        refreshNow(key)
        if (covered.isEmpty()) return
        val prefs = owedPrefs ?: return
        val listener = afterRefreshListener ?: return
        for (owed in covered) {
            try {
                listener.onSharedPreferenceChanged(prefs, owed)
            } catch (t: Throwable) {
                // Same guard as the config listeners above: a UI update must not take the IME down.
                Log.e(TAG, "After-refresh preference listener failed for key=$owed", t)
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

/**
 * Where the one trailing refresh of a preference burst waits. Production is the main Looper;
 * a plain-JVM test hands in a hand-cranked queue through [ConfigurationManager]'s internal
 * constructor so the deferred half can be driven without one.
 */
internal interface ConfigRefreshScheduler {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

/** [ConfigRefreshScheduler] on the main thread's queue. */
private class MainLooperConfigRefreshScheduler(private val handler: Handler) : ConfigRefreshScheduler {
    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}
