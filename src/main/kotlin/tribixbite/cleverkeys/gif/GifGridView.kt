package tribixbite.cleverkeys.gif

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.*
import java.io.File

/**
 * Manages a RecyclerView-based GIF grid with Coil image loading and pagination.
 *
 * Replaces the legacy GridView+BaseAdapter approach with RecyclerView+ViewHolder
 * for better view recycling in the memory-constrained IME context.
 *
 * Pagination: 100 items per page (same as clipboard). When total items exceed
 * ITEMS_PER_PAGE, the onPaginationChanged callback fires with page info for
 * the UI to show prev/next controls.
 *
 * Coil handles:
 * - Async thumbnail loading from files
 * - Memory + disk caching with configurable limits
 * - Automatic cancellation on view recycling
 */
class GifGridManager(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private val columns: Int = 3
) {
    private var gifList: List<Gif> = emptyList()
    private var currentCategory: GifCategory = GifCategory.RECENTLY_USED
    private var currentSearchQuery: String = ""
    private var currentPage: Int = 0
    private var totalItems: Int = 0

    private val assetManager = GifAssetManager.getInstance(context)
    private val database = GifDatabase.getInstance(context)
    private val adapter = GifRecyclerAdapter()

    // Audit E-1: an uncaught exception in a root coroutine of this scope reaches the default
    // handler and KILLS THE IME PROCESS (the recordGifUsage upsert crash rode exactly this
    // path on API 24-28). Grid work is best-effort UI plumbing — log and survive.
    private val crashGuard = CoroutineExceptionHandler { _, t ->
        android.util.Log.e("GifGridManager", "GIF grid coroutine failed", t)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + crashGuard)
    private var searchJob: Job? = null

    /** Coil image loader with IME-friendly memory limits.
     * Default Coil cache is 25% of available RAM (~200-300MB on modern phones).
     * Cap at 32MB — Coil caches uncompressed ARGB_8888 bitmaps (~160KB at 200px),
     * so 32MB holds ~200 images — enough for two 100-item pages. Prevents IME
     * from bloating to 500MB+. */
    private val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(32 * 1024 * 1024) // 32 MB cap (vs ~250MB default)
                .build()
        }
        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
        .diskCachePolicy(coil.request.CachePolicy.DISABLED) // Files already on disk
        .crossfade(false) // Minimize animation overhead in IME
        .build()

    /** Callback for GIF selection (tap). */
    var onGifSelected: ((Gif) -> Unit)? = null

    /** Callback for long-press preview. */
    var onGifLongPress: ((Gif, View) -> Unit)? = null

    /**
     * Pagination state callback.
     * @param needsPagination true if total items exceed ITEMS_PER_PAGE
     * @param currentPage 1-indexed page number
     * @param totalPages total number of pages
     */
    var onPaginationChanged: ((needsPagination: Boolean, currentPage: Int, totalPages: Int) -> Unit)? = null

    /**
     * Audit E-5: fired on the MAIN thread every time an async search/load actually lands,
     * with the query it belongs to and the result count. The "No results" indicator must
     * key off this — reading [getResultCount] synchronously after [search] returns the
     * PREVIOUS list's size (search debounces 150 ms and queries on IO), so the indicator
     * was always one query stale and effectively never showed.
     */
    var onResultsChanged: ((query: String, count: Int) -> Unit)? = null

    init {
        recyclerView.layoutManager = GridLayoutManager(context, columns)
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.itemAnimator = null // No animations for perf

        // Load recently used on init, fall back to "All" if empty
        scope.launch { loadDefaultView() }
    }

    /**
     * Recently-used view, falling back to "All" when there is no usage history yet.
     *
     * Audit E-4: the fallback must also MOVE [currentCategory] to ALL — the old init left
     * it at RECENTLY_USED while displaying the All list, so the next-page reload fetched
     * recently-used (empty on a fresh install) and blanked the grid.
     */
    private suspend fun loadDefaultView() {
        currentCategory = GifCategory.RECENTLY_USED
        loadCategory(GifCategory.RECENTLY_USED)
        if (gifList.isEmpty()) {
            // No usage history yet — show all available GIFs
            currentCategory = GifCategory.ALL
            loadCategory(GifCategory.ALL)
        }
        // Announce the landING category, not the one that was asked for. loadDefaultView is also
        // what a cleared search restores, so the category bar has no other reliably-timed signal
        // that the grid moved to ALL on a fresh install. (2026-11-21, `gif`/look pass.)
        notifyPagination()
    }

    /**
     * Load GIFs for a specific category.
     */
    fun setCategory(category: GifCategory) {
        currentCategory = category
        currentSearchQuery = ""
        currentPage = 0
        searchJob?.cancel() // a pending debounced search must not overwrite the category view
        scope.launch { loadCategory(category) }
    }

    /**
     * Search GIFs by query.
     */
    // notifyDataSetChanged is correct here: the entire gifList is swapped for a new page/
    // search result set (every item changes), so there is no meaningful granular delta.
    @SuppressLint("NotifyDataSetChanged")
    fun search(query: String) {
        currentSearchQuery = query
        currentPage = 0
        // The caller invokes this on every keystroke with no debounce. Cancel the
        // in-flight search so fast typing doesn't queue N full FTS passes behind
        // one SQLite connection (issue #152), and coalesce keystrokes briefly.
        searchJob?.cancel()
        searchJob = scope.launch {
            if (query.isBlank()) {
                // Audit E-4: a cleared search restores the DEFAULT view (recent, falling
                // back to All + category tracking) — the old bare recently-used load
                // emptied the grid on any install without usage history. loadDefaultView
                // notifies adapter/pagination itself.
                currentPage = 0
                loadDefaultView()
            } else {
                delay(SEARCH_DEBOUNCE_MS) // cancelled by the next keystroke
                val page = database.searchGifs(query, ITEMS_PER_PAGE, 0)
                // A partial first page IS the complete result set — skip the
                // second FTS traversal that countSearchResults would run.
                totalItems = if (page.size < ITEMS_PER_PAGE) page.size
                    else database.countSearchResults(query)
                gifList = page
                adapter.notifyDataSetChanged()
                recyclerView.scrollToPosition(0)
                notifyPagination()
            }
            // Audit E-5: report the landed result count for THIS query (main thread here).
            onResultsChanged?.invoke(query, gifList.size)
        }
    }

    /** Current result count on this page. */
    fun getResultCount(): Int = gifList.size

    /**
     * The category actually on screen.
     *
     * Not the same thing as the last one requested: [loadDefaultView] opens on RECENTLY_USED and
     * silently moves to ALL when there is no usage history, so on a fresh install the grid is
     * showing ALL while the caller asked for RECENTLY_USED. The category bar keys its selected
     * state off this, not off what it tapped.
     */
    fun shownCategory(): GifCategory = currentCategory

    /** Navigate to next page. */
    fun nextPage() {
        if (!hasNextPage()) return
        currentPage++
        scope.launch { reloadCurrentView() }
    }

    /** Navigate to previous page. */
    fun previousPage() {
        if (currentPage <= 0) return
        currentPage--
        scope.launch { reloadCurrentView() }
    }

    fun hasNextPage(): Boolean = (currentPage + 1) * ITEMS_PER_PAGE < totalItems
    fun hasPreviousPage(): Boolean = currentPage > 0

    // notifyDataSetChanged is correct here: the entire gifList is swapped for a new category
    // page (every item changes), so there is no meaningful granular delta.
    @SuppressLint("NotifyDataSetChanged")
    private suspend fun loadCategory(category: GifCategory) {
        totalItems = database.getCategoryCount(category)
        gifList = database.getGifsByCategory(category, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
        withContext(Dispatchers.Main) {
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            notifyPagination()
        }
    }

    /** Reload current view (category or search) at current page offset. */
    // notifyDataSetChanged is correct here: the entire gifList is swapped for a new page
    // (every item changes), so there is no meaningful granular delta.
    @SuppressLint("NotifyDataSetChanged")
    private suspend fun reloadCurrentView() {
        if (currentSearchQuery.isNotBlank()) {
            gifList = database.searchGifs(currentSearchQuery, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
        } else {
            gifList = database.getGifsByCategory(currentCategory, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
        }
        withContext(Dispatchers.Main) {
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            notifyPagination()
        }
    }

    private fun notifyPagination() {
        val totalPages = getTotalPages()
        onPaginationChanged?.invoke(
            totalItems > ITEMS_PER_PAGE,
            currentPage + 1,  // 1-indexed for display
            totalPages
        )
    }

    private fun getTotalPages(): Int {
        return if (totalItems <= 0) 1
        else (totalItems + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
    }

    /** Clean up resources. */
    fun destroy() {
        scope.cancel()
        imageLoader.shutdown()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RecyclerView Adapter + ViewHolder
    // ─────────────────────────────────────────────────────────────────────────

    private inner class GifViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView as ImageView

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val gif = gifList.getOrNull(pos) ?: return@setOnClickListener
                scope.launch { database.recordGifUsage(gif.id) }
                onGifSelected?.invoke(gif)
            }
            itemView.setOnLongClickListener { v ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val gif = gifList.getOrNull(pos) ?: return@setOnLongClickListener false
                onGifLongPress?.invoke(gif, v)
                true
            }
        }

        fun bind(gif: Gif) {
            val thumbFile = File(context.filesDir, gif.getThumbnailPath())

            if (thumbFile.exists()) {
                val request = ImageRequest.Builder(context)
                    .data(thumbFile)
                    .target(imageView)
                    .scale(Scale.FILL)
                    .size(THUMB_SIZE_PX)
                    .build()
                imageLoader.enqueue(request)
            } else {
                imageView.setImageDrawable(null)
            }
        }
    }

    private inner class GifRecyclerAdapter : RecyclerView.Adapter<GifViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
            val imageView = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    squareCellPx(parent)
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                // CENTER_CROP with a fixed width means the height must follow the width, or the
                // cell crops unevenly. Do not set adjustViewBounds: it makes the ImageView
                // re-derive its own bounds from the drawable and fights the fixed cell.
                val pad = GifGridGeometry.dpToPx(2, parent.context.resources.displayMetrics.density)
                setPadding(pad, pad, pad, pad)
            }
            return GifViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
            gifList.getOrNull(position)?.let { holder.bind(it) }
        }

        override fun getItemCount(): Int = gifList.size

        override fun onViewRecycled(holder: GifViewHolder) {
            // Coil cancels requests automatically on target reuse,
            // but clear drawable to free memory immediately
            holder.imageView.setImageDrawable(null)
        }
    }

    /**
     * Side of one grid cell, in pixels: the pane's width divided into [columns] equal cells.
     *
     * 2026-11-21 (`gif`/look pass): the cell height was the literal `dpToPx(80)` while only the
     * COLUMN COUNT is configurable (`gif_thumbnail_columns`, 2..5 — `Defaults.GIF_THUMBNAIL_COLUMNS`
     * is 3). On a 392dp-wide pane that made one cell 194x80dp at two columns and 78x80dp at five:
     * a 2.4x swing in aspect ratio, so the same thumbnail was cropped hard through its sides at two
     * columns and nearly square at five. A square cell whose side comes from the width keeps every
     * cell the same shape at every column count, which is what the colour-coded emotion packs and
     * the Coil `Scale.FILL` request both assume.
     *
     * The width is read from the RecyclerView rather than the screen: the pane is the keyboard's
     * width, which is not the same thing on a split or floating layout.
     */
    private fun squareCellPx(parent: ViewGroup): Int {
        val usable = (parent.width - parent.paddingLeft - parent.paddingRight)
            .takeIf { it > 0 }
            // Before the first layout pass the width is 0. Fall back to a third of the screen,
            // which is the default column count and corrects itself on the first rebind.
            ?: (parent.context.resources.displayMetrics.widthPixels / columns)
        return GifGridGeometry.cellSizePx(usable, 0, columns, parent.context.resources.displayMetrics.density)
    }

    companion object {
        /** Items per page — matches clipboard pagination (100 items). */
        const val ITEMS_PER_PAGE = 100

        /**
         * Keystroke coalescing window for search-as-you-type (issue #152).
         *
         * Raised from 150 ms on 2026-11-21 (`gif`/tool pass). 150 ms is shorter than the gap
         * between keystrokes for anyone typing at a normal pace, so it coalesced nothing: at
         * 250 ms per character every single keystroke started its own FTS5 MATCH over the pack
         * index plus a 100-row read, on the IME's one SQLite connection, while the user was
         * typing into the keyboard. `searchJob.cancel()` only drops the coroutine; it does not
         * interrupt a query already inside SQLite, so the discarded passes still cost a full
         * traversal each. 300 ms is the value `DictionaryManagerActivity` already uses for the
         * same job, and it lets a whole short word settle into one pass.
         */
        private const val SEARCH_DEBOUNCE_MS = 300L

        /** Thumbnail decode target size (px). Keeps Coil memory low. */
        private const val THUMB_SIZE_PX = 200

    }
}
