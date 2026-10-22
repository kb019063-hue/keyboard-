package tribixbite.cleverkeys

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Plays the bundled key-press click (`res/raw/key_click.wav`, synthesised by
 * `scripts/generate_key_sound.py`).
 *
 * This is the audio half of key-press feedback; [VibratorCompat] is the haptic half. They share a
 * shape on purpose: a master toggle in [Config], a volume/enable check at play time, and a single
 * call site in `Keyboard2View.vibrate`, so a key that vibrates is a key that can click and the two
 * can never drift apart.
 *
 * Why SoundPool rather than `AudioManager.playSoundEffect`: the system call honours the global
 * "touch sounds" setting, so a user who enabled key sound HERE would hear nothing if they had
 * touch sounds off system-wide -- a setting that appears broken. SoundPool plays our own sample at
 * our own volume, independent of that global, exactly like the haptic path which passes
 * FLAG_IGNORE_GLOBAL_SETTING for the same reason.
 *
 * The pool is created lazily on first use (a keyboard that never enables sound never pays for it)
 * and released when the keyboard view detaches, so rotating or switching IMEs cannot leak a native
 * SoundPool. `play` is called on the main thread from the key event path; SoundPool.play itself is
 * thread-safe and returns immediately, mixing in its own thread, so it cannot add latency to the
 * keystroke.
 */
object KeyClickSound {
    private var pool: SoundPool? = null
    private var clickId = 0
    private var loaded = false

    private fun ensure(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(2) // a fast typist overlaps clicks; two streams cover it without queueing
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded = true }
        clickId = p.load(context.applicationContext, R.raw.key_click, 1)
        pool = p
    }

    /**
     * Click for one key press, if the user has key sound on.
     *
     * Silent when [Config.sound_enabled] is false, when the volume is 0, or when the sample has
     * not finished loading yet (the first millisecond after enable) -- dropping one click is
     * inaudible; blocking a keystroke on a SoundPool load would not be.
     */
    @JvmStatic
    fun play(context: Context, config: Config) {
        if (!config.sound_enabled) return
        val volume = config.sound_volume.coerceIn(0, 100) / 100f
        if (volume <= 0f) return
        ensure(context)
        val p = pool ?: return
        if (!loaded) return
        // left/right equal, normal priority, no loop, natural rate
        p.play(clickId, volume, volume, 1, 0, 1f)
    }

    /** Release the native pool. Safe to call when nothing was ever created. */
    @JvmStatic
    fun release() {
        pool?.release()
        pool = null
        clickId = 0
        loaded = false
    }
}
