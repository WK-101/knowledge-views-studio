package app.parley.data.people

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.parley.common.people.FavoriteSort
import app.parley.common.people.SecondLineMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject

/** Contacts display and privacy preferences owned by the contacts features (kept apart from the app settings). */
data class PeopleSettings(
    val secondLine: SecondLineMode = SecondLineMode.NONE,
    val preferNickname: Boolean = false,
    val favoriteSort: FavoriteSort = FavoriteSort.CUSTOM,
    /** Custom favourites order, as lookup keys. */
    val favoriteOrder: List<String> = emptyList(),
    /** Favourites grid columns; 0 = automatic (fits the screen width). */
    val favoriteColumns: Int = 0,
    /** Several selected labels: every one (AND) or any (OR). */
    val labelMatchAll: Boolean = false,
    /** New contacts are saved to the private vault. */
    val privateByDefault: Boolean = false,
    /** When another app asks for a whole contact, offer to share only one number. */
    val pickerOneField: Boolean = false,
    /** Ringtone URI per label title. */
    val labelRingtones: Map<String, String> = emptyMap(),
    /** U4: swipe actions on contact and Recents rows (off by default). */
    val swipe: app.parley.common.people.SwipeConfig = app.parley.common.people.SwipeConfig(),
    /** U6: how avatars without a photo look. */
    val avatarStyle: app.parley.common.people.AvatarStyle = app.parley.common.people.AvatarStyle.COLOURFUL,
)

private val Context.peopleStore: DataStore<Preferences> by preferencesDataStore(name = "people")

class PeoplePrefs(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.peopleStore
    private val loadedFlag = MutableStateFlow(false)

    val settings: StateFlow<PeopleSettings> = store.data.map { it.read().also { loadedFlag.value = true } }
        .stateIn(scope, SharingStarted.Eagerly, PeopleSettings())

    /** False until the stored preferences have been read once. */
    val loaded: Boolean get() = loadedFlag.value

    suspend fun current(): PeopleSettings = if (loadedFlag.value) settings.value else store.data.first().read()

    suspend fun update(f: (PeopleSettings) -> PeopleSettings) {
        store.edit { p -> p.write(f(p.read())) }
    }

    /** Everything, as plain strings, for the encrypted backup. */
    suspend fun exportMap(): Map<String, String> = store.data.first().asMap().mapKeys { it.key.name }.mapValues { it.value.toString() }

    suspend fun importMap(map: Map<String, String>) {
        store.edit { p ->
            map.forEach { (k, v) ->
                when (k) {
                    K.nickname.name, K.matchAll.name, K.privateDefault.name, K.pickerOne.name, K.swipeOn.name -> p[booleanPreferencesKey(k)] = v.toBoolean()
                    K.columns.name -> v.toIntOrNull()?.let { p[intPreferencesKey(k)] = it }
                    K.secondLine.name, K.favSort.name, K.favOrder.name, K.ringtones.name, K.swipeRight.name, K.swipeLeft.name, K.avatar.name ->
                        p[stringPreferencesKey(k)] = v
                }
            }
        }
    }

    private fun Preferences.read(): PeopleSettings {
        val d = PeopleSettings()
        return PeopleSettings(
            secondLine = this[K.secondLine]?.let { v -> SecondLineMode.entries.firstOrNull { it.name == v } } ?: d.secondLine,
            preferNickname = this[K.nickname] ?: d.preferNickname,
            favoriteSort = this[K.favSort]?.let { v -> FavoriteSort.entries.firstOrNull { it.name == v } } ?: d.favoriteSort,
            favoriteOrder = this[K.favOrder]?.split(SEP)?.filter { it.isNotEmpty() } ?: d.favoriteOrder,
            favoriteColumns = this[K.columns] ?: d.favoriteColumns,
            labelMatchAll = this[K.matchAll] ?: d.labelMatchAll,
            privateByDefault = this[K.privateDefault] ?: d.privateByDefault,
            pickerOneField = this[K.pickerOne] ?: d.pickerOneField,
            labelRingtones = this[K.ringtones]?.let { raw ->
                runCatching { JSONObject(raw).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrNull()
            } ?: d.labelRingtones,
            swipe = app.parley.common.people.SwipeConfig(
                enabled = this[K.swipeOn] ?: d.swipe.enabled,
                right = app.parley.common.people.SwipeAction.parse(this[K.swipeRight], d.swipe.right),
                left = app.parley.common.people.SwipeAction.parse(this[K.swipeLeft], d.swipe.left),
            ),
            avatarStyle = this[K.avatar]?.let { v -> app.parley.common.people.AvatarStyle.entries.firstOrNull { it.name == v } } ?: d.avatarStyle,
        )
    }

    private fun MutablePreferences.write(s: PeopleSettings) {
        this[K.secondLine] = s.secondLine.name
        this[K.nickname] = s.preferNickname
        this[K.favSort] = s.favoriteSort.name
        this[K.favOrder] = s.favoriteOrder.joinToString(SEP)
        this[K.columns] = s.favoriteColumns
        this[K.matchAll] = s.labelMatchAll
        this[K.privateDefault] = s.privateByDefault
        this[K.pickerOne] = s.pickerOneField
        this[K.ringtones] = JSONObject(s.labelRingtones).toString()
        this[K.swipeOn] = s.swipe.enabled
        this[K.swipeRight] = s.swipe.right.name
        this[K.swipeLeft] = s.swipe.left.name
        this[K.avatar] = s.avatarStyle.name
    }

    private object K {
        val secondLine = stringPreferencesKey("second_line")
        val nickname = booleanPreferencesKey("prefer_nickname")
        val favSort = stringPreferencesKey("favorite_sort")
        val favOrder = stringPreferencesKey("favorite_order")
        val columns = intPreferencesKey("favorite_columns")
        val matchAll = booleanPreferencesKey("label_match_all")
        val privateDefault = booleanPreferencesKey("private_by_default")
        val pickerOne = booleanPreferencesKey("picker_one_field")
        val ringtones = stringPreferencesKey("label_ringtones")
        val swipeOn = booleanPreferencesKey("swipe_enabled")
        val swipeRight = stringPreferencesKey("swipe_right")
        val swipeLeft = stringPreferencesKey("swipe_left")
        val avatar = stringPreferencesKey("avatar_style")
    }

    private companion object {
        const val SEP = "\u001F"
    }
}
