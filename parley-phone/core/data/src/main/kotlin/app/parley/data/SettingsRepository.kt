package app.parley.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.parley.common.AnswerGesture
import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.ListDensity
import app.parley.common.NavTabs
import app.parley.common.ScreeningSettings
import app.parley.common.StartTab
import app.parley.common.SurfaceLayout
import app.parley.common.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    private val _loaded = MutableStateFlow(false)
    /** False until the stored settings have been read once (avoids flashing first-run UI). */
    val loaded: StateFlow<Boolean> = _loaded

    val settings: StateFlow<AppSettings> = store.data
        .map { it.toSettings().also { _loaded.value = true } }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    init {
        // Pin the layout schema once, before any new default could apply: an existing user keeps
        // separate tabs exactly as they were (see SurfaceLayout.migrate).
        scope.launch {
            runCatching {
                store.edit { prefs ->
                    val existing = prefs.asMap().keys.any { it.name != K.surfaces.name }
                    SurfaceLayout.migrate(prefs[K.surfaces], existingUser = existing)?.let { prefs[K.surfaces] = it }
                }
            }
        }
    }

    /**
     * Current settings as stored. Read from the store rather than [settings], which can lag a moment behind an
     * [update] that just finished (a call placed right after changing "confirm before calling" must see it).
     * DataStore serves this from memory once loaded, and reads disk when the process was just woken by a call.
     */
    suspend fun current(): AppSettings = store.data.first().toSettings()

    /** All stored preferences as typed strings ("b:true", "i:5", "s:text") for backups. */
    suspend fun exportMap(): Map<String, String> = store.data.first().asMap().mapKeys { it.key.name }.mapValues { (_, v) ->
        when (v) {
            is Boolean -> "b:$v"
            is Int -> "i:$v"
            is Long -> "l:$v"
            else -> "s:$v"
        }
    }

    suspend fun importMap(map: Map<String, String>) {
        store.edit { prefs ->
            map.forEach { (k, v) ->
                val body = v.substringAfter(':')
                when (v.substringBefore(':')) {
                    "b" -> prefs[booleanPreferencesKey(k)] = body.toBoolean()
                    "i" -> body.toIntOrNull()?.let { prefs[intPreferencesKey(k)] = it }
                    "l" -> body.toLongOrNull()?.let { prefs[longPreferencesKey(k)] = it }
                    "s" -> prefs[stringPreferencesKey(k)] = body
                }
            }
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.write(next)
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOr(this[K.theme], d.themeMode),
            amoledBlack = this[K.amoled] ?: d.amoledBlack,
            dynamicColor = this[K.dynamic] ?: d.dynamicColor,
            density = enumOr(this[K.density], d.density),
            answerGesture = enumOr(this[K.answer], d.answerGesture),
            confirmBeforeCall = this[K.confirm] ?: d.confirmBeforeCall,
            dialpadTones = this[K.tones] ?: d.dialpadTones,
            dialpadHaptics = this[K.haptics] ?: d.dialpadHaptics,
            startTab = enumOr(this[K.startTab], d.startTab),
            sortByFirstName = this[K.sortFirst] ?: d.sortByFirstName,
            showSimLabels = this[K.simLabels] ?: d.showSimLabels,
            defaultAccountType = this[K.accType]?.ifEmpty { null },
            defaultAccountName = this[K.accName]?.ifEmpty { null },
            quickReplies = this[K.replies]?.split(SEP)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: d.quickReplies,
            // v1 toggles keep their own keys; everything added later lives in one JSON value.
            screening = ScreeningSettings.decode(this[K.screeningJson]).copy(
                blockHidden = this[K.blockHidden] ?: false,
                blockNonContacts = this[K.blockNonContacts] ?: false,
                blockNeighbourSpoofing = this[K.blockNeighbour] ?: false,
                blockFailedVerification = this[K.blockFailed] ?: false,
                defaultAction = enumOr(this[K.blockAction], BlockAction.REJECT),
                repeatCallers = this[K.repeatCaller] ?: d.repeatCallerRingsThrough,
            ),
            onboardingDone = this[K.onboarding] ?: false,
            appLock = this[K.appLock] ?: d.appLock,
            lockAfterMinutes = this[K.lockAfter] ?: d.lockAfterMinutes,
            secureScreen = this[K.secure] ?: d.secureScreen,
            hideVault = this[K.hideVault] ?: d.hideVault,
            privateVaultHistory = this[K.privateHistory] ?: d.privateVaultHistory,
            unknownRingtone = this[K.unknownRingtone]?.ifEmpty { null },
            repeatCallerRingsThrough = this[K.repeatCaller] ?: d.repeatCallerRingsThrough,
            birthdayReminders = this[K.birthdays] ?: d.birthdayReminders,
            birthdayReminderHour = this[K.birthdayHour] ?: d.birthdayReminderHour,
            reachOutNudges = this[K.nudges] ?: d.reachOutNudges,
            callLogRetentionDays = this[K.retention] ?: d.callLogRetentionDays,
            contactRowActions = this[K.rowActions] ?: d.contactRowActions,
            mirrorRelations = this[K.mirrorRelations] ?: d.mirrorRelations,
            askBeforeDeletingTemporary = this[K.askTempDelete] ?: d.askBeforeDeletingTemporary,
            navTabs = NavTabs.decode(this[K.navTabs]),
            recentsLayout = enumOr(this[K.recentsLayout], d.recentsLayout),
            recentsStyle = enumOr(this[K.recentsStyle], d.recentsStyle),
            callBackground = enumOr(this[K.callBackground], d.callBackground),
            showCallerPhoto = this[K.showCallerPhoto] ?: d.showCallerPhoto,
            answerWithRtt = this[K.answerWithRtt] ?: d.answerWithRtt,
            surfaces = SurfaceLayout.decode(this[K.surfaces]),
            rememberRecentsFilter = this[K.rememberRecentsFilter] ?: d.rememberRecentsFilter,
            recentsFilter = this[K.recentsFilter] ?: d.recentsFilter,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[K.theme] = s.themeMode.name
        this[K.amoled] = s.amoledBlack
        this[K.dynamic] = s.dynamicColor
        this[K.density] = s.density.name
        this[K.answer] = s.answerGesture.name
        this[K.confirm] = s.confirmBeforeCall
        this[K.tones] = s.dialpadTones
        this[K.haptics] = s.dialpadHaptics
        this[K.startTab] = s.startTab.name
        this[K.sortFirst] = s.sortByFirstName
        this[K.simLabels] = s.showSimLabels
        this[K.accType] = s.defaultAccountType.orEmpty()
        this[K.accName] = s.defaultAccountName.orEmpty()
        this[K.replies] = s.quickReplies.joinToString(SEP)
        this[K.blockHidden] = s.screening.blockHidden
        this[K.blockNonContacts] = s.screening.blockNonContacts
        this[K.blockNeighbour] = s.screening.blockNeighbourSpoofing
        this[K.blockFailed] = s.screening.blockFailedVerification
        this[K.blockAction] = s.screening.defaultAction.name
        this[K.screeningJson] = s.screening.encode()
        this[K.onboarding] = s.onboardingDone
        this[K.appLock] = s.appLock
        this[K.lockAfter] = s.lockAfterMinutes
        this[K.secure] = s.secureScreen
        this[K.hideVault] = s.hideVault
        this[K.privateHistory] = s.privateVaultHistory
        this[K.unknownRingtone] = s.unknownRingtone.orEmpty()
        this[K.repeatCaller] = s.repeatCallerRingsThrough
        this[K.birthdays] = s.birthdayReminders
        this[K.birthdayHour] = s.birthdayReminderHour
        this[K.nudges] = s.reachOutNudges
        this[K.retention] = s.callLogRetentionDays
        this[K.rowActions] = s.contactRowActions
        this[K.mirrorRelations] = s.mirrorRelations
        this[K.askTempDelete] = s.askBeforeDeletingTemporary
        this[K.navTabs] = s.navTabs.encode()
        this[K.recentsLayout] = s.recentsLayout.name
        this[K.recentsStyle] = s.recentsStyle.name
        this[K.callBackground] = s.callBackground.name
        this[K.showCallerPhoto] = s.showCallerPhoto
        this[K.answerWithRtt] = s.answerWithRtt
        this[K.surfaces] = s.surfaces.encode()
        this[K.rememberRecentsFilter] = s.rememberRecentsFilter
        this[K.recentsFilter] = s.recentsFilter
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private object K {
        val theme = stringPreferencesKey("theme")
        val amoled = booleanPreferencesKey("amoled")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val density = stringPreferencesKey("density")
        val answer = stringPreferencesKey("answer_gesture")
        val confirm = booleanPreferencesKey("confirm_before_call")
        val tones = booleanPreferencesKey("dialpad_tones")
        val haptics = booleanPreferencesKey("dialpad_haptics")
        val startTab = stringPreferencesKey("start_tab")
        val sortFirst = booleanPreferencesKey("sort_first_name")
        val simLabels = booleanPreferencesKey("sim_labels")
        val accType = stringPreferencesKey("default_account_type")
        val accName = stringPreferencesKey("default_account_name")
        val replies = stringPreferencesKey("quick_replies")
        val blockHidden = booleanPreferencesKey("block_hidden")
        val blockNonContacts = booleanPreferencesKey("block_non_contacts")
        val blockNeighbour = booleanPreferencesKey("block_neighbour")
        val blockFailed = booleanPreferencesKey("block_failed_verification")
        val blockAction = stringPreferencesKey("block_action")
        val screeningJson = stringPreferencesKey("screening_v2")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val appLock = booleanPreferencesKey("app_lock")
        val lockAfter = intPreferencesKey("lock_after_minutes")
        val secure = booleanPreferencesKey("secure_screen")
        val hideVault = booleanPreferencesKey("hide_vault")
        val privateHistory = booleanPreferencesKey("private_vault_history")
        val unknownRingtone = stringPreferencesKey("unknown_ringtone")
        val repeatCaller = booleanPreferencesKey("repeat_caller")
        val birthdays = booleanPreferencesKey("birthday_reminders")
        val birthdayHour = intPreferencesKey("birthday_hour")
        val nudges = booleanPreferencesKey("reach_out_nudges")
        val retention = intPreferencesKey("call_log_retention_days")
        val rowActions = booleanPreferencesKey("contact_row_actions")
        val mirrorRelations = booleanPreferencesKey("mirror_relations")
        val askTempDelete = booleanPreferencesKey("ask_before_deleting_temporary")
        val navTabs = stringPreferencesKey("nav_tabs")
        val recentsLayout = stringPreferencesKey("recents_layout")
        val recentsStyle = stringPreferencesKey("recents_style")
        val callBackground = stringPreferencesKey("call_background")
        val showCallerPhoto = booleanPreferencesKey("show_caller_photo")
        val answerWithRtt = booleanPreferencesKey("answer_with_rtt")
        val surfaces = stringPreferencesKey("surface_layout")
        val rememberRecentsFilter = booleanPreferencesKey("remember_recents_filter")
        val recentsFilter = stringPreferencesKey("recents_filter")
    }

    companion object {
        private const val SEP = "\u001F"

        /**
         * Settings that protect the phone's content (app lock, its delay, hiding the screen, discreet mode). A backup
         * never changes them on its own: a restore keeps them waiting until the user confirms with the app lock.
         */
        val SECURITY_KEYS: Set<String> = setOf(K.appLock.name, K.lockAfter.name, K.secure.name, K.hideVault.name)
    }
}
