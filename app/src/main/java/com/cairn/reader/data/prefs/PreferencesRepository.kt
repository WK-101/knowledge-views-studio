package com.cairn.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReaderTheme { DEFAULT, SEPIA, BLACK }

/** Reading typeface. SERIF/SANS are the bundled Newsreader/Inter; SYSTEM and MONO
 *  use device fonts (no APK weight). */
enum class ReaderFont(val label: String) {
    SERIF("Newsreader"), SANS("Inter"), SYSTEM("System"), MONO("Mono")
}

enum class ListViewMode { LIST, CARD, MAGAZINE }
enum class LibraryViewMode { LIST, GRID, MASONRY, HEADLINES }

/** A configurable list-row swipe action. */
enum class SwipeAction(val label: String) {
    NONE("Nothing"),
    MARK_READ("Mark read"),
    SAVE("Save for later"),
    STAR("Star"),
    ARCHIVE("Archive"),
}

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val listViewMode: ListViewMode = ListViewMode.CARD,
    val libraryViewMode: LibraryViewMode = LibraryViewMode.GRID,
    val readerFontScale: Float = 1.0f,
    val readerTheme: ReaderTheme = ReaderTheme.DEFAULT,
    val readerFont: ReaderFont = ReaderFont.SERIF,
    val readerJustify: Boolean = false,
    /** Show images inside the reader (lead image + inline). */
    val readerShowImages: Boolean = true,
    /** Auto-hide the reader's bars while scrolling down, reveal on scroll up. */
    val readerImmersive: Boolean = true,
    /** Full-screen reading: also hide the system status/navigation bars. */
    val readerFullScreen: Boolean = false,
    val blockedKeywords: Set<String> = emptySet(),
    val hideDuplicates: Boolean = false,
    val savedSearches: Set<String> = emptySet(),
    /** Remembered library view mode per scope key (e.g. "col:<id>"), Raindrop-style. */
    val libraryViewByScope: Map<String, LibraryViewMode> = emptyMap(),
    val seenOnboarding: Boolean = false,
    val swipeRight: SwipeAction = SwipeAction.SAVE,
    val swipeLeft: SwipeAction = SwipeAction.MARK_READ,
    // Two-stage swipe: a short (half) swipe and a long (full) swipe per direction, for finer control.
    val swipeRightHalf: SwipeAction = SwipeAction.STAR,
    val swipeRightFull: SwipeAction = SwipeAction.SAVE,
    val swipeLeftHalf: SwipeAction = SwipeAction.MARK_READ,
    val swipeLeftFull: SwipeAction = SwipeAction.ARCHIVE,
    val compactDensity: Boolean = false,
    // Offline & storage policy.
    /** Automatic background sync only runs on un-metered (Wi-Fi) networks. Manual refresh always runs. */
    val syncWifiOnly: Boolean = false,
    /** "Save offline" downloads the article's images for a true self-contained copy. */
    val cacheImagesOffline: Boolean = true,
    /** Only download offline-copy images on un-metered networks (text is always saved). */
    val imagesWifiOnly: Boolean = true,
    /** Keep at most this many items per feed (older, un-engaged ones are pruned). 0 = keep everything. */
    val maxItemsPerFeed: Int = 0,
    /** Also drop un-engaged items older than this many days on sync. 0 = no age limit. */
    val maxAgeDays: Int = 0,
    /** When on, retention never deletes unread articles (only read, un-engaged ones age out). */
    val keepUnread: Boolean = false,
    /** Which bottom-nav tabs are enabled, by destination name. Empty falls back to a sane default. */
    val bottomTabs: Set<String> = setOf("Inbox", "Library", "Discover", "Settings"),
    /** SAF tree URI where automatic backups are written; null = not configured. */
    val backupFolderUri: String? = null,
    /** How often to auto-back-up, in hours. 0 = off. */
    val backupFrequencyHours: Int = 0,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val LIST_VIEW = stringPreferencesKey("list_view_mode")
        val LIBRARY_VIEW = stringPreferencesKey("library_view_mode")
        val FONT_SCALE = floatPreferencesKey("reader_font_scale")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_FONT = stringPreferencesKey("reader_font")
        val READER_JUSTIFY = booleanPreferencesKey("reader_justify")
        val READER_IMAGES = booleanPreferencesKey("reader_show_images")
        val READER_IMMERSIVE = booleanPreferencesKey("reader_immersive")
        val READER_FULLSCREEN = booleanPreferencesKey("reader_fullscreen")
        val BLOCKED = stringSetPreferencesKey("blocked_keywords")
        val HIDE_DUP = booleanPreferencesKey("hide_duplicates")
        val SAVED_SEARCHES = stringSetPreferencesKey("saved_searches")
        val LIBRARY_VIEW_BY_SCOPE = stringSetPreferencesKey("library_view_by_scope")
        val SEEN_ONBOARDING = booleanPreferencesKey("seen_onboarding")
        val SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        val SWIPE_LEFT = stringPreferencesKey("swipe_left")
        val SWIPE_RIGHT_HALF = stringPreferencesKey("swipe_right_half")
        val SWIPE_RIGHT_FULL = stringPreferencesKey("swipe_right_full")
        val SWIPE_LEFT_HALF = stringPreferencesKey("swipe_left_half")
        val SWIPE_LEFT_FULL = stringPreferencesKey("swipe_left_full")
        val COMPACT_DENSITY = booleanPreferencesKey("compact_density")
        val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val CACHE_IMAGES = booleanPreferencesKey("cache_images_offline")
        val IMAGES_WIFI_ONLY = booleanPreferencesKey("images_wifi_only")
        val MAX_ITEMS_PER_FEED = intPreferencesKey("max_items_per_feed")
        val MAX_AGE_DAYS = intPreferencesKey("max_age_days")
        val KEEP_UNREAD = booleanPreferencesKey("keep_unread")
        val BOTTOM_TABS = stringSetPreferencesKey("bottom_tabs")
        val BACKUP_FOLDER = stringPreferencesKey("backup_folder_uri")
        val BACKUP_FREQ = intPreferencesKey("backup_frequency_hours")
    }

    /** Per-scope view entries are stored as "scopeKey<sep>MODE" in a string set. */
    private val scopeSep = ""

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { p ->
        AppPreferences(
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            listViewMode = p[Keys.LIST_VIEW]?.let { runCatching { ListViewMode.valueOf(it) }.getOrNull() } ?: ListViewMode.CARD,
            libraryViewMode = p[Keys.LIBRARY_VIEW]?.let { runCatching { LibraryViewMode.valueOf(it) }.getOrNull() } ?: LibraryViewMode.GRID,
            readerFontScale = p[Keys.FONT_SCALE] ?: 1.0f,
            readerTheme = p[Keys.READER_THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.DEFAULT,
            readerFont = p[Keys.READER_FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: ReaderFont.SERIF,
            readerJustify = p[Keys.READER_JUSTIFY] ?: false,
            readerShowImages = p[Keys.READER_IMAGES] ?: true,
            readerImmersive = p[Keys.READER_IMMERSIVE] ?: true,
            readerFullScreen = p[Keys.READER_FULLSCREEN] ?: false,
            blockedKeywords = p[Keys.BLOCKED] ?: emptySet(),
            hideDuplicates = p[Keys.HIDE_DUP] ?: false,
            savedSearches = p[Keys.SAVED_SEARCHES] ?: emptySet(),
            libraryViewByScope = (p[Keys.LIBRARY_VIEW_BY_SCOPE] ?: emptySet()).mapNotNull { entry ->
                val parts = entry.split(scopeSep)
                if (parts.size != 2) return@mapNotNull null
                val mode = runCatching { LibraryViewMode.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
                parts[0] to mode
            }.toMap(),
            seenOnboarding = p[Keys.SEEN_ONBOARDING] ?: false,
            swipeRight = p[Keys.SWIPE_RIGHT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.SAVE,
            swipeLeft = p[Keys.SWIPE_LEFT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.MARK_READ,
            // Full defaults to the old single-swipe choice so existing users keep their behavior.
            swipeRightHalf = p[Keys.SWIPE_RIGHT_HALF]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.STAR,
            swipeRightFull = p[Keys.SWIPE_RIGHT_FULL]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() }
                ?: p[Keys.SWIPE_RIGHT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.SAVE,
            swipeLeftHalf = p[Keys.SWIPE_LEFT_HALF]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.MARK_READ,
            swipeLeftFull = p[Keys.SWIPE_LEFT_FULL]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() }
                ?: p[Keys.SWIPE_LEFT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.ARCHIVE,
            compactDensity = p[Keys.COMPACT_DENSITY] ?: false,
            syncWifiOnly = p[Keys.SYNC_WIFI_ONLY] ?: false,
            cacheImagesOffline = p[Keys.CACHE_IMAGES] ?: true,
            imagesWifiOnly = p[Keys.IMAGES_WIFI_ONLY] ?: true,
            maxItemsPerFeed = p[Keys.MAX_ITEMS_PER_FEED] ?: 0,
            maxAgeDays = p[Keys.MAX_AGE_DAYS] ?: 0,
            keepUnread = p[Keys.KEEP_UNREAD] ?: false,
            bottomTabs = (p[Keys.BOTTOM_TABS] ?: setOf("Inbox", "Library", "Discover", "Settings")),
            backupFolderUri = p[Keys.BACKUP_FOLDER],
            backupFrequencyHours = p[Keys.BACKUP_FREQ] ?: 0,
        )
    }

    suspend fun setSeenOnboarding(seen: Boolean) = context.dataStore.edit { it[Keys.SEEN_ONBOARDING] = seen }
    suspend fun setSwipeRight(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_RIGHT] = action.name }
    suspend fun setSwipeLeft(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_LEFT] = action.name }
    suspend fun setSwipeRightHalf(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_RIGHT_HALF] = action.name }
    suspend fun setSwipeRightFull(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_RIGHT_FULL] = action.name }
    suspend fun setSwipeLeftHalf(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_LEFT_HALF] = action.name }
    suspend fun setSwipeLeftFull(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_LEFT_FULL] = action.name }
    suspend fun setCompactDensity(enabled: Boolean) = context.dataStore.edit { it[Keys.COMPACT_DENSITY] = enabled }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setListViewMode(mode: ListViewMode) = context.dataStore.edit { it[Keys.LIST_VIEW] = mode.name }
    suspend fun setLibraryViewMode(mode: LibraryViewMode) = context.dataStore.edit { it[Keys.LIBRARY_VIEW] = mode.name }
    suspend fun setReaderFontScale(scale: Float) = context.dataStore.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.8f, 1.8f) }
    suspend fun setReaderTheme(theme: ReaderTheme) = context.dataStore.edit { it[Keys.READER_THEME] = theme.name }
    suspend fun setReaderFont(font: ReaderFont) = context.dataStore.edit { it[Keys.READER_FONT] = font.name }
    suspend fun setReaderJustify(justify: Boolean) = context.dataStore.edit { it[Keys.READER_JUSTIFY] = justify }
    suspend fun setReaderShowImages(show: Boolean) = context.dataStore.edit { it[Keys.READER_IMAGES] = show }
    suspend fun setReaderImmersive(on: Boolean) = context.dataStore.edit { it[Keys.READER_IMMERSIVE] = on }
    suspend fun setReaderFullScreen(on: Boolean) = context.dataStore.edit { it[Keys.READER_FULLSCREEN] = on }

    suspend fun setHideDuplicates(enabled: Boolean) = context.dataStore.edit { it[Keys.HIDE_DUP] = enabled }

    suspend fun setSyncWifiOnly(enabled: Boolean) = context.dataStore.edit { it[Keys.SYNC_WIFI_ONLY] = enabled }
    suspend fun setCacheImagesOffline(enabled: Boolean) = context.dataStore.edit { it[Keys.CACHE_IMAGES] = enabled }
    suspend fun setImagesWifiOnly(enabled: Boolean) = context.dataStore.edit { it[Keys.IMAGES_WIFI_ONLY] = enabled }
    suspend fun setMaxItemsPerFeed(max: Int) = context.dataStore.edit { it[Keys.MAX_ITEMS_PER_FEED] = max.coerceAtLeast(0) }
    suspend fun setMaxAgeDays(days: Int) = context.dataStore.edit { it[Keys.MAX_AGE_DAYS] = days.coerceAtLeast(0) }
    suspend fun setKeepUnread(on: Boolean) = context.dataStore.edit { it[Keys.KEEP_UNREAD] = on }

    suspend fun setBackupFolder(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.BACKUP_FOLDER) else it[Keys.BACKUP_FOLDER] = uri
    }
    suspend fun setBackupFrequency(hours: Int) = context.dataStore.edit { it[Keys.BACKUP_FREQ] = hours.coerceAtLeast(0) }

    /** Enable/disable a bottom-nav tab by destination name; never lets the bar drop below one tab. */
    suspend fun setBottomTab(name: String, enabled: Boolean) = context.dataStore.edit { p ->
        val current = p[Keys.BOTTOM_TABS] ?: setOf("Inbox", "Library", "Discover", "Settings")
        val next = if (enabled) current + name else current - name
        p[Keys.BOTTOM_TABS] = if (next.isEmpty()) setOf("Inbox") else next
    }

    /** Remember the library view mode for a specific scope, and make it the global default too,
     *  so scopes you haven't customised inherit your latest choice. */
    suspend fun setLibraryViewForScope(scopeKey: String, mode: LibraryViewMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LIBRARY_VIEW] = mode.name
            val existing = prefs[Keys.LIBRARY_VIEW_BY_SCOPE] ?: emptySet()
            val kept = existing.filterNot { it.substringBefore(scopeSep) == scopeKey }.toSet()
            prefs[Keys.LIBRARY_VIEW_BY_SCOPE] = kept + "$scopeKey$scopeSep${mode.name}"
        }
    }

    suspend fun addBlockedKeyword(term: String) {
        val t = term.trim().lowercase()
        if (t.isBlank()) return
        context.dataStore.edit { it[Keys.BLOCKED] = (it[Keys.BLOCKED] ?: emptySet()) + t }
    }

    suspend fun removeBlockedKeyword(term: String) =
        context.dataStore.edit { it[Keys.BLOCKED] = (it[Keys.BLOCKED] ?: emptySet()) - term }

    suspend fun addSavedSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        context.dataStore.edit { it[Keys.SAVED_SEARCHES] = (it[Keys.SAVED_SEARCHES] ?: emptySet()) + q }
    }

    suspend fun removeSavedSearch(query: String) =
        context.dataStore.edit { it[Keys.SAVED_SEARCHES] = (it[Keys.SAVED_SEARCHES] ?: emptySet()) - query }
}
