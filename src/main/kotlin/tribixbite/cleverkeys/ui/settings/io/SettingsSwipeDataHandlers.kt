package tribixbite.cleverkeys.ui.settings.io

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity

/** File-format names shown in the export toast; proper nouns, identical in every locale. */
private const val SWIPE_FORMAT_JSON = "JSON"
private const val SWIPE_FORMAT_NDJSON = "NDJSON"

/** "Exported N swipe entries to <format>" toast shared by both swipe-data exporters. */
private fun SettingsActivity.toastSwipeDataExported(count: Int, format: String) {
    Toast.makeText(
        this,
        resources.getQuantityString(R.plurals.privacy_swipe_data_exported, count, count, format),
        Toast.LENGTH_SHORT,
    ).show()
}

internal fun SettingsActivity.exportSwipeDataJSON() {
    try {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "swipe_data_${sdf.format(java.util.Date())}.json"
        swipeDataJsonExportLauncher.launch(filename)
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.exportSwipeDataNDJSON() {
    try {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "swipe_data_${sdf.format(java.util.Date())}.ndjson"
        swipeDataNdjsonExportLauncher.launch(filename)
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.performSwipeDataJsonExport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            // 2026-11-21 (ml/ audit): this ran on the main thread. lifecycleScope dispatches on
            // Dispatchers.Main.immediate, and the body below streams EVERY row of swipe_data
            // through a cursor into the caller's OutputStream - 4.3 KB of JSON per row at ~80
            // trace points, so 42 MB of writing at 10k rows with a SQLite cursor held open the
            // whole time. That is an ANR on the settings screen for anyone who has been using the
            // keyboard for a while, and the rotation/background case closes the output stream
            // mid-export. SettingsBackupHandlers, in this same directory, already wraps its
            // export in withContext(Dispatchers.IO); this file and its privacy sibling did not.
            val count = withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
                    dataStore.exportToJSON(outputStream)
                } ?: throw java.io.IOException(getString(R.string.privacy_export_open_failed))
            }
            toastSwipeDataExported(count, SWIPE_FORMAT_JSON)
        } catch (e: Exception) {
            toastExportFailed(e)
        }
    }
}

internal fun SettingsActivity.performSwipeDataNdjsonExport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            // Same main-thread export as performSwipeDataJsonExport above; see the note there.
            val count = withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
                    dataStore.exportToNDJSON(outputStream)
                } ?: throw java.io.IOException(getString(R.string.privacy_export_open_failed))
            }
            toastSwipeDataExported(count, SWIPE_FORMAT_NDJSON)
        } catch (e: Exception) {
            toastExportFailed(e)
        }
    }
}
