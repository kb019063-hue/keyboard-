package tribixbite.cleverkeys.ui.settings.io

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SwipePerformanceStats
import tribixbite.cleverkeys.SettingsActivity

/**
 * "Export failed: <reason>" toast shared by the privacy-data exporters (perf stats, swipe data).
 * The reason is the localized [IoFailureClassifier] category; the exception is logged.
 */
internal fun SettingsActivity.toastExportFailed(e: Exception) {
    android.util.Log.w(SettingsActivity.TAG, "Privacy data export failed", e)
    Toast.makeText(
        this, getString(R.string.common_export_failed_detail, ioFailureText(e)), Toast.LENGTH_SHORT
    ).show()
}

/**
 * View collected swipe data in a dialog with pagination
 */
internal fun SettingsActivity.viewCollectedData() {
    collectedDataSearchQuery = ""
    collectedDataCurrentPage = 0
    loadCollectedDataPage()
    showCollectedDataViewer = true
}

/**
 * Load a page of collected data based on current search/pagination state
 */
internal fun SettingsActivity.loadCollectedDataPage() {
    val _self = this
    lifecycleScope.launch {
        try {
            val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
            val offset = collectedDataCurrentPage * collectedDataPageSize
            val query = collectedDataSearchQuery
            val needStats = collectedDataStats == null

            // 2026-11-21 (ml/ audit): the reads below ran on the main thread - lifecycleScope
            // dispatches on Dispatchers.Main.immediate - and none of them is small.
            // getStatistics() alone makes three separate passes over the whole table
            // (COUNT(*), GROUP BY source, COUNT(DISTINCT target_word)), and a page is 20 rows of
            // JSON parsed into SwipeMLData objects with their full trace-point lists. Wrapped so
            // the handler only touches the collectedData* fields back on the main thread.
            val loaded = withContext(Dispatchers.IO) {
                Triple(
                    dataStore.countSearchResults(query),
                    if (query.isEmpty()) {
                        dataStore.loadPaginatedData(collectedDataPageSize, offset)
                    } else {
                        dataStore.searchByWord(query, collectedDataPageSize, offset)
                    },
                    if (needStats) dataStore.getStatistics() else null,
                )
            }
            collectedDataTotalCount = loaded.first
            collectedDataList = loaded.second
            if (needStats) {
                collectedDataStats = loaded.third
            }
        } catch (e: Exception) {
            android.util.Log.w(SettingsActivity.TAG, "Loading collected swipe data failed", e)
            Toast.makeText(
                _self, getString(R.string.privacy_error_loading_data, ioFailureText(e)), Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/**
 * View performance statistics in a dialog
 */
internal fun SettingsActivity.viewPerfStats() {
    try {
        val stats = SwipePerformanceStats.getInstance(this)
        perfStatsSummary = stats.formatSummary()
        showPerfStatsViewer = true
    } catch (e: Exception) {
        android.util.Log.w(SettingsActivity.TAG, "Loading performance stats failed", e)
        Toast.makeText(
            this, getString(R.string.privacy_error_loading_stats, ioFailureText(e)), Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Export performance statistics to JSON file
 */
internal fun SettingsActivity.exportPerfStats() {
    try {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "perf_stats_${sdf.format(java.util.Date())}.json"
        perfStatsExportLauncher.launch(filename)
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

/**
 * Delete all collected swipe data with confirmation
 */
/**
 * Erase every stored swipe trace, behind a confirmation.
 *
 * @param onDeleted invoked on the main thread after the store is emptied, so the
 *   caller can re-read its own counts. Replaces the old `recreate()` call, which
 *   refreshed the stale stats by rebuilding the whole activity — collapsing every
 *   section and discarding the user's scroll position for a delete performed
 *   inside one collapsible block.
 */
internal fun SettingsActivity.deleteCollectedData(onDeleted: () -> Unit = {}) {
    android.app.AlertDialog.Builder(this)
        .setTitle(getString(R.string.privacy_delete_data_title))
        .setMessage(getString(R.string.privacy_delete_data_body))
        .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
            val _self = this
            lifecycleScope.launch {
                try {
                    val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
                    // 2026-11-21 (ml/ audit): DELETE over the whole table, off the main thread.
                    withContext(Dispatchers.IO) { dataStore.clearAllData() }
                    Toast.makeText(
                        _self, R.string.privacy_delete_data_done, Toast.LENGTH_SHORT
                    ).show()
                    onDeleted()
                } catch (e: Exception) {
                    android.util.Log.w(SettingsActivity.TAG, "Deleting collected swipe data failed", e)
                    Toast.makeText(
                        _self,
                        _self.getString(R.string.privacy_delete_data_error, _self.ioFailureText(e)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.performPerfStatsExport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val stats = SwipePerformanceStats.getInstance(_self)
                val json = org.json.JSONObject().apply {
                    put("export_timestamp", System.currentTimeMillis())
                    put("total_predictions", stats.getTotalPredictions())
                    put("total_inference_time_ms", stats.getTotalInferenceTime())
                    put("average_inference_time_ms", stats.getAverageInferenceTime())
                    put("total_selections", stats.getTotalSelections())
                    put("top1_selections", stats.getTop1Selections())
                    put("top3_selections", stats.getTop3Selections())
                    put("top1_accuracy_pct", stats.getTop1Accuracy())
                    put("top3_accuracy_pct", stats.getTop3Accuracy())
                    put("model_load_time_ms", stats.getModelLoadTime())
                    put("days_tracked", stats.getDaysSinceStart())
                    put("first_stat_timestamp", stats.getFirstStatTimestamp())
                }
                java.io.OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(json.toString(2))
                }
                Toast.makeText(_self, R.string.privacy_perf_stats_exported, Toast.LENGTH_SHORT).show()
            } ?: throw java.io.IOException(getString(R.string.privacy_export_open_failed))
        } catch (e: Exception) {
            toastExportFailed(e)
        }
    }
}
