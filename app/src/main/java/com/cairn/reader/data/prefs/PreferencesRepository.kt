package com.cairn.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val blockedKeywords: Set<String> = emptySet(),
    val hideDuplicates: Boolean = false,
    val savedSearches: Set<String> = emptySet(),
    /** Remembered library view mode per scope key (e.g. "col:<id>"), Raindrop-style. */
    val libraryViewByScope: Map<String, LibraryViewMode> = emptyMap(),
    val seenOnboarding: Boolean = false,
    val swipeRight: SwipeAction = SwipeAction.SAVE,
    val swipeLeft: SwipeAction = SwipeAction.MARK_READ,
    val compactDensity: Boolean = false,
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
        val BLOCKED = stringSetPreferencesKey("blocked_keywords")
        val HIDE_DUP = booleanPreferencesKey("hide_duplicates")
        val SAVED_SEARCHES = stringSetPreferencesKey("saved_searches")
        val LIBRARY_VIEW_BY_SCOPE = stringSetPreferencesKey("library_view_by_scope")
        val SEEN_ONBOARDING = booleanPreferencesKey("seen_onboarding")
        val SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        val SWIPE_LEFT = stringPreferencesKey("swipe_left")
        val COMPACT_DENSITY = booleanPreferencesKey("compact_density")
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
            compactDensity = p[Keys.COMPACT_DENSITY] ?: false,
        )
    }

    suspend fun setSeenOnboarding(seen: Boolean) = context.dataStore.edit { it[Keys.SEEN_ONBOARDING] = seen }
    suspend fun setSwipeRight(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_RIGHT] = action.name }
    suspend fun setSwipeLeft(action: SwipeAction) = context.dataStore.edit { it[Keys.SWIPE_LEFT] = action.name }
    suspend fun setCompactDensity(enabled: Boolean) = context.dataStore.edit { it[Keys.COMPACT_DENSITY] = enabled }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setListViewMode(mode: ListViewMode) = context.dataStore.edit { it[Keys.LIST_VIEW] = mode.name }
    suspend fun setLibraryViewMode(mode: LibraryViewMode) = context.dataStore.edit { it[Keys.LIBRARY_VIEW] = mode.name }
    suspend fun setReaderFontScale(scale: Float) = context.dataStore.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.8f, 1.8f) }
    suspend fun setReaderTheme(theme: ReaderTheme) = context.dataStore.edit { it[Keys.READER_THEME] = theme.name }
    suspend fun setReaderFont(font: ReaderFont) = context.dataStore.edit { it[Keys.READER_FONT] = font.name }
    suspend fun setReaderJustify(justify: Boolean) = context.dataStore.edit { it[Keys.READER_JUSTIFY] = justify }

    suspend fun setHideDuplicates(enabled: Boolean) = context.dataStore.edit { it[Keys.HIDE_DUP] = enabled }

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
