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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReaderTheme { DEFAULT, PAPER, SEPIA, GRAY, NIGHT, BLACK }

/** Reading typeface. SERIF/SANS are the bundled Newsreader/Inter; the rest use device
 *  fonts (no APK weight): BOOK = a system serif, SYSTEM = the device default, MONO = monospace. */
enum class ReaderFont(val label: String) {
    SERIF("Newsreader"), SANS("Inter"), BOOK("Book"), SYSTEM("System"), MONO("Mono")
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
    DELETE("Delete"),
    SAVE_OFFLINE("Save offline"),
    LIBRARY("Save to Library"),
    OPEN_ORIGINAL("Open original"),
    SHARE("Share"),
}

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Accent theme (see AppAccent); "DEFAULT" keeps the Cairn teal / Material You. */
    val appAccent: String = "DEFAULT",
    /** Pure-black backgrounds in dark mode (AMOLED). */
    val trueBlack: Boolean = false,
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
    /** Keep every article you open readable offline later (caches text now, images per policy). */
    val cacheOnOpen: Boolean = true,
    /** Keep at most this many items per feed (older, un-engaged ones are pruned). 0 = keep everything. */
    val maxItemsPerFeed: Int = 0,
    /** Also drop un-engaged items older than this many days on sync. 0 = no age limit. */
    val maxAgeDays: Int = 0,
    /** When on, retention never deletes unread articles (only read, un-engaged ones age out). */
    val keepUnread: Boolean = false,
    /** Which bottom-nav tabs are enabled, by destination name. Empty falls back to a sane default. */
    val bottomTabs: Set<String> = setOf("Inbox", "Library", "Discover", "Settings"),
    /** The user's chosen order of bottom-nav tabs (names). Membership is [bottomTabs]; this just
     *  orders them. Empty = fall back to the app's canonical order. */
    val bottomTabsOrder: List<String> = emptyList(),
    /** SAF tree URI where automatic backups are written; null = not configured. */
    val backupFolderUri: String? = null,
    /** How often to auto-back-up, in hours. 0 = off. */
    val backupFrequencyHours: Int = 0,
    /** Whether scheduled backups bundle offline article copies (a larger .zip) or stay data-only (.json). */
    val backupIncludeOffline: Boolean = false,
    /** Days a trashed item is kept before auto-purge on sync. 0 = keep until emptied manually. */
    val trashRetentionDays: Int = 30,
    /** Whether text-to-speech (Listen) is offered at all — the Inbox "Listen to all" button and the
     *  reader's read-aloud. Off hides those controls for people who never use them. */
    val ttsEnabled: Boolean = true,
    /** Strip tracking / analytics parameters (utm_*, fbclid, gclid, …) from links Cairn stores,
     *  opens, and shares. On by default — Cairn is privacy-first. */
    val stripTrackingParams: Boolean = true,
    /** Auto-mark items read as they scroll up out of view in the Inbox. Off by default. */
    val markReadOnScroll: Boolean = false,
    // -- Fine reading typography (reader) --
    /** Extra line height multiplier applied on top of the base (1.0 = default). */
    val readerLineHeight: Float = 1.0f,
    /** Letter spacing in em (0 = default). */
    val readerLetterSpacing: Float = 0f,
    /** Extra spacing between paragraphs, in dp. */
    val readerParagraphSpacing: Int = 8,
    /** Content measure: max text width in dp (0 = fill available width). */
    val readerMeasure: Int = 0,
    /** Bionic reading: bold the leading part of each word to guide the eye. */
    val bionicReading: Boolean = false,
    /** Which surface opens on cold start (a Destination name, or "" for the default Inbox). */
    val startDestination: String = "",
    /** The Inbox filter to open on cold start (an InboxFilter name, or "" to keep the default). */
    val startFilter: String = "",
    /** Only run background sync while charging. */
    val syncChargingOnly: Boolean = false,
    /** Background sync interval in minutes (0 = the app default cadence). */
    val syncIntervalMinutes: Int = 0,
    // -- List row density (per-element visibility) --
    val showThumbnail: Boolean = true,
    val showExcerpt: Boolean = true,
    val showReadingTime: Boolean = true,
    /** Sticky Today / Yesterday / date headers in the Inbox list. */
    val stickyDateHeaders: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val APP_ACCENT = stringPreferencesKey("app_accent")
        val TRUE_BLACK = booleanPreferencesKey("true_black")
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
        val CACHE_ON_OPEN = booleanPreferencesKey("cache_on_open")
        val IMAGES_WIFI_ONLY = booleanPreferencesKey("images_wifi_only")
        val MAX_ITEMS_PER_FEED = intPreferencesKey("max_items_per_feed")
        val MAX_AGE_DAYS = intPreferencesKey("max_age_days")
        val KEEP_UNREAD = booleanPreferencesKey("keep_unread")
        val BOTTOM_TABS = stringSetPreferencesKey("bottom_tabs")
        val BACKUP_FOLDER = stringPreferencesKey("backup_folder_uri")
        val BACKUP_FREQ = intPreferencesKey("backup_frequency_hours")
        val BACKUP_INCLUDE_OFFLINE = booleanPreferencesKey("backup_include_offline")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val BOTTOM_TABS_ORDER = stringPreferencesKey("bottom_tabs_order")
        val STRIP_TRACKING = booleanPreferencesKey("strip_tracking_params")
        val MARK_READ_ON_SCROLL = booleanPreferencesKey("mark_read_on_scroll")
        val READER_LINE_HEIGHT = androidx.datastore.preferences.core.floatPreferencesKey("reader_line_height")
        val READER_LETTER_SPACING = androidx.datastore.preferences.core.floatPreferencesKey("reader_letter_spacing")
        val READER_PARA_SPACING = intPreferencesKey("reader_paragraph_spacing")
        val READER_MEASURE = intPreferencesKey("reader_measure")
        val BIONIC_READING = booleanPreferencesKey("bionic_reading")
        val START_DESTINATION = stringPreferencesKey("start_destination")
        val START_FILTER = stringPreferencesKey("start_filter")
        val SYNC_CHARGING_ONLY = booleanPreferencesKey("sync_charging_only")
        val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        val SHOW_THUMBNAIL = booleanPreferencesKey("show_thumbnail")
        val SHOW_EXCERPT = booleanPreferencesKey("show_excerpt")
        val SHOW_READING_TIME = booleanPreferencesKey("show_reading_time")
        val STICKY_DATE_HEADERS = booleanPreferencesKey("sticky_date_headers")
    }

    /** Per-scope view entries are stored as "scopeKey<sep>MODE" in a string set. */
    private val scopeSep = ""

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { p ->
        AppPreferences(
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            appAccent = p[Keys.APP_ACCENT] ?: "DEFAULT",
            trueBlack = p[Keys.TRUE_BLACK] ?: false,
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
            cacheOnOpen = p[Keys.CACHE_ON_OPEN] ?: true,
            imagesWifiOnly = p[Keys.IMAGES_WIFI_ONLY] ?: true,
            maxItemsPerFeed = p[Keys.MAX_ITEMS_PER_FEED] ?: 0,
            maxAgeDays = p[Keys.MAX_AGE_DAYS] ?: 0,
            keepUnread = p[Keys.KEEP_UNREAD] ?: false,
            bottomTabs = (p[Keys.BOTTOM_TABS] ?: setOf("Inbox", "Library", "Discover", "Settings")),
            bottomTabsOrder = p[Keys.BOTTOM_TABS_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            backupFolderUri = p[Keys.BACKUP_FOLDER],
            backupFrequencyHours = p[Keys.BACKUP_FREQ] ?: 0,
            backupIncludeOffline = p[Keys.BACKUP_INCLUDE_OFFLINE] ?: false,
            trashRetentionDays = p[Keys.TRASH_RETENTION_DAYS] ?: 30,
            ttsEnabled = p[Keys.TTS_ENABLED] ?: true,
            stripTrackingParams = p[Keys.STRIP_TRACKING] ?: true,
            markReadOnScroll = p[Keys.MARK_READ_ON_SCROLL] ?: false,
            readerLineHeight = p[Keys.READER_LINE_HEIGHT] ?: 1.0f,
            readerLetterSpacing = p[Keys.READER_LETTER_SPACING] ?: 0f,
            readerParagraphSpacing = p[Keys.READER_PARA_SPACING] ?: 8,
            readerMeasure = p[Keys.READER_MEASURE] ?: 0,
            bionicReading = p[Keys.BIONIC_READING] ?: false,
            startDestination = p[Keys.START_DESTINATION] ?: "",
            startFilter = p[Keys.START_FILTER] ?: "",
            syncChargingOnly = p[Keys.SYNC_CHARGING_ONLY] ?: false,
            syncIntervalMinutes = p[Keys.SYNC_INTERVAL_MINUTES] ?: 0,
            showThumbnail = p[Keys.SHOW_THUMBNAIL] ?: true,
            showExcerpt = p[Keys.SHOW_EXCERPT] ?: true,
            showReadingTime = p[Keys.SHOW_READING_TIME] ?: true,
            stickyDateHeaders = p[Keys.STICKY_DATE_HEADERS] ?: false,
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
    suspend fun setAppAccent(name: String) = context.dataStore.edit { it[Keys.APP_ACCENT] = name }
    suspend fun setTrueBlack(enabled: Boolean) = context.dataStore.edit { it[Keys.TRUE_BLACK] = enabled }
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
    suspend fun setCacheOnOpen(enabled: Boolean) = context.dataStore.edit { it[Keys.CACHE_ON_OPEN] = enabled }
    suspend fun setImagesWifiOnly(enabled: Boolean) = context.dataStore.edit { it[Keys.IMAGES_WIFI_ONLY] = enabled }
    suspend fun setMaxItemsPerFeed(max: Int) = context.dataStore.edit { it[Keys.MAX_ITEMS_PER_FEED] = max.coerceAtLeast(0) }
    suspend fun setMaxAgeDays(days: Int) = context.dataStore.edit { it[Keys.MAX_AGE_DAYS] = days.coerceAtLeast(0) }
    suspend fun setKeepUnread(on: Boolean) = context.dataStore.edit { it[Keys.KEEP_UNREAD] = on }

    suspend fun setBackupFolder(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.BACKUP_FOLDER) else it[Keys.BACKUP_FOLDER] = uri
    }
    suspend fun setBackupFrequency(hours: Int) = context.dataStore.edit { it[Keys.BACKUP_FREQ] = hours.coerceAtLeast(0) }

    private val DEFAULT_TABS = listOf("Inbox", "Library", "Discover", "Settings")

    /** Enable/disable a bottom-nav tab by destination name; never lets the bar drop below one tab.
     *  Keeps the ordered list in sync (append on enable, drop on disable). */
    suspend fun setBottomTab(name: String, enabled: Boolean) = context.dataStore.edit { p ->
        val current = p[Keys.BOTTOM_TABS] ?: DEFAULT_TABS.toSet()
        val next = if (enabled) current + name else current - name
        val members = if (next.isEmpty()) setOf("Inbox") else next
        p[Keys.BOTTOM_TABS] = members
        // Maintain order: start from the stored order (or default), keep members, append new ones.
        val order = (p[Keys.BOTTOM_TABS_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_TABS)
        val reordered = order.filter { it in members } + members.filter { it !in order }
        p[Keys.BOTTOM_TABS_ORDER] = reordered.joinToString(",")
    }

    /** Move a bottom-nav tab one slot earlier ([up]) or later within the ordered bar. */
    suspend fun moveBottomTab(name: String, up: Boolean) = context.dataStore.edit { p ->
        val members = p[Keys.BOTTOM_TABS] ?: DEFAULT_TABS.toSet()
        val order = (p[Keys.BOTTOM_TABS_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_TABS)
            .filter { it in members }.toMutableList()
        val i = order.indexOf(name)
        if (i < 0) return@edit
        val j = if (up) i - 1 else i + 1
        if (j < 0 || j >= order.size) return@edit
        order[i] = order[j].also { order[j] = order[i] }
        p[Keys.BOTTOM_TABS_ORDER] = order.joinToString(",")
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

    suspend fun setBackupIncludeOffline(enabled: Boolean) =
        context.dataStore.edit { it[Keys.BACKUP_INCLUDE_OFFLINE] = enabled }

    suspend fun setTrashRetentionDays(days: Int) =
        context.dataStore.edit { it[Keys.TRASH_RETENTION_DAYS] = days.coerceAtLeast(0) }

    suspend fun setTtsEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.TTS_ENABLED] = enabled }

    suspend fun setStripTrackingParams(enabled: Boolean) =
        context.dataStore.edit { it[Keys.STRIP_TRACKING] = enabled }

    suspend fun setMarkReadOnScroll(enabled: Boolean) =
        context.dataStore.edit { it[Keys.MARK_READ_ON_SCROLL] = enabled }

    suspend fun setReaderLineHeight(v: Float) =
        context.dataStore.edit { it[Keys.READER_LINE_HEIGHT] = v.coerceIn(0.9f, 2.2f) }

    suspend fun setReaderLetterSpacing(v: Float) =
        context.dataStore.edit { it[Keys.READER_LETTER_SPACING] = v.coerceIn(-0.05f, 0.3f) }

    suspend fun setReaderParagraphSpacing(dp: Int) =
        context.dataStore.edit { it[Keys.READER_PARA_SPACING] = dp.coerceIn(0, 40) }

    suspend fun setReaderMeasure(dp: Int) =
        context.dataStore.edit { it[Keys.READER_MEASURE] = dp.coerceIn(0, 900) }

    suspend fun setBionicReading(on: Boolean) =
        context.dataStore.edit { it[Keys.BIONIC_READING] = on }

    suspend fun setStartDestination(name: String) =
        context.dataStore.edit { it[Keys.START_DESTINATION] = name }

    suspend fun setStartFilter(name: String) =
        context.dataStore.edit { it[Keys.START_FILTER] = name }

    suspend fun setSyncChargingOnly(on: Boolean) =
        context.dataStore.edit { it[Keys.SYNC_CHARGING_ONLY] = on }

    suspend fun setSyncIntervalMinutes(min: Int) =
        context.dataStore.edit { it[Keys.SYNC_INTERVAL_MINUTES] = min.coerceIn(0, 1440) }

    suspend fun setShowThumbnail(on: Boolean) = context.dataStore.edit { it[Keys.SHOW_THUMBNAIL] = on }
    suspend fun setShowExcerpt(on: Boolean) = context.dataStore.edit { it[Keys.SHOW_EXCERPT] = on }
    suspend fun setShowReadingTime(on: Boolean) = context.dataStore.edit { it[Keys.SHOW_READING_TIME] = on }
    suspend fun setStickyDateHeaders(on: Boolean) = context.dataStore.edit { it[Keys.STICKY_DATE_HEADERS] = on }

    // -- Settings backup -------------------------------------------------------
    //
    // A full backup includes every app setting so a restore reproduces the app exactly. The
    // device-specific auto-backup folder grant (a SAF URI that can't transfer) is intentionally
    // left out; everything else — appearance, reader, swipe, retention, tabs, keywords,
    // saved searches, per-scope view modes — is captured and restored.

    /** Serialize all app settings to a JSON object for inclusion in a backup. */
    suspend fun exportSettings(): JSONObject {
        val p = preferences.first()
        return JSONObject().apply {
            put("themeMode", p.themeMode.name)
            put("dynamicColor", p.dynamicColor)
            put("appAccent", p.appAccent)
            put("trueBlack", p.trueBlack)
            put("listViewMode", p.listViewMode.name)
            put("libraryViewMode", p.libraryViewMode.name)
            put("readerFontScale", p.readerFontScale.toDouble())
            put("readerTheme", p.readerTheme.name)
            put("readerFont", p.readerFont.name)
            put("readerJustify", p.readerJustify)
            put("readerShowImages", p.readerShowImages)
            put("readerImmersive", p.readerImmersive)
            put("readerFullScreen", p.readerFullScreen)
            put("blockedKeywords", JSONArray(p.blockedKeywords.toList()))
            put("hideDuplicates", p.hideDuplicates)
            put("savedSearches", JSONArray(p.savedSearches.toList()))
            put("libraryViewByScope", JSONObject().apply { p.libraryViewByScope.forEach { (k, v) -> put(k, v.name) } })
            put("swipeRightHalf", p.swipeRightHalf.name)
            put("swipeRightFull", p.swipeRightFull.name)
            put("swipeLeftHalf", p.swipeLeftHalf.name)
            put("swipeLeftFull", p.swipeLeftFull.name)
            put("compactDensity", p.compactDensity)
            put("syncWifiOnly", p.syncWifiOnly)
            put("cacheImagesOffline", p.cacheImagesOffline)
            put("imagesWifiOnly", p.imagesWifiOnly)
            put("cacheOnOpen", p.cacheOnOpen)
            put("maxItemsPerFeed", p.maxItemsPerFeed)
            put("maxAgeDays", p.maxAgeDays)
            put("keepUnread", p.keepUnread)
            put("bottomTabs", JSONArray(p.bottomTabs.toList()))
            put("backupFrequencyHours", p.backupFrequencyHours)
            put("backupIncludeOffline", p.backupIncludeOffline)
            put("trashRetentionDays", p.trashRetentionDays)
            put("ttsEnabled", p.ttsEnabled)
            put("stripTrackingParams", p.stripTrackingParams)
            put("bottomTabsOrder", JSONArray(p.bottomTabsOrder))
            put("markReadOnScroll", p.markReadOnScroll)
            put("readerLineHeight", p.readerLineHeight.toDouble())
            put("readerLetterSpacing", p.readerLetterSpacing.toDouble())
            put("readerParagraphSpacing", p.readerParagraphSpacing)
            put("readerMeasure", p.readerMeasure)
            put("bionicReading", p.bionicReading)
            put("startDestination", p.startDestination)
            put("startFilter", p.startFilter)
            put("syncChargingOnly", p.syncChargingOnly)
            put("syncIntervalMinutes", p.syncIntervalMinutes)
            put("showThumbnail", p.showThumbnail)
            put("showExcerpt", p.showExcerpt)
            put("showReadingTime", p.showReadingTime)
            put("stickyDateHeaders", p.stickyDateHeaders)
        }
    }

    /** Restore app settings from a backup's settings object. Unknown/missing keys keep the
     *  current value. Enums that fail to parse are skipped rather than crashing the restore. */
    suspend fun importSettings(json: JSONObject) {
        context.dataStore.edit { e ->
            if (json.has("themeMode")) json.optString("themeMode").let { e[Keys.THEME_MODE] = it }
            if (json.has("dynamicColor")) e[Keys.DYNAMIC] = json.getBoolean("dynamicColor")
            if (json.has("appAccent")) e[Keys.APP_ACCENT] = json.getString("appAccent")
            if (json.has("trueBlack")) e[Keys.TRUE_BLACK] = json.getBoolean("trueBlack")
            if (json.has("listViewMode")) e[Keys.LIST_VIEW] = json.getString("listViewMode")
            if (json.has("libraryViewMode")) e[Keys.LIBRARY_VIEW] = json.getString("libraryViewMode")
            if (json.has("readerFontScale")) e[Keys.FONT_SCALE] = json.getDouble("readerFontScale").toFloat().coerceIn(0.8f, 1.8f)
            if (json.has("readerTheme")) e[Keys.READER_THEME] = json.getString("readerTheme")
            if (json.has("readerFont")) e[Keys.READER_FONT] = json.getString("readerFont")
            if (json.has("readerJustify")) e[Keys.READER_JUSTIFY] = json.getBoolean("readerJustify")
            if (json.has("readerShowImages")) e[Keys.READER_IMAGES] = json.getBoolean("readerShowImages")
            if (json.has("readerImmersive")) e[Keys.READER_IMMERSIVE] = json.getBoolean("readerImmersive")
            if (json.has("readerFullScreen")) e[Keys.READER_FULLSCREEN] = json.getBoolean("readerFullScreen")
            json.optJSONArray("blockedKeywords")?.let { arr -> e[Keys.BLOCKED] = (0 until arr.length()).map { arr.getString(it) }.toSet() }
            if (json.has("hideDuplicates")) e[Keys.HIDE_DUP] = json.getBoolean("hideDuplicates")
            json.optJSONArray("savedSearches")?.let { arr -> e[Keys.SAVED_SEARCHES] = (0 until arr.length()).map { arr.getString(it) }.toSet() }
            json.optJSONObject("libraryViewByScope")?.let { obj ->
                e[Keys.LIBRARY_VIEW_BY_SCOPE] = obj.keys().asSequence().map { k -> "$k=${obj.getString(k)}" }.toSet()
            }
            if (json.has("swipeRightHalf")) e[Keys.SWIPE_RIGHT_HALF] = json.getString("swipeRightHalf")
            if (json.has("swipeRightFull")) e[Keys.SWIPE_RIGHT_FULL] = json.getString("swipeRightFull")
            if (json.has("swipeLeftHalf")) e[Keys.SWIPE_LEFT_HALF] = json.getString("swipeLeftHalf")
            if (json.has("swipeLeftFull")) e[Keys.SWIPE_LEFT_FULL] = json.getString("swipeLeftFull")
            if (json.has("compactDensity")) e[Keys.COMPACT_DENSITY] = json.getBoolean("compactDensity")
            if (json.has("syncWifiOnly")) e[Keys.SYNC_WIFI_ONLY] = json.getBoolean("syncWifiOnly")
            if (json.has("cacheImagesOffline")) e[Keys.CACHE_IMAGES] = json.getBoolean("cacheImagesOffline")
            if (json.has("imagesWifiOnly")) e[Keys.IMAGES_WIFI_ONLY] = json.getBoolean("imagesWifiOnly")
            if (json.has("cacheOnOpen")) e[Keys.CACHE_ON_OPEN] = json.getBoolean("cacheOnOpen")
            if (json.has("maxItemsPerFeed")) e[Keys.MAX_ITEMS_PER_FEED] = json.getInt("maxItemsPerFeed").coerceAtLeast(0)
            if (json.has("maxAgeDays")) e[Keys.MAX_AGE_DAYS] = json.getInt("maxAgeDays").coerceAtLeast(0)
            if (json.has("keepUnread")) e[Keys.KEEP_UNREAD] = json.getBoolean("keepUnread")
            json.optJSONArray("bottomTabs")?.let { arr -> e[Keys.BOTTOM_TABS] = (0 until arr.length()).map { arr.getString(it) }.toSet() }
            if (json.has("backupFrequencyHours")) e[Keys.BACKUP_FREQ] = json.getInt("backupFrequencyHours").coerceAtLeast(0)
            if (json.has("backupIncludeOffline")) e[Keys.BACKUP_INCLUDE_OFFLINE] = json.getBoolean("backupIncludeOffline")
            if (json.has("trashRetentionDays")) e[Keys.TRASH_RETENTION_DAYS] = json.getInt("trashRetentionDays").coerceAtLeast(0)
            if (json.has("ttsEnabled")) e[Keys.TTS_ENABLED] = json.getBoolean("ttsEnabled")
            if (json.has("stripTrackingParams")) e[Keys.STRIP_TRACKING] = json.getBoolean("stripTrackingParams")
            json.optJSONArray("bottomTabsOrder")?.let { arr -> e[Keys.BOTTOM_TABS_ORDER] = (0 until arr.length()).joinToString(",") { arr.getString(it) } }
            if (json.has("markReadOnScroll")) e[Keys.MARK_READ_ON_SCROLL] = json.getBoolean("markReadOnScroll")
            if (json.has("readerLineHeight")) e[Keys.READER_LINE_HEIGHT] = json.getDouble("readerLineHeight").toFloat().coerceIn(0.9f, 2.2f)
            if (json.has("readerLetterSpacing")) e[Keys.READER_LETTER_SPACING] = json.getDouble("readerLetterSpacing").toFloat().coerceIn(-0.05f, 0.3f)
            if (json.has("readerParagraphSpacing")) e[Keys.READER_PARA_SPACING] = json.getInt("readerParagraphSpacing").coerceIn(0, 40)
            if (json.has("readerMeasure")) e[Keys.READER_MEASURE] = json.getInt("readerMeasure").coerceIn(0, 900)
            if (json.has("bionicReading")) e[Keys.BIONIC_READING] = json.getBoolean("bionicReading")
            if (json.has("startDestination")) e[Keys.START_DESTINATION] = json.getString("startDestination")
            if (json.has("startFilter")) e[Keys.START_FILTER] = json.getString("startFilter")
            if (json.has("syncChargingOnly")) e[Keys.SYNC_CHARGING_ONLY] = json.getBoolean("syncChargingOnly")
            if (json.has("syncIntervalMinutes")) e[Keys.SYNC_INTERVAL_MINUTES] = json.getInt("syncIntervalMinutes").coerceIn(0, 1440)
            if (json.has("showThumbnail")) e[Keys.SHOW_THUMBNAIL] = json.getBoolean("showThumbnail")
            if (json.has("showExcerpt")) e[Keys.SHOW_EXCERPT] = json.getBoolean("showExcerpt")
            if (json.has("showReadingTime")) e[Keys.SHOW_READING_TIME] = json.getBoolean("showReadingTime")
            if (json.has("stickyDateHeaders")) e[Keys.STICKY_DATE_HEADERS] = json.getBoolean("stickyDateHeaders")
        }
    }
}
