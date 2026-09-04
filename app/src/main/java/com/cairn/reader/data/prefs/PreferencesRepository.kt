package com.cairn.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val listViewMode: ListViewMode = ListViewMode.CARD,
    val readerFontScale: Float = 1.0f,
    val readerTheme: ReaderTheme = ReaderTheme.DEFAULT,
    val readerFont: ReaderFont = ReaderFont.SERIF,
    val readerJustify: Boolean = false,
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
        val FONT_SCALE = floatPreferencesKey("reader_font_scale")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_FONT = stringPreferencesKey("reader_font")
        val READER_JUSTIFY = booleanPreferencesKey("reader_justify")
    }

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { p ->
        AppPreferences(
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            listViewMode = p[Keys.LIST_VIEW]?.let { runCatching { ListViewMode.valueOf(it) }.getOrNull() } ?: ListViewMode.CARD,
            readerFontScale = p[Keys.FONT_SCALE] ?: 1.0f,
            readerTheme = p[Keys.READER_THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.DEFAULT,
            readerFont = p[Keys.READER_FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: ReaderFont.SERIF,
            readerJustify = p[Keys.READER_JUSTIFY] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setListViewMode(mode: ListViewMode) = context.dataStore.edit { it[Keys.LIST_VIEW] = mode.name }
    suspend fun setReaderFontScale(scale: Float) = context.dataStore.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.8f, 1.8f) }
    suspend fun setReaderTheme(theme: ReaderTheme) = context.dataStore.edit { it[Keys.READER_THEME] = theme.name }
    suspend fun setReaderFont(font: ReaderFont) = context.dataStore.edit { it[Keys.READER_FONT] = font.name }
    suspend fun setReaderJustify(justify: Boolean) = context.dataStore.edit { it[Keys.READER_JUSTIFY] = justify }
}
