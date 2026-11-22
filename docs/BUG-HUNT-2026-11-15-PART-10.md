# Bug hunt, part 10 — `ml/`: the swipe-ML data store and the settings paths into it

Date: 2026-11-15 (work committed 2026-11-21)
Branch: `arena/cbf26556-keyboard`
Base: `d5daa37` (part 9, `gif/`)
Scope: `src/main/kotlin/tribixbite/cleverkeys/ml/` and the settings handlers that call it
(`ui/settings/io/SettingsSwipeDataHandlers.kt`, `SettingsPrivacyDataHandlers.kt`).

The first pass over this subsystem. Three of the four findings are user-reachable from the
Privacy and Swipe-data settings pages, which is what pulled the settings handlers into scope: a
store defect and the way the UI calls it are the same bug when the caller is on the wrong thread.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| M1 | Swipe-data export streams the **entire table** to the output stream on the **main thread** | **critical** (ANR) | **fixed** |
| M2 | The collected-data page load runs three full-table scans plus 20 JSON parses on the **main thread** | **high** (jank on a settings screen) | **fixed** |
| M3 | Export `statistics` counters are lifetime INSERT counts written under row-count names | **high** (misreports the user's own data) | **fixed** |
| M4 | `deleteEntry` matches on word + a 1-second timestamp window instead of the unique trace id, and runs off the store's executor | medium (latent — no production caller) | **fixed** |
| M5 | `is_exported` is written in three places and read in none | low (dead column) | reported |
| M6 | `searchByWord` interpolates the user's query into a `LIKE` pattern without escaping `%` / `_` | low (wrong results, not injection) | reported |
| M7 | `onUpgrade` is empty, so any future schema bump silently keeps the old table | low (future-facing) | reported |
| M8 | No test described the export schema, so the `statistics` block could change unnoticed | medium (process) | **fixed** |

Production files changed: 3. New files: `ml/SwipeMLExportStatisticsTest.kt` (4 tests). No SQL
schema change, no `DATABASE_VERSION` bump, no migration.

---

## M1 — the export ran on the main thread

`SettingsSwipeDataHandlers.performSwipeDataJsonExport` and its NDJSON sibling:

```kotlin
lifecycleScope.launch {          // ← Dispatchers.Main.immediate
    contentResolver.openOutputStream(uri)?.use { outputStream ->
        val count = dataStore.exportToJSON(outputStream)   // ← whole table, here
```

`lifecycleScope` launches on `Dispatchers.Main.immediate`. The store's streaming export walks a
`SQLiteDatabase` cursor over every row of `swipe_data` and writes each row's JSON straight to the
`ContentResolver` output stream — inline, on that thread, with the cursor open for the whole
duration.

At ~80 trace points a row is ~4.3 KB of JSON:

| rows | written on the UI thread | cursor held open |
|------|------------------------|------------------|
| 500 | 2.1 MB | whole export |
| 2 000 | 8.4 MB | whole export |
| 10 000 | 42 MB | whole export |
| 50 000 | 210 MB | whole export |

This is not jank, it is an ANR waiting for a user who has been using the keyboard for a few months.
It is also *aborted* rather than merely slow if the activity is rotated or backgrounded mid-export:
`lifecycleScope` cancels the coroutine and the `use {}` block closes the output stream under the
writer, leaving a truncated file behind.

The streaming design itself is right — the I-5 note above `exportToJSON(): File` explains that it
exists to avoid the old whole-table materialisation on a 256 MB heap. The work was moved off the
main thread, not restructured.

**Fix:** both exports wrap the open-write-close in `withContext(Dispatchers.IO)`, which is exactly
what `SettingsBackupHandlers` — in the same directory, same `lifecycleScope` pattern — already does
for config export. Only the toast moved back onto the main thread, because `Toast` is a UI call.

## M2 — the collected-data page load ran on the main thread

`SettingsPrivacyDataHandlers.loadCollectedDataPage` had the same shape:

```kotlin
lifecycleScope.launch {
    collectedDataTotalCount = dataStore.countSearchResults(...)   // full scan
    collectedDataList = dataStore.loadPaginatedData(20, offset)   // 20 JSON parses
    collectedDataStats = dataStore.getStatistics()                // THREE full scans
```

`getStatistics()` is the expensive one: `COUNT(*)`, a `GROUP BY collection_source`, and
`COUNT(DISTINCT target_word)` — three independent passes over the whole table, none of them
covered by an index that would make them cheap. At 10 000 rows that is ~42 MB of table read three
times to redraw a dialog the user just opened.

**Fix:** the three reads are collected inside one `withContext(Dispatchers.IO)`. The values the
coroutine needs are read into locals *before* the hop, so the handler does not touch Android
lifecycle-backed properties from a background thread, and the assignment back into
`collectedData*` happens after `withContext` returns — on the main thread, where the property
delegates expect to be.

`deleteCollectedData`'s `clearAllData()` got the same treatment: a `DELETE` over the whole table.

## M3 — the export's statistics described a different population than the export

The `statistics` object at the tail of every JSON export:

```json
"statistics": { "total_swipes": 5000, "calibration_swipes": 800, "user_swipes": 400 }
```

Those three come from `SharedPreferences` and are **lifetime insert counters**, not counts of what
is in the file:

- `updateStatistics()` is called from exactly one place, `storeSwipeData`. Every
  `storeSwipeDataBatch` insert is invisible to them.
- Nothing ever decrements them. `performRetentionCleanup` deletes rows without touching them,
  `deleteBySource` deletes rows without touching them, `relabelRow` rewrites without touching them.
  Only `clearAllData` clears them, and it clears the whole preferences file.

So a user who collected 5 000 swipes and let the default 90-day retention remove all of them
exported this:

```json
{
  "data": [],
  "total_samples": 0,
  "statistics": { "total_swipes": 5000, ... }
}
```

`total_samples` was already correct — it is the writer's own counter. The `statistics` block
contradicted it, in the file's most prominent place, using names (`total_swipes`,
`calibration_swipes`, `user_swipes`) that read as row counts.

**Fix:** `total_swipes` now carries the number of rows actually written, and the counters are kept
under names that say what they are — `lifetime_inserts`, `lifetime_calibration_inserts`,
`lifetime_user_selection_inserts`. The per-source split is not recoverable from the file without a
second scan, and the counters are genuinely useful for "has this device ever collected anything",
so they were relabelled rather than dropped.

Nothing in the tree read those keys (`SwipeMLDataStore` was their only writer, and `grep` over
`src/`, `docs/` and `tools/` found no reader), so the rename breaks no consumer.

The block moved into `SwipeMLDataStore.statisticsJson()`, a pure builder, for the same reason
`relabelRow` takes a `newValues` factory: so the SQLite-free test tier can assert it.

## M4 — `deleteEntry` deleted by the wrong key, on the wrong thread

```kotlin
db.delete(TABLE_SWIPES,
    "$COL_TARGET_WORD=? AND $COL_TIMESTAMP BETWEEN ? AND ?",
    arrayOf(word, (ts-1000).toString(), (ts+1000).toString()))
```

`trace_id` is `UNIQUE NOT NULL` and is the identity of a row: `relabelRow` keys on it, the insert
path keys on it, the duplicate check keys on it. `deleteEntry` was the one method that did not.
Two swipes of the same word inside the same second are indistinguishable to that `WHERE` clause, so
deleting one deleted both; and a stored timestamp more than a second away matched nothing, silently.

It also ran synchronously on the caller's thread, while every other write in the class goes through
`_executor` — the single-threaded executor whose entire purpose is to serialize this store's
writers. A synchronous delete can interleave with a queued insert.

**Fix:** keyed on `trace_id`, and moved onto `_executor`. The log line no longer names the word
(an ungated line must not carry what the user typed).

Severity note: `grep` finds **no production caller** — only `androidTest/SwipeMLDataStoreTest`.
This is a latent-defect fix, and it is recorded as such rather than as a user-visible one.

## M5 — `is_exported` is write-only

`COL_IS_EXPORTED` is `DEFAULT 0`, set to `0` on insert, set to `1` by `markAllAsExported()` after
every export, and set back to `0` by `relabelRow` so that "the next export carries the corrected
label". No query in the class filters on it — `loadAllData`, `loadRecentData`,
`loadPaginatedData`, `searchByWord`, `exportToJSON`, `exportToNDJSON` and `getStatistics` all read
every row regardless.

So the column costs a per-row write on every export and changes no behaviour. **Reported, not
fixed:** dropping it means a `DATABASE_VERSION` bump and an `onUpgrade` that is currently empty
(M7), which is a bigger and riskier change than this pass should make. `relabelRow`'s KDoc claim
that the cleared flag matters should be corrected when the column is either used or removed.

## M6 — `LIKE` patterns are not escaped

`searchByWord(query, ...)` and `countSearchResults(query)` both build `"%$query%"` and pass it as a
`LIKE` argument. Parameters make this safe from injection, but `%` and `_` are still wildcards
*inside* the parameter: typing `_` matches any single character and `%` matches everything. A user
searching their collected data for a literal underscore gets a wildcard match.

**Reported, not fixed.** The fix is an `ESCAPE` clause plus escaping the two characters in the
bound value; it belongs with the same kind of fix in the GIF FTS path (`GifFtsSanitizer` already
exists for that subsystem) and doing one without the other would leave the codebase inconsistent.
It also has the lowest impact of anything in this report — wrong search results, not lost data.

## M7 — `onUpgrade` accepts any version bump silently

```kotlin
override fun onUpgrade(db, oldVersion, newVersion) {
    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.w(TAG, "Upgrading database ...")
}
```

A `DATABASE_VERSION` bump today would keep the existing table and change nothing, with the only
signal being a verbose-gated log line. **Reported, not fixed:** there is nothing to migrate yet at
version 1, and adding speculative migration code is worse than a documented gap. It is listed
because M5 is the change most likely to trip it, and because the next person to bump the version
should not assume this method does something.

## Checked and found sound

- **`storeSwipeData`'s duplicate check.** Queries `trace_id` before inserting. Redundant with the
  `UNIQUE` constraint plus `CONFLICT_IGNORE` in the batch path, but not wrong — it is what keeps a
  duplicate out of the statistics counter.
- **`relabelRow`.** Keys on `trace_id`, updates the column *and* the JSON blob, records
  `metadata.corrected_from` without overwriting an earlier value, and leaves
  `collection_source` alone so per-source statistics do not move. Covered by
  `SwipeMLRelabelStoreTest`.
- **Streaming exports.** Both overloads walk a cursor without materialising the table; the
  `exportToJSON(): File` path and the `OutputStream` path share the same body. I-5's fix holds.
- **`performRetentionCleanup`.** Runs on `_executor`, deletes by cutoff then applies a row cap with
  a `NOT IN (SELECT ... LIMIT ?)` subquery; both statements inside one try/catch with a
  `writableDatabase` obtained once. Sound.
- **The singleton.** `@Volatile` + double-checked locking over `applicationContext`, so it cannot
  leak an Activity or a Service. Correct.
- **`isValid()`.** Rejects fewer than 2 points, fewer than 2 registered keys, an empty target word,
  and any point outside `0f..1f`. The defaults are checked against `SwipeMLData`'s own
  normalisation contract.
- **`SwipeMLData.addRawPoint` / `calculateStatistics`.** Normalisation is by screen dimensions,
  matching the `KeyGeom` doc's "recover comparable px" claim; `straightnessRatio` guards the zero
  path.
- **`PlaygroundPayload` / `PlaygroundTraceRecorder`.** Has its own tests
  (`PlaygroundPayloadTest`, `PlaygroundRecordingScopeTest`) and was not touched.

## What this pass did not do

- **Did not read `SwipeMLData.kt` (532 lines) line by line.** I read the provenance constants,
  `relabelJson`, `isValid`, `calculateStatistics` and the field list — everything the store's
  behaviour depends on — but the enrichment/candidate/geometry serialisation in the middle of the
  file was skimmed. It has its own tests (`SwipeMLDataEnrichmentTest`,
  `SwipeMLDataProvenanceTest`, `SwipeMLDataRelabelTest`).
- **Did not change the SQL schema.** M5 and M7 are both schema-shaped and both left reported.
- **Did not escape `LIKE` patterns** (M6) — see the reason there.
- **Did not add a test for M1 or M2.** Both are a missing `withContext` inside a function that
  needs `Context`, `ContentResolver` and a live `lifecycleScope`; there is no Robolectric or
  instrumented runner in this environment. The fix is verified by inspection and by compiling, not
  by an assertion. Stating that plainly is better than a test named after a path it does not
  exercise.
- **Did not check the toolbar/`SwipeDebugActivity` path beyond its threading** (it already uses
  `dbExecutor.execute`, which is correct).
- **Did not touch `MLDataCollector`** — read only, to confirm `relabelSwipe`'s trace id comes from
  the row that was actually stored.

## CI

Filled in below after the run.
