package com.cairn.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cairn.reader.util.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK, AUTO }
enum class ReaderTheme { DEFAULT, PAPER, SEPIA, GRAY, NIGHT, BLACK }

/** Reading typeface. SERIF/SANS are the bundled Newsreader/Inter; the rest use device
 *  fonts (no APK weight): BOOK = a system serif, SYSTEM = the device default, MONO = monospace. */
enum class ReaderFont(val label: String) {
    SERIF("Newsreader"), SANS("Inter"), BOOK("Book"), SYSTEM("System"), MONO("Mono")
}

enum class ListViewMode { LIST, CARD, MAGAZINE }
enum class LibraryViewMode { LIST, GRID, MASONRY, HEADLINES }

/** Spaced-repetition scheduler. BASIC = the classic SM-2 ease ladder; ADVANCED = FSRS-5, which
 *  models memory as difficulty + stability and targets a chosen recall probability. */
enum class ReviewScheduler { BASIC, ADVANCED }

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

/** The user's two-stage swipe configuration (right-half, right-full, left-half, left-full),
 *  surfaced to every entry-list row so a swipe behaves identically across surfaces. */
data class SwipeConfig(
    val rightHalf: SwipeAction = SwipeAction.STAR,
    val rightFull: SwipeAction = SwipeAction.SAVE,
    val leftHalf: SwipeAction = SwipeAction.MARK_READ,
    val leftFull: SwipeAction = SwipeAction.ARCHIVE,
)

/** Projects the four flat swipe preferences into the [SwipeConfig] every entry-list row consumes.
 *  Was the same four-argument projection copy-pasted across Inbox/Library/ReadLater/Offline VMs. */
val AppPreferences.swipeConfig: SwipeConfig
    get() = SwipeConfig(swipeRightHalf, swipeRightFull, swipeLeftHalf, swipeLeftFull)

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Accent theme (see AppAccent); "DEFAULT" keeps the Cairn teal / Material You. */
    val appAccent: String = "DEFAULT",
    /** A custom accent seed color (ARGB). 0 = none; when non-zero it overrides accent and dynamic color. */
    val appSeedColor: Int = 0,
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
    /** App-wide full screen: hide the Android status/navigation bars across the whole app. */
    val appFullScreen: Boolean = false,
    /** Whether the reader's Highlights box starts expanded (true) or folded (false). */
    val highlightsBoxExpanded: Boolean = true,
    val blockedKeywords: Set<String> = emptySet(),
    val hideDuplicates: Boolean = false,
    val savedSearches: Set<String> = emptySet(),
    /** Remembered library view mode per scope key (e.g. "col:<id>"), Raindrop-style. */
    val libraryViewByScope: Map<String, LibraryViewMode> = emptyMap(),
    // -- Library home fold state (remembered across navigation and restarts) --
    val libraryQuickOpen: Boolean = true,
    val libraryCollectionsOpen: Boolean = true,
    val libraryTagsOpen: Boolean = true,
    /** Collection IDs whose children are collapsed in the Library tree. */
    val libraryCollapsedCollections: Set<String> = emptySet(),
    /** Tag paths whose children are collapsed in the Library tree. */
    val libraryCollapsedTags: Set<String> = emptySet(),
    val seenOnboarding: Boolean = false,
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
    /** Base URL of a self-hosted WebDAV / Nextcloud folder to mirror backups into; null = off.
     *  When set, scheduled and manual backups also upload there, so nothing depends on one device. */
    val webdavUrl: String? = null,
    /** WebDAV username (Basic auth). For Nextcloud, an app password is recommended. */
    val webdavUser: String? = null,
    /** WebDAV password / app-password (Basic auth). Stored locally only, alongside every other setting. */
    val webdavPass: String? = null,
    /** Days a trashed item is kept before auto-purge on sync. 0 = keep until emptied manually. */
    val trashRetentionDays: Int = 30,
    /** Whether text-to-speech (Listen) is offered at all — the Inbox "Listen to all" button and the
     *  reader's read-aloud. Off hides those controls for people who never use them. */
    val ttsEnabled: Boolean = true,
    /** Strip tracking / analytics parameters (utm_*, fbclid, gclid, …) from links Cairn stores,
     *  opens, and shares. On by default — Cairn is privacy-first. */
    val stripTrackingParams: Boolean = true,
    /** Periodically re-check saved links for rot by contacting the publisher. OFF by default: it is
     *  the one automatic feature that reaches third-party servers, so it stays opt-in to keep the
     *  "offline by default" promise honest. */
    val linkCheckEnabled: Boolean = false,
    /** Strip trackers, beacons and campaign params from stored article bodies (privacy sanitize).
     *  On by default — a saved article should never phone home when you open it. */
    val sanitizeArticles: Boolean = true,
    /** Allow the in-reader "Look up" sheet to fetch definitions from a public dictionary API.
     *  OFF by default: it is the only feature that sends any text you selected to a third-party
     *  server (a single word, over HTTPS), so it stays opt-in to keep the "offline by default"
     *  promise honest. When off, Look up still works for on-device actions (copy, search, share). */
    val dictionaryOnline: Boolean = false,
    /** Context automation: after a successful background sync, pull the next batch of likely-reads
     *  fully offline (respecting the Wi-Fi/charging sync constraints already in effect). */
    val autoOfflinePack: Boolean = false,
    /** Post a once-daily "your brief is ready" notification with the top picks. */
    val dailyBriefNotify: Boolean = false,
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
    // -- Spaced-repetition review --
    /** Which scheduler grades highlights. ADVANCED (FSRS) by default — it adapts to each item. */
    val reviewScheduler: ReviewScheduler = ReviewScheduler.ADVANCED,
    /** Desired recall probability the advanced scheduler targets (0.70–0.99). Higher = more frequent
     *  reviews and stronger recall; lower = fewer reviews. Ignored by the basic scheduler. */
    val reviewRetention: Float = 0.90f,
    /** Hard cap on any scheduled interval, in days. Keeps mature cards from drifting years out. */
    val reviewMaxIntervalDays: Int = 3650,
    /** How many due cards a single review session pulls (new + review, oldest-due first). */
    val reviewSessionSize: Int = 40,
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
    /** Force a single-column layout even on tablets / unfolded foldables, for people who prefer
     *  the phone layout on a big screen. Off = use the two-pane layout when wide enough. */
    val forceSingleColumn: Boolean = false,
    /** Reader page-turn helpers: tap the left/right edge, or the volume keys, to page up/down. */
    val tapZonePaging: Boolean = false,
    val volumeKeyPaging: Boolean = false,
    /** Open articles as the original web page (in-app browser) by default instead of the cleaned
     *  reader, for feeds that haven't chosen their own "Open in" mode. Off = cleaned reader. */
    val openArticlesInWeb: Boolean = false,
    // -- Global feed-management defaults (applied to each newly added feed) --
    /** Folder every new feed is filed into (blank = no folder). Per-feed settings can override it. */
    val defaultFeedFolder: String = "",
    /** Fetch the full article text on sync for new feeds (vs. the feed's own summary). */
    val defaultFeedFullText: Boolean = false,
    /** Post a new-article notification for new feeds. */
    val defaultFeedNotify: Boolean = false,
    /** Default acquisition mode for newly added sources — [AcquisitionMode] name (FEED/EXTRACT/BOTH). */
    val defaultAcquisitionMode: String = "FEED",
    /** Device-local one-time flag: the full-text FTS re-index (Content Engine P2) has completed. Not
     *  backed up — it's a per-install data migration marker, not a user preference. */
    val ftsFullReindexed: Boolean = false,
    // -- Deep-archive crawler politeness (Content Engine P3) --
    /** Honour robots.txt (Disallow + Crawl-delay) when backfilling a site's archive. Default: on. */
    val crawlRespectRobots: Boolean = true,
    /** Only run archive backfill on un-metered (Wi-Fi) networks. Default: on. */
    val crawlWifiOnly: Boolean = true,
    /** Only run archive backfill while charging. Default: on. */
    val crawlChargingOnly: Boolean = true,
    /** How many archive articles to fetch per background run (keeps each pass bounded and polite). */
    val crawlMaxPerRun: Int = 40,
    /** Floor for the delay between requests to the same host, in ms (raised by robots Crawl-delay). */
    val crawlDelayMs: Long = 1500,
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
        val APP_SEED_COLOR = intPreferencesKey("app_seed_color")
        val TRUE_BLACK = booleanPreferencesKey("true_black")
        val LIST_VIEW = stringPreferencesKey("list_view_mode")
        val LIBRARY_VIEW = stringPreferencesKey("library_view_mode")
        val FONT_SCALE = floatPreferencesKey("reader_font_scale")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_FONT = stringPreferencesKey("reader_font")
        val READER_JUSTIFY = booleanPreferencesKey("reader_justify")
        val READER_IMAGES = booleanPreferencesKey("reader_show_images")
        val READER_IMMERSIVE = booleanPreferencesKey("reader_immersive")
        // Kept the legacy datastore key name so users who had full-screen on keep it after the
        // rename from reader-only to app-wide full screen.
        val APP_FULLSCREEN = booleanPreferencesKey("reader_fullscreen")
        val HIGHLIGHTS_BOX_EXPANDED = booleanPreferencesKey("highlights_box_expanded")
        val BLOCKED = stringSetPreferencesKey("blocked_keywords")
        val HIDE_DUP = booleanPreferencesKey("hide_duplicates")
        val SAVED_SEARCHES = stringSetPreferencesKey("saved_searches")
        val LIBRARY_VIEW_BY_SCOPE = stringSetPreferencesKey("library_view_by_scope")
        val LIB_QUICK_OPEN = booleanPreferencesKey("library_quick_open")
        val LIB_COLLECTIONS_OPEN = booleanPreferencesKey("library_collections_open")
        val LIB_TAGS_OPEN = booleanPreferencesKey("library_tags_open")
        val LIB_COLLAPSED_COLLECTIONS = stringSetPreferencesKey("library_collapsed_collections")
        val LIB_COLLAPSED_TAGS = stringSetPreferencesKey("library_collapsed_tags")
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
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_PASS = stringPreferencesKey("webdav_pass")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val BOTTOM_TABS_ORDER = stringPreferencesKey("bottom_tabs_order")
        val STRIP_TRACKING = booleanPreferencesKey("strip_tracking_params")
        val LINK_CHECK_ENABLED = booleanPreferencesKey("link_check_enabled")
        val SANITIZE_ARTICLES = booleanPreferencesKey("sanitize_articles")
        val DICTIONARY_ONLINE = booleanPreferencesKey("dictionary_online")
        val AUTO_OFFLINE_PACK = booleanPreferencesKey("auto_offline_pack")
        val DAILY_BRIEF_NOTIFY = booleanPreferencesKey("daily_brief_notify")
        val MARK_READ_ON_SCROLL = booleanPreferencesKey("mark_read_on_scroll")
        val READER_LINE_HEIGHT = androidx.datastore.preferences.core.floatPreferencesKey("reader_line_height")
        val READER_LETTER_SPACING = androidx.datastore.preferences.core.floatPreferencesKey("reader_letter_spacing")
        val READER_PARA_SPACING = intPreferencesKey("reader_paragraph_spacing")
        val READER_MEASURE = intPreferencesKey("reader_measure")
        val BIONIC_READING = booleanPreferencesKey("bionic_reading")
        val REVIEW_SCHEDULER = stringPreferencesKey("review_scheduler")
        val REVIEW_RETENTION = floatPreferencesKey("review_retention")
        val REVIEW_MAX_INTERVAL = intPreferencesKey("review_max_interval_days")
        val REVIEW_SESSION_SIZE = intPreferencesKey("review_session_size")
        val START_DESTINATION = stringPreferencesKey("start_destination")
        val START_FILTER = stringPreferencesKey("start_filter")
        val SYNC_CHARGING_ONLY = booleanPreferencesKey("sync_charging_only")
        val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        val SHOW_THUMBNAIL = booleanPreferencesKey("show_thumbnail")
        val SHOW_EXCERPT = booleanPreferencesKey("show_excerpt")
        val SHOW_READING_TIME = booleanPreferencesKey("show_reading_time")
        val STICKY_DATE_HEADERS = booleanPreferencesKey("sticky_date_headers")
        val FORCE_SINGLE_COLUMN = booleanPreferencesKey("force_single_column")
        val TAP_ZONE_PAGING = booleanPreferencesKey("tap_zone_paging")
        val VOLUME_KEY_PAGING = booleanPreferencesKey("volume_key_paging")
        val OPEN_ARTICLES_IN_WEB = booleanPreferencesKey("open_articles_in_web")
        val DEFAULT_FEED_FOLDER = stringPreferencesKey("default_feed_folder")
        val DEFAULT_FEED_FULLTEXT = booleanPreferencesKey("default_feed_fulltext")
        val DEFAULT_FEED_NOTIFY = booleanPreferencesKey("default_feed_notify")
        val DEFAULT_ACQUISITION = stringPreferencesKey("default_acquisition_mode")
        val FTS_FULL_REINDEXED = booleanPreferencesKey("fts_full_reindexed")
        val CRAWL_RESPECT_ROBOTS = booleanPreferencesKey("crawl_respect_robots")
        val CRAWL_WIFI_ONLY = booleanPreferencesKey("crawl_wifi_only")
        val CRAWL_CHARGING_ONLY = booleanPreferencesKey("crawl_charging_only")
        val CRAWL_MAX_PER_RUN = intPreferencesKey("crawl_max_per_run")
        val CRAWL_DELAY_MS = longPreferencesKey("crawl_delay_ms")
    }

    /** Per-scope view entries are stored as "scopeKey<sep>MODE" in a string set. */
    private val scopeSep = ""

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { p ->
        AppPreferences(
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            appAccent = p[Keys.APP_ACCENT] ?: "DEFAULT",
            appSeedColor = p[Keys.APP_SEED_COLOR] ?: 0,
            trueBlack = p[Keys.TRUE_BLACK] ?: false,
            listViewMode = p[Keys.LIST_VIEW]?.let { runCatching { ListViewMode.valueOf(it) }.getOrNull() } ?: ListViewMode.CARD,
            libraryViewMode = p[Keys.LIBRARY_VIEW]?.let { runCatching { LibraryViewMode.valueOf(it) }.getOrNull() } ?: LibraryViewMode.GRID,
            readerFontScale = p[Keys.FONT_SCALE] ?: 1.0f,
            readerTheme = p[Keys.READER_THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.DEFAULT,
            readerFont = p[Keys.READER_FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: ReaderFont.SERIF,
            readerJustify = p[Keys.READER_JUSTIFY] ?: false,
            readerShowImages = p[Keys.READER_IMAGES] ?: true,
            readerImmersive = p[Keys.READER_IMMERSIVE] ?: true,
            appFullScreen = p[Keys.APP_FULLSCREEN] ?: false,
            highlightsBoxExpanded = p[Keys.HIGHLIGHTS_BOX_EXPANDED] ?: true,
            blockedKeywords = p[Keys.BLOCKED] ?: emptySet(),
            hideDuplicates = p[Keys.HIDE_DUP] ?: false,
            savedSearches = p[Keys.SAVED_SEARCHES] ?: emptySet(),
            libraryViewByScope = (p[Keys.LIBRARY_VIEW_BY_SCOPE] ?: emptySet()).mapNotNull { entry ->
                val parts = entry.split(scopeSep)
                if (parts.size != 2) return@mapNotNull null
                val mode = runCatching { LibraryViewMode.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
                parts[0] to mode
            }.toMap(),
            libraryQuickOpen = p[Keys.LIB_QUICK_OPEN] ?: true,
            libraryCollectionsOpen = p[Keys.LIB_COLLECTIONS_OPEN] ?: true,
            libraryTagsOpen = p[Keys.LIB_TAGS_OPEN] ?: true,
            libraryCollapsedCollections = p[Keys.LIB_COLLAPSED_COLLECTIONS] ?: emptySet(),
            libraryCollapsedTags = p[Keys.LIB_COLLAPSED_TAGS] ?: emptySet(),
            seenOnboarding = p[Keys.SEEN_ONBOARDING] ?: false,
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
            webdavUrl = p[Keys.WEBDAV_URL],
            webdavUser = p[Keys.WEBDAV_USER],
            webdavPass = p[Keys.WEBDAV_PASS]?.let { SecretStore.decrypt(it) },
            trashRetentionDays = p[Keys.TRASH_RETENTION_DAYS] ?: 30,
            ttsEnabled = p[Keys.TTS_ENABLED] ?: true,
            stripTrackingParams = p[Keys.STRIP_TRACKING] ?: true,
            linkCheckEnabled = p[Keys.LINK_CHECK_ENABLED] ?: false,
            sanitizeArticles = p[Keys.SANITIZE_ARTICLES] ?: true,
            dictionaryOnline = p[Keys.DICTIONARY_ONLINE] ?: false,
            autoOfflinePack = p[Keys.AUTO_OFFLINE_PACK] ?: false,
            dailyBriefNotify = p[Keys.DAILY_BRIEF_NOTIFY] ?: false,
            markReadOnScroll = p[Keys.MARK_READ_ON_SCROLL] ?: false,
            readerLineHeight = p[Keys.READER_LINE_HEIGHT] ?: 1.0f,
            readerLetterSpacing = p[Keys.READER_LETTER_SPACING] ?: 0f,
            readerParagraphSpacing = p[Keys.READER_PARA_SPACING] ?: 8,
            readerMeasure = p[Keys.READER_MEASURE] ?: 0,
            bionicReading = p[Keys.BIONIC_READING] ?: false,
            reviewScheduler = p[Keys.REVIEW_SCHEDULER]?.let { runCatching { ReviewScheduler.valueOf(it) }.getOrNull() } ?: ReviewScheduler.ADVANCED,
            reviewRetention = p[Keys.REVIEW_RETENTION] ?: 0.90f,
            reviewMaxIntervalDays = p[Keys.REVIEW_MAX_INTERVAL] ?: 3650,
            reviewSessionSize = p[Keys.REVIEW_SESSION_SIZE] ?: 40,
            startDestination = p[Keys.START_DESTINATION] ?: "",
            startFilter = p[Keys.START_FILTER] ?: "",
            syncChargingOnly = p[Keys.SYNC_CHARGING_ONLY] ?: false,
            syncIntervalMinutes = p[Keys.SYNC_INTERVAL_MINUTES] ?: 0,
            showThumbnail = p[Keys.SHOW_THUMBNAIL] ?: true,
            showExcerpt = p[Keys.SHOW_EXCERPT] ?: true,
            showReadingTime = p[Keys.SHOW_READING_TIME] ?: true,
            stickyDateHeaders = p[Keys.STICKY_DATE_HEADERS] ?: false,
            forceSingleColumn = p[Keys.FORCE_SINGLE_COLUMN] ?: false,
            tapZonePaging = p[Keys.TAP_ZONE_PAGING] ?: false,
            volumeKeyPaging = p[Keys.VOLUME_KEY_PAGING] ?: false,
            openArticlesInWeb = p[Keys.OPEN_ARTICLES_IN_WEB] ?: false,
            defaultFeedFolder = p[Keys.DEFAULT_FEED_FOLDER] ?: "",
            defaultFeedFullText = p[Keys.DEFAULT_FEED_FULLTEXT] ?: false,
            defaultFeedNotify = p[Keys.DEFAULT_FEED_NOTIFY] ?: false,
            defaultAcquisitionMode = p[Keys.DEFAULT_ACQUISITION] ?: "FEED",
            ftsFullReindexed = p[Keys.FTS_FULL_REINDEXED] ?: false,
            crawlRespectRobots = p[Keys.CRAWL_RESPECT_ROBOTS] ?: true,
            crawlWifiOnly = p[Keys.CRAWL_WIFI_ONLY] ?: true,
            crawlChargingOnly = p[Keys.CRAWL_CHARGING_ONLY] ?: true,
            crawlMaxPerRun = p[Keys.CRAWL_MAX_PER_RUN] ?: 40,
            crawlDelayMs = p[Keys.CRAWL_DELAY_MS] ?: 1500L,
        )
    }

    suspend fun setSeenOnboarding(seen: Boolean) = context.dataStore.edit { it[Keys.SEEN_ONBOARDING] = seen }
    suspend fun setSwipeRightHalf(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_RIGHT_HALF] = action.name }
    suspend fun setSwipeRightFull(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_RIGHT_FULL] = action.name }
    suspend fun setSwipeLeftHalf(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_LEFT_HALF] = action.name }
    suspend fun setSwipeLeftFull(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_LEFT_FULL] = action.name }
    suspend fun setCompactDensity(enabled: Boolean) = context.dataStore.edit { it[Keys.COMPACT_DENSITY] = enabled }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setAppAccent(name: String) = context.dataStore.edit { it[Keys.APP_ACCENT] = name }
    /** Set a custom seed color (ARGB), or 0 to clear it and fall back to the accent / dynamic color. */
    suspend fun setAppSeedColor(argb: Int) = context.dataStore.edit { it[Keys.APP_SEED_COLOR] = argb }
    suspend fun setTrueBlack(enabled: Boolean) = context.dataStore.edit { it[Keys.TRUE_BLACK] = enabled }
    suspend fun setListViewMode(mode: ListViewMode) = context.dataStore.edit { it[Keys.LIST_VIEW] = mode.name }
    suspend fun setLibraryViewMode(mode: LibraryViewMode) = context.dataStore.edit { it[Keys.LIBRARY_VIEW] = mode.name }
    suspend fun setReaderFontScale(scale: Float) = context.dataStore.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.8f, 1.8f) }
    suspend fun setReaderTheme(theme: ReaderTheme) = context.dataStore.edit { it[Keys.READER_THEME] = theme.name }
    suspend fun setReaderFont(font: ReaderFont) = context.dataStore.edit { it[Keys.READER_FONT] = font.name }
    suspend fun setReaderJustify(justify: Boolean) = context.dataStore.edit { it[Keys.READER_JUSTIFY] = justify }
    suspend fun setReaderShowImages(show: Boolean) = context.dataStore.edit { it[Keys.READER_IMAGES] = show }
    suspend fun setReaderImmersive(on: Boolean) = context.dataStore.edit { it[Keys.READER_IMMERSIVE] = on }
    suspend fun setAppFullScreen(on: Boolean) = context.dataStore.edit { it[Keys.APP_FULLSCREEN] = on }
    suspend fun setHighlightsBoxExpanded(on: Boolean) = context.dataStore.edit { it[Keys.HIGHLIGHTS_BOX_EXPANDED] = on }

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

    /** Configure (or clear) the WebDAV / Nextcloud backup target. Passing a blank URL turns it off. */
    suspend fun setWebDav(url: String?, user: String?, pass: String?) = context.dataStore.edit {
        val u = url?.trim().orEmpty()
        if (u.isBlank()) {
            it.remove(Keys.WEBDAV_URL); it.remove(Keys.WEBDAV_USER); it.remove(Keys.WEBDAV_PASS)
        } else {
            it[Keys.WEBDAV_URL] = u
            if (user.isNullOrBlank()) it.remove(Keys.WEBDAV_USER) else it[Keys.WEBDAV_USER] = user.trim()
            if (pass.isNullOrEmpty()) it.remove(Keys.WEBDAV_PASS) else it[Keys.WEBDAV_PASS] = SecretStore.encrypt(pass)
        }
    }

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

    // -- Library home fold state persistence -----------------------------------
    suspend fun setLibraryQuickOpen(open: Boolean) = context.dataStore.edit { it[Keys.LIB_QUICK_OPEN] = open }
    suspend fun setLibraryCollectionsOpen(open: Boolean) = context.dataStore.edit { it[Keys.LIB_COLLECTIONS_OPEN] = open }
    suspend fun setLibraryTagsOpen(open: Boolean) = context.dataStore.edit { it[Keys.LIB_TAGS_OPEN] = open }

    /** Toggle whether a collection's children are collapsed, persisting the whole set. */
    suspend fun setCollectionCollapsed(id: String, collapsed: Boolean) = context.dataStore.edit {
        val set = it[Keys.LIB_COLLAPSED_COLLECTIONS] ?: emptySet()
        it[Keys.LIB_COLLAPSED_COLLECTIONS] = if (collapsed) set + id else set - id
    }

    /** Toggle whether a tag node's children are collapsed, persisting the whole set. */
    suspend fun setTagCollapsed(path: String, collapsed: Boolean) = context.dataStore.edit {
        val set = it[Keys.LIB_COLLAPSED_TAGS] ?: emptySet()
        it[Keys.LIB_COLLAPSED_TAGS] = if (collapsed) set + path else set - path
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

    suspend fun setLinkCheckEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.LINK_CHECK_ENABLED] = enabled }

    suspend fun setSanitizeArticles(enabled: Boolean) =
        context.dataStore.edit { it[Keys.SANITIZE_ARTICLES] = enabled }

    suspend fun setDictionaryOnline(enabled: Boolean) =
        context.dataStore.edit { it[Keys.DICTIONARY_ONLINE] = enabled }

    suspend fun setAutoOfflinePack(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_OFFLINE_PACK] = enabled }

    suspend fun setDailyBriefNotify(enabled: Boolean) =
        context.dataStore.edit { it[Keys.DAILY_BRIEF_NOTIFY] = enabled }

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

    suspend fun setReviewScheduler(scheduler: ReviewScheduler) =
        context.dataStore.edit { it[Keys.REVIEW_SCHEDULER] = scheduler.name }

    suspend fun setReviewRetention(retention: Float) =
        context.dataStore.edit { it[Keys.REVIEW_RETENTION] = retention.coerceIn(0.70f, 0.99f) }

    suspend fun setReviewMaxIntervalDays(days: Int) =
        context.dataStore.edit { it[Keys.REVIEW_MAX_INTERVAL] = days.coerceIn(30, 36500) }

    suspend fun setReviewSessionSize(n: Int) =
        context.dataStore.edit { it[Keys.REVIEW_SESSION_SIZE] = n.coerceIn(5, 200) }

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
    suspend fun setForceSingleColumn(on: Boolean) = context.dataStore.edit { it[Keys.FORCE_SINGLE_COLUMN] = on }
    suspend fun setTapZonePaging(on: Boolean) = context.dataStore.edit { it[Keys.TAP_ZONE_PAGING] = on }
    suspend fun setVolumeKeyPaging(on: Boolean) = context.dataStore.edit { it[Keys.VOLUME_KEY_PAGING] = on }
    suspend fun setOpenArticlesInWeb(on: Boolean) = context.dataStore.edit { it[Keys.OPEN_ARTICLES_IN_WEB] = on }

    suspend fun setDefaultFeedFolder(folder: String) = context.dataStore.edit { it[Keys.DEFAULT_FEED_FOLDER] = folder }
    suspend fun setDefaultFeedFullText(on: Boolean) = context.dataStore.edit { it[Keys.DEFAULT_FEED_FULLTEXT] = on }
    suspend fun setDefaultFeedNotify(on: Boolean) = context.dataStore.edit { it[Keys.DEFAULT_FEED_NOTIFY] = on }
    suspend fun setDefaultAcquisitionMode(mode: String) = context.dataStore.edit { it[Keys.DEFAULT_ACQUISITION] = mode }
    suspend fun setFtsFullReindexed(done: Boolean) = context.dataStore.edit { it[Keys.FTS_FULL_REINDEXED] = done }
    suspend fun setCrawlRespectRobots(on: Boolean) = context.dataStore.edit { it[Keys.CRAWL_RESPECT_ROBOTS] = on }
    suspend fun setCrawlWifiOnly(on: Boolean) = context.dataStore.edit { it[Keys.CRAWL_WIFI_ONLY] = on }
    suspend fun setCrawlChargingOnly(on: Boolean) = context.dataStore.edit { it[Keys.CRAWL_CHARGING_ONLY] = on }

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
            put("appSeedColor", p.appSeedColor)
            put("trueBlack", p.trueBlack)
            put("listViewMode", p.listViewMode.name)
            put("libraryViewMode", p.libraryViewMode.name)
            put("readerFontScale", p.readerFontScale.toDouble())
            put("readerTheme", p.readerTheme.name)
            put("readerFont", p.readerFont.name)
            put("readerJustify", p.readerJustify)
            put("readerShowImages", p.readerShowImages)
            put("readerImmersive", p.readerImmersive)
            put("appFullScreen", p.appFullScreen)
            put("highlightsBoxExpanded", p.highlightsBoxExpanded)
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
            p.webdavUrl?.let { put("webdavUrl", it) }
            p.webdavUser?.let { put("webdavUser", it) }
            // Deliberately NOT exported: the WebDAV password would otherwise travel inside a backup
            // (and be uploaded to the very server it authenticates to). It is re-entered on restore.
            put("trashRetentionDays", p.trashRetentionDays)
            put("ttsEnabled", p.ttsEnabled)
            put("stripTrackingParams", p.stripTrackingParams)
            put("linkCheckEnabled", p.linkCheckEnabled)
            put("sanitizeArticles", p.sanitizeArticles)
            put("dictionaryOnline", p.dictionaryOnline)
            put("autoOfflinePack", p.autoOfflinePack)
            put("dailyBriefNotify", p.dailyBriefNotify)
            put("bottomTabsOrder", JSONArray(p.bottomTabsOrder))
            put("markReadOnScroll", p.markReadOnScroll)
            put("readerLineHeight", p.readerLineHeight.toDouble())
            put("readerLetterSpacing", p.readerLetterSpacing.toDouble())
            put("readerParagraphSpacing", p.readerParagraphSpacing)
            put("readerMeasure", p.readerMeasure)
            put("bionicReading", p.bionicReading)
            put("reviewScheduler", p.reviewScheduler.name)
            put("reviewRetention", p.reviewRetention.toDouble())
            put("reviewMaxIntervalDays", p.reviewMaxIntervalDays)
            put("reviewSessionSize", p.reviewSessionSize)
            put("startDestination", p.startDestination)
            put("startFilter", p.startFilter)
            put("syncChargingOnly", p.syncChargingOnly)
            put("syncIntervalMinutes", p.syncIntervalMinutes)
            put("showThumbnail", p.showThumbnail)
            put("showExcerpt", p.showExcerpt)
            put("showReadingTime", p.showReadingTime)
            put("stickyDateHeaders", p.stickyDateHeaders)
            put("forceSingleColumn", p.forceSingleColumn)
            put("tapZonePaging", p.tapZonePaging)
            put("volumeKeyPaging", p.volumeKeyPaging)
            put("openArticlesInWeb", p.openArticlesInWeb)
            put("defaultFeedFolder", p.defaultFeedFolder)
            put("defaultFeedFullText", p.defaultFeedFullText)
            put("defaultFeedNotify", p.defaultFeedNotify)
            put("defaultAcquisitionMode", p.defaultAcquisitionMode)
        }
    }

    /** Restore app settings from a backup's settings object. Unknown/missing keys keep the
     *  current value. Enums that fail to parse are skipped rather than crashing the restore. */
    suspend fun importSettings(json: JSONObject) {
        context.dataStore.edit { e ->
            if (json.has("themeMode")) json.optString("themeMode").let { e[Keys.THEME_MODE] = it }
            if (json.has("dynamicColor")) e[Keys.DYNAMIC] = json.getBoolean("dynamicColor")
            if (json.has("appAccent")) e[Keys.APP_ACCENT] = json.getString("appAccent")
            if (json.has("appSeedColor")) e[Keys.APP_SEED_COLOR] = json.getInt("appSeedColor")
            if (json.has("trueBlack")) e[Keys.TRUE_BLACK] = json.getBoolean("trueBlack")
            if (json.has("listViewMode")) e[Keys.LIST_VIEW] = json.getString("listViewMode")
            if (json.has("libraryViewMode")) e[Keys.LIBRARY_VIEW] = json.getString("libraryViewMode")
            if (json.has("readerFontScale")) e[Keys.FONT_SCALE] = json.getDouble("readerFontScale").toFloat().coerceIn(0.8f, 1.8f)
            if (json.has("readerTheme")) e[Keys.READER_THEME] = json.getString("readerTheme")
            if (json.has("readerFont")) e[Keys.READER_FONT] = json.getString("readerFont")
            if (json.has("readerJustify")) e[Keys.READER_JUSTIFY] = json.getBoolean("readerJustify")
            if (json.has("readerShowImages")) e[Keys.READER_IMAGES] = json.getBoolean("readerShowImages")
            if (json.has("readerImmersive")) e[Keys.READER_IMMERSIVE] = json.getBoolean("readerImmersive")
            if (json.has("appFullScreen")) e[Keys.APP_FULLSCREEN] = json.getBoolean("appFullScreen")
            else if (json.has("readerFullScreen")) e[Keys.APP_FULLSCREEN] = json.getBoolean("readerFullScreen")
            if (json.has("highlightsBoxExpanded")) e[Keys.HIGHLIGHTS_BOX_EXPANDED] = json.getBoolean("highlightsBoxExpanded")
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
            if (json.has("webdavUrl")) json.getString("webdavUrl").let { if (it.isNotBlank()) e[Keys.WEBDAV_URL] = it }
            if (json.has("webdavUser")) json.getString("webdavUser").let { if (it.isNotBlank()) e[Keys.WEBDAV_USER] = it }
            // Legacy backups may still carry a plaintext password; re-encrypt it at rest on restore.
            if (json.has("webdavPass")) json.getString("webdavPass").let { if (it.isNotEmpty()) e[Keys.WEBDAV_PASS] = SecretStore.encrypt(it) }
            if (json.has("trashRetentionDays")) e[Keys.TRASH_RETENTION_DAYS] = json.getInt("trashRetentionDays").coerceAtLeast(0)
            if (json.has("ttsEnabled")) e[Keys.TTS_ENABLED] = json.getBoolean("ttsEnabled")
            if (json.has("stripTrackingParams")) e[Keys.STRIP_TRACKING] = json.getBoolean("stripTrackingParams")
            if (json.has("linkCheckEnabled")) e[Keys.LINK_CHECK_ENABLED] = json.getBoolean("linkCheckEnabled")
            if (json.has("sanitizeArticles")) e[Keys.SANITIZE_ARTICLES] = json.getBoolean("sanitizeArticles")
            if (json.has("dictionaryOnline")) e[Keys.DICTIONARY_ONLINE] = json.getBoolean("dictionaryOnline")
            if (json.has("autoOfflinePack")) e[Keys.AUTO_OFFLINE_PACK] = json.getBoolean("autoOfflinePack")
            if (json.has("dailyBriefNotify")) e[Keys.DAILY_BRIEF_NOTIFY] = json.getBoolean("dailyBriefNotify")
            json.optJSONArray("bottomTabsOrder")?.let { arr -> e[Keys.BOTTOM_TABS_ORDER] = (0 until arr.length()).joinToString(",") { arr.getString(it) } }
            if (json.has("markReadOnScroll")) e[Keys.MARK_READ_ON_SCROLL] = json.getBoolean("markReadOnScroll")
            if (json.has("readerLineHeight")) e[Keys.READER_LINE_HEIGHT] = json.getDouble("readerLineHeight").toFloat().coerceIn(0.9f, 2.2f)
            if (json.has("readerLetterSpacing")) e[Keys.READER_LETTER_SPACING] = json.getDouble("readerLetterSpacing").toFloat().coerceIn(-0.05f, 0.3f)
            if (json.has("readerParagraphSpacing")) e[Keys.READER_PARA_SPACING] = json.getInt("readerParagraphSpacing").coerceIn(0, 40)
            if (json.has("readerMeasure")) e[Keys.READER_MEASURE] = json.getInt("readerMeasure").coerceIn(0, 900)
            if (json.has("bionicReading")) e[Keys.BIONIC_READING] = json.getBoolean("bionicReading")
            if (json.has("reviewScheduler")) e[Keys.REVIEW_SCHEDULER] = json.getString("reviewScheduler")
            if (json.has("reviewRetention")) e[Keys.REVIEW_RETENTION] = json.getDouble("reviewRetention").toFloat().coerceIn(0.70f, 0.99f)
            if (json.has("reviewMaxIntervalDays")) e[Keys.REVIEW_MAX_INTERVAL] = json.getInt("reviewMaxIntervalDays").coerceIn(30, 36500)
            if (json.has("reviewSessionSize")) e[Keys.REVIEW_SESSION_SIZE] = json.getInt("reviewSessionSize").coerceIn(5, 200)
            if (json.has("startDestination")) e[Keys.START_DESTINATION] = json.getString("startDestination")
            if (json.has("startFilter")) e[Keys.START_FILTER] = json.getString("startFilter")
            if (json.has("syncChargingOnly")) e[Keys.SYNC_CHARGING_ONLY] = json.getBoolean("syncChargingOnly")
            if (json.has("syncIntervalMinutes")) e[Keys.SYNC_INTERVAL_MINUTES] = json.getInt("syncIntervalMinutes").coerceIn(0, 1440)
            if (json.has("showThumbnail")) e[Keys.SHOW_THUMBNAIL] = json.getBoolean("showThumbnail")
            if (json.has("showExcerpt")) e[Keys.SHOW_EXCERPT] = json.getBoolean("showExcerpt")
            if (json.has("showReadingTime")) e[Keys.SHOW_READING_TIME] = json.getBoolean("showReadingTime")
            if (json.has("stickyDateHeaders")) e[Keys.STICKY_DATE_HEADERS] = json.getBoolean("stickyDateHeaders")
            if (json.has("forceSingleColumn")) e[Keys.FORCE_SINGLE_COLUMN] = json.getBoolean("forceSingleColumn")
            if (json.has("tapZonePaging")) e[Keys.TAP_ZONE_PAGING] = json.getBoolean("tapZonePaging")
            if (json.has("volumeKeyPaging")) e[Keys.VOLUME_KEY_PAGING] = json.getBoolean("volumeKeyPaging")
            if (json.has("openArticlesInWeb")) e[Keys.OPEN_ARTICLES_IN_WEB] = json.getBoolean("openArticlesInWeb")
            if (json.has("defaultFeedFolder")) e[Keys.DEFAULT_FEED_FOLDER] = json.getString("defaultFeedFolder")
            if (json.has("defaultFeedFullText")) e[Keys.DEFAULT_FEED_FULLTEXT] = json.getBoolean("defaultFeedFullText")
            if (json.has("defaultFeedNotify")) e[Keys.DEFAULT_FEED_NOTIFY] = json.getBoolean("defaultFeedNotify")
            if (json.has("defaultAcquisitionMode")) e[Keys.DEFAULT_ACQUISITION] = json.getString("defaultAcquisitionMode")
        }
    }
}
