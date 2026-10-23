package tribixbite.cleverkeys

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import tribixbite.cleverkeys.R

/**
 * Translucent trampoline that asks for RECORD_AUDIO on behalf of the mic key (phase 4 part 1).
 *
 * An `InputMethodService` cannot request a runtime permission: there is no activity for the system
 * dialog to attach to, and a dialog built on the IME's own window token dies the moment the
 * keyboard hides. So the key starts this activity, which asks, reports the answer in one line and
 * closes -- the same shape as [ProcessTextActivity], the other trampoline this IME owns.
 *
 * Not exported, own task (`taskAffinity=""`), excluded from recents. Deliberately NOT `noHistory`:
 * the system permission dialog is a separate activity, so this one is stopped while the question is
 * on screen, and `noHistory` would finish it and lose the answer -- exactly the trap
 * [ProcessTextActivity]'s manifest comment documents.
 */
class VoicePermissionActivity : Activity() {

    private var messageView: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Translucent theme means the app behind shows through, so the panel carries its own scrim
        // and light text; otherwise the rationale is unreadable over whatever is on screen.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(SCRIM)
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }
        val message = TextView(this).apply {
            text = getString(R.string.voice_typing_permission_rationale)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val close = Button(this).apply {
            text = getString(R.string.common_close)
            setOnClickListener { finish() }
        }
        root.addView(message)
        root.addView(close)
        setContentView(root)
        messageView = message

        if (granted()) {
            // Already allowed (a re-press after granting, or a restore): nothing to ask, so say the
            // useful part and get out of the way.
            show(R.string.voice_typing_permission_granted, autoCloseMs = AUTO_CLOSE_MS)
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        // The one line CI and a curious user can both read to see what the dialog answered.
        Log.i(TAG, "permission result granted=$granted")
        if (granted) {
            // Close on its own: the user was typing, and the answer they want is "back to typing".
            show(R.string.voice_typing_permission_granted, autoCloseMs = AUTO_CLOSE_MS)
        } else {
            // Denied stays up until dismissed, because it explains why the mic key now does the
            // legacy IME switch instead of dictating.
            show(R.string.voice_typing_permission_denied, autoCloseMs = null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        messageView = null
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun show(textRes: Int, autoCloseMs: Long?) {
        messageView?.text = getString(textRes)
        if (autoCloseMs != null) main.postDelayed({ finish() }, autoCloseMs)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "VoicePermissionActivity"
        const val REQUEST_CODE = 4711
        const val AUTO_CLOSE_MS = 2500L
        const val SCRIM = 0xCC000000.toInt()
    }
}
