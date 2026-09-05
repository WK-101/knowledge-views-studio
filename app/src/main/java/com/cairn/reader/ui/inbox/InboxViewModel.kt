package com.cairn.reader.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.FeedUnread
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.audio.AudioPlayer
import com.cairn.reader.audio.SpeechText
import com.cairn.reader.audio.TtsReader
import com.cairn.reader.data.prefs.ListViewMode
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The list lenses on the inbox. */
enum class InboxFilter(val label: String) {
    UNREAD("Unread"),
    STARRED("Starred"),
    SAVED("Saved"),
    ALL("All"),
}

/** What the drawer is currently pointing the inbox at: everything, one feed, or a folder. */
sealed interface DrawerSelection {
    data object All : DrawerSelection
    data class Feed(val sourceId: String, val title: String) : DrawerSelection
    data class Folder(val name: String) : DrawerSelection
}

enum class InboxSort(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first") }

/** The two-stage swipe configuration surfaced to the list rows. */
data class SwipeConfig(
    val rightHalf: com.cairn.reader.data.prefs.SwipeAction = com.cairn.reader.data.prefs.SwipeAction.STAR,
    val rightFull: com.cairn.reader.data.prefs.SwipeAction = com.cairn.reader.data.prefs.SwipeAction.SAVE,
    val leftHalf: com.cairn.reader.data.prefs.SwipeAction = com.cairn.reader.data.prefs.SwipeAction.MARK_READ,
    val leftFull: com.cairn.reader.data.prefs.SwipeAction = com.cairn.reader.data.prefs.SwipeAction.ARCHIVE,
)

/** A transient snackbar; when [onAction] is set the UI shows an action button (usually "Undo"). */
data class Snack(val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

data class InboxUiState(
    val loading: Boolean = true,
    val items: List<ItemListRow> = emptyList(),
    val unread: Int = 0,
    val filter: InboxFilter = InboxFilter.UNREAD,
    val sort: InboxSort = InboxSort.NEWEST,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val feedRepository: FeedRepository,
    private val sourceRepository: com.cairn.reader.data.repo.SourceRepository,
    private val preferencesRepository: PreferencesRepository,
    private val ttsReader: TtsReader,
    private val audioPlayer: AudioPlayer,
) : ViewModel() {

    /** Unsubscribe from a feed straight from the drawer's long-press menu. */
    fun unsubscribe(sourceId: String) = viewModelScope.launch { sourceRepository.delete(sourceId) }

    // -- Per-feed settings from the drawer long-press ("Feed settings & folder") -----------
    val folders: StateFlow<List<String>> =
        sourceRepository.folders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadSource(id: String, onLoaded: (com.cairn.reader.data.db.SourceEntity?) -> Unit) =
        viewModelScope.launch { onLoaded(sourceRepository.get(id)) }

    fun renameFeed(id: String, title: String) = viewModelScope.launch { sourceRepository.setTitle(id, title) }
    fun setFeedFolder(id: String, folder: String?) = viewModelScope.launch { sourceRepository.setFolder(id, folder) }
    fun setFeedFullText(id: String, on: Boolean) = viewModelScope.launch { sourceRepository.setFullText(id, on) }
    fun setFeedNotify(id: String, on: Boolean) = viewModelScope.launch { sourceRepository.setNotify(id, on) }
    fun setFeedPodcast(id: String, on: Boolean) = viewModelScope.launch { sourceRepository.setPodcast(id, on) }
    fun setFeedUrl(id: String, url: String) = viewModelScope.launch { sourceRepository.setFeedUrl(id, url) }
    fun setFeedOpenIn(id: String, mode: String) = viewModelScope.launch { sourceRepository.setOpenIn(id, mode) }
    fun setFeedMaxItems(id: String, n: Int?) = viewModelScope.launch { sourceRepository.setMaxItems(id, n) }

    /** Playback state for the "Listen to all" queue, shared with the reader. */
    val tts: StateFlow<TtsReader.State> = ttsReader.state

    /** Podcast-episode playback state, shared with the reader. */
    val audio: StateFlow<AudioPlayer.State> = audioPlayer.state

    /** Queue the current list (up to 30 stories) for read-aloud, back-to-back. */
    fun listenAll() = viewModelScope.launch {
        val rows = state.value.items.take(30)
        val tracks = rows.mapNotNull { row ->
            val (title, body) = itemRepository.articleText(row.id) ?: return@mapNotNull null
            val chunks = SpeechText.chunks(title, body)
            if (chunks.isEmpty()) null else TtsReader.Track(title, chunks)
        }
        if (tracks.isEmpty()) {
            _snacks.emit(Snack("Nothing here to read aloud"))
        } else {
            audioPlayer.stop() // one thing plays at a time
            ttsReader.startQueue(tracks)
        }
    }

    fun listenToggle() = ttsReader.togglePlayPause()
    fun listenStop() = ttsReader.stop()
    fun listenSpeed(speed: Float) = ttsReader.setSpeed(speed)
    fun listenNext() = ttsReader.skipNext()
    fun listenPrev() = ttsReader.skipPrevious()

    fun audioToggle() = audioPlayer.togglePlayPause()
    fun audioSeek(deltaMs: Int) = audioPlayer.seekBy(deltaMs)
    fun audioStop() = audioPlayer.stop()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val viewMode: StateFlow<ListViewMode> =
        preferencesRepository.preferences
            .map { it.listViewMode }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListViewMode.CARD)

    fun setViewMode(mode: ListViewMode) = viewModelScope.launch { preferencesRepository.setListViewMode(mode) }

    /** Comfortable (false) vs compact (true) list density. */
    val compact: StateFlow<Boolean> =
        preferencesRepository.preferences.map { it.compactDensity }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The four two-stage swipe actions (right-half, right-full, left-half, left-full). */
    val swipeActions: StateFlow<SwipeConfig> =
        preferencesRepository.preferences
            .map { SwipeConfig(it.swipeRightHalf, it.swipeRightFull, it.swipeLeftHalf, it.swipeLeftFull) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SwipeConfig())

    private val _filter = MutableStateFlow(InboxFilter.UNREAD)
    private val _sort = MutableStateFlow(InboxSort.NEWEST)

    /** What the drawer points the list at: All Articles, one feed, or a whole folder. */
    private val _selection = MutableStateFlow<DrawerSelection>(DrawerSelection.All)
    val selection: StateFlow<DrawerSelection> = _selection.asStateFlow()

    val feeds: StateFlow<List<FeedUnread>> =
        itemRepository.feedUnread().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _snacks = MutableSharedFlow<Snack>(extraBufferCapacity = 8)
    val snacks = _snacks.asSharedFlow()

    init {
        // First run: subscribe to a few real feeds, then pull them in.
        viewModelScope.launch {
            if (feedRepository.seedDefaultFeedsIfEmpty()) refresh()
        }
    }

    private val rows = combine(_filter, _selection) { filter, selection -> filter to selection }
        .flatMapLatest { (filter, selection) ->
            val source = (selection as? DrawerSelection.Feed)?.sourceId
            val folder = (selection as? DrawerSelection.Folder)?.name
            when (filter) {
                InboxFilter.UNREAD -> itemRepository.inbox(source, folder)
                InboxFilter.STARRED -> itemRepository.starred(source, folder)
                InboxFilter.SAVED -> itemRepository.saved(source, folder)
                InboxFilter.ALL -> itemRepository.all(source, folder)
            }
        }

    /** A live text filter over the current inbox list (title / source / excerpt). */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setInboxQuery(value: String) { _query.value = value }

    private val filteredRows = combine(rows, preferencesRepository.preferences, _sort, _query) { list, prefs, sort, query ->
        var filtered = applyContentFilters(list, prefs.blockedKeywords, prefs.hideDuplicates)
        val q = query.trim()
        if (q.length >= 2) {
            filtered = filtered.filter { r ->
                r.title.contains(q, true) ||
                    (r.sourceTitle?.contains(q, true) == true) ||
                    (r.siteName?.contains(q, true) == true) ||
                    (r.excerpt?.contains(q, true) == true)
            }
        }
        // The DAO returns newest-first; only OLDEST needs a reversal.
        if (sort == InboxSort.OLDEST) filtered.reversed() else filtered
    }

    val state: StateFlow<InboxUiState> =
        combine(filteredRows, itemRepository.unreadCount(), _filter, _sort) { items, unread, filter, sort ->
            InboxUiState(loading = false, items = items, unread = unread, filter = filter, sort = sort)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InboxUiState(),
        )

    fun setFilter(filter: InboxFilter) {
        _filter.value = filter
    }

    fun setSort(sort: InboxSort) {
        _sort.value = sort
    }

    /** Mark every unread item in the current drawer scope read. */
    fun markAllRead() = viewModelScope.launch {
        val sel = _selection.value
        itemRepository.markAllRead(
            sourceId = (sel as? DrawerSelection.Feed)?.sourceId,
            folder = (sel as? DrawerSelection.Folder)?.name,
        )
        _snacks.emit(Snack("Marked all read"))
    }

    private fun scopeSource() = (_selection.value as? DrawerSelection.Feed)?.sourceId
    private fun scopeFolder() = (_selection.value as? DrawerSelection.Folder)?.name

    /** Mark everything newer than [row] (above it in the newest-first list) read. */
    fun markAboveRead(row: ItemListRow) = viewModelScope.launch {
        itemRepository.markReadNewerThan(scopeSource(), scopeFolder(), row.publishedAt ?: row.savedAt)
        _snacks.emit(Snack("Marked newer items read"))
    }

    /** Mark everything older than [row] (below it) read. */
    fun markBelowRead(row: ItemListRow) = viewModelScope.launch {
        itemRepository.markReadOlderThan(scopeSource(), scopeFolder(), row.publishedAt ?: row.savedAt)
        _snacks.emit(Snack("Marked older items read"))
    }

    /** Mark items older than 7 days read, within the current scope. */
    fun markOlderThan7dRead() = viewModelScope.launch {
        itemRepository.markReadOlderThan(scopeSource(), scopeFolder(), System.currentTimeMillis() - 7 * 86_400_000L)
        _snacks.emit(Snack("Marked items older than 7 days read"))
    }

    /** Mark a specific feed or folder read from the drawer's long-press menu. */
    fun markFeedRead(sourceId: String) = viewModelScope.launch {
        itemRepository.markAllRead(sourceId = sourceId, folder = null)
        _snacks.emit(Snack("Marked all read"))
    }

    fun markFolderRead(folder: String) = viewModelScope.launch {
        itemRepository.markAllRead(sourceId = null, folder = folder)
        _snacks.emit(Snack("Marked all read"))
    }

    /** All Articles — the whole inbox, unread first. */
    fun selectAll() {
        _selection.value = DrawerSelection.All
        _filter.value = InboxFilter.UNREAD
    }

    /** Starred hub — every starred story, regardless of feed. */
    fun selectStarred() {
        _selection.value = DrawerSelection.All
        _filter.value = InboxFilter.STARRED
    }

    fun selectFeed(sourceId: String, title: String) {
        _selection.value = DrawerSelection.Feed(sourceId, title)
    }

    fun selectFolder(name: String) {
        _selection.value = DrawerSelection.Folder(name)
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { feedRepository.syncAll() }
            _refreshing.value = false
        }
    }

    fun addFeed(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _refreshing.value = true
            val result = feedRepository.addFeedByUrl(trimmed)
            _refreshing.value = false
            result.fold(
                onSuccess = { _snacks.emit(Snack("Feed added")) },
                onFailure = {
                    // No direct feed — offer the no-RSS fallback right in the snackbar.
                    _snacks.emit(
                        Snack(it.message ?: "No feed found there", "Try Google News") {
                            followViaGoogleNews(trimmed)
                        },
                    )
                },
            )
        }
    }

    /** Follow a site with no RSS via a Google News "site:" feed. */
    fun followViaGoogleNews(url: String) = viewModelScope.launch {
        _refreshing.value = true
        val result = feedRepository.followViaGoogleNews(url)
        _refreshing.value = false
        _snacks.emit(
            Snack(
                result.fold(
                    onSuccess = { "Now following via Google News" },
                    onFailure = { it.message ?: "Couldn't follow that site" },
                ),
            ),
        )
    }

    private fun applyContentFilters(list: List<ItemListRow>, blocked: Set<String>, dedup: Boolean): List<ItemListRow> {
        var out = list
        if (blocked.isNotEmpty()) {
            out = out.filter { row ->
                val hay = (row.title + " " + (row.excerpt ?: "")).lowercase()
                blocked.none { it.isNotBlank() && hay.contains(it) }
            }
        }
        if (dedup) {
            val seen = HashSet<String>()
            out = out.filter { seen.add(it.title.trim().lowercase()) }
        }
        return out
    }

    fun markRead(id: String, read: Boolean = true) = viewModelScope.launch {
        itemRepository.setRead(id, read)
        _snacks.emit(
            Snack(if (read) "Marked read" else "Marked unread", "Undo") {
                viewModelScope.launch { itemRepository.setRead(id, !read) }
            },
        )
    }

    fun toggleStar(id: String, starred: Boolean) = viewModelScope.launch {
        itemRepository.setStarred(id, starred)
        _snacks.emit(
            Snack(if (starred) "Starred" else "Unstarred", "Undo") {
                viewModelScope.launch { itemRepository.setStarred(id, !starred) }
            },
        )
    }

    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch {
        itemRepository.setReadLater(id, save)
        _snacks.emit(
            Snack(if (save) "Saved for later" else "Removed from Saved", "Undo") {
                viewModelScope.launch { itemRepository.setReadLater(id, !save) }
            },
        )
    }

    fun archive(id: String) = viewModelScope.launch {
        itemRepository.setArchived(id, true)
        _snacks.emit(Snack("Archived", "Undo") { viewModelScope.launch { itemRepository.setArchived(id, false) } })
    }

    fun unarchive(id: String) = viewModelScope.launch { itemRepository.setArchived(id, false) }

    /** Permanently delete an item (with a brief Undo). Feeds keep everything otherwise. */
    fun delete(id: String) = viewModelScope.launch {
        val snapshot = feedRepository.deleteItem(id)
        _snacks.emit(
            Snack("Deleted", if (snapshot != null) "Undo" else null) {
                if (snapshot != null) viewModelScope.launch { feedRepository.restoreItem(snapshot) }
            },
        )
    }

    /** Make a permanent offline copy of an item from the list's long-press menu. */
    fun saveOffline(id: String) = viewModelScope.launch {
        _snacks.emit(Snack("Saving offline…"))
        val result = feedRepository.saveOffline(id)
        _snacks.emit(
            Snack(
                result.fold(
                    onSuccess = { n -> if (n > 0) "Saved offline · $n image${if (n == 1) "" else "s"}" else "Saved offline" },
                    onFailure = { it.message ?: "Couldn't save offline" },
                ),
            ),
        )
    }

    /** Perform a configurable swipe action on a row; each carries its own undo. */
    fun swipe(row: ItemListRow, action: com.cairn.reader.data.prefs.SwipeAction) {
        when (action) {
            com.cairn.reader.data.prefs.SwipeAction.MARK_READ -> markRead(row.id, !row.isRead)
            com.cairn.reader.data.prefs.SwipeAction.SAVE -> toggleSave(row.id, !row.isReadLater)
            com.cairn.reader.data.prefs.SwipeAction.STAR -> toggleStar(row.id, !row.isStarred)
            com.cairn.reader.data.prefs.SwipeAction.ARCHIVE -> archive(row.id)
            com.cairn.reader.data.prefs.SwipeAction.DELETE -> delete(row.id)
            com.cairn.reader.data.prefs.SwipeAction.NONE -> Unit
        }
    }
}
