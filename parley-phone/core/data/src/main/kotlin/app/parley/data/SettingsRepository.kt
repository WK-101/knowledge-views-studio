package app.parley.data

import app.parley.common.catching
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
import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.NavTabs
import app.parley.common.ScreeningSettings
import app.parley.common.SurfaceLayout
import app.parley.common.history.RetentionDefaults
import app.parley.common.people.NameOrder
import app.parley.common.security.DuressPolicy
import app.parley.common.security.SafetyOverlay
import app.parley.data.security.Concealment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private const val PRIVATE_NAMES_TIMEOUT_MS = 1_500L

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    /**
     * This install was never updated: the first run of a new install (an update changes the last-update time). Stored
     * settings can't tell, since start-up writes some before anyone chose anything.
     */
    private val freshInstall: Boolean by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
        }.getOrDefault(false)
    }

    private val _loaded = MutableStateFlow(false)

    /** False until the stored settings have been read once (avoids flashing first-run UI). */
    val loaded: StateFlow<Boolean> = _loaded

    /**
     * I21: the safety switches changed during a duress session, in memory only (see [DuressPolicy.split]); null outside
     * one. Dropped by [endDuressSession] at the next lock.
     */
    private val sessionOverlay = MutableStateFlow<SafetyOverlay?>(null)

    @Volatile private var duressSession = false

    /**
     * The settings Parley runs on: as stored, except while a duress unlock hides things ([Concealment]), when discreet
     * mode is forced on and [AppSettings.duress] says what the Privacy page shows.
     */
    val settings: StateFlow<AppSettings> = combine(store.data.map { it.toSettings() }, Concealment.state, sessionOverlay) { stored, d, overlay ->
        DuressPolicy.effective(stored, d.hiding, overlay.takeIf { duressSession })
    }
        .onStart { Concealment.ensureLoaded() }
        .map { it.also { _loaded.value = true } }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** A duress unlock opened Parley: changes to the safety switches stay in memory from now until [endDuressSession]. */
    fun beginDuressSession() {
        duressSession = true
        sessionOverlay.value = null
    }

    /** Parley locked: the session's changes are forgotten. */
    fun endDuressSession() {
        duressSession = false
        sessionOverlay.value = null
    }

    init {
        // Pin the layout schema once, before any new default could apply: an existing user keeps
        // separate tabs exactly as they were (see SurfaceLayout.migrate).
        scope.launch {
            catching {
                store.edit { prefs ->
                    val existing = prefs.asMap().keys.any { it.name != K.surfaces.name }
                    SurfaceLayout.migrate(prefs[K.surfaces], existingUser = existing)?.let { prefs[K.surfaces] = it }
                    // Likewise the call-history retention: five years on a new install, unchanged for everyone else.
                    if (prefs[K.retention] == null) prefs[K.retention] = RetentionDefaults.resolve(null, existingUser = !freshInstall)
                }
            }
        }
    }

    /**
     * Current settings as stored. Read from the store rather than [settings], which can lag a moment behind an
     * [update] that just finished (a call placed right after changing "confirm before calling" must see it).
     * DataStore serves this from memory once loaded, and reads disk when the process was just woken by a call.
     */
    suspend fun current(): AppSettings =
        DuressPolicy.effective(store.data.first().toSettings(), Concealment.hiding, sessionOverlay.value.takeIf { duressSession })

    /** All stored preferences as typed strings ("b:true", "i:5", "s:text") for backups. */
    suspend fun exportMap(): Map<String, String> = store.data.first().asMap().mapKeys { it.key.name }.mapValues { (_, v) ->
        when (v) {
            is Boolean -> "b:$v"
            is Int -> "i:$v"
            is Long -> "l:$v"
            else -> "s:$v"
        }
    }

    /**
     * Writes restored settings. While a duress unlock hides things, the safety switches go through the same rule as
     * [update]: a restore in a duress session changes what the screens show until the next lock, never what is stored.
     */
    suspend fun importMap(map: Map<String, String>) {
        store.edit { prefs ->
            val before = prefs.toSettings()
            // A backup from before "Show names as" was a setting of its own has "Sort by" only: names then show the
            // way that phone sorted them, whatever this phone had stored (see NameOrder.showLastFirst).
            if (K.sortFirst.name in map && K.namesLastFirst.name !in map) prefs.remove(K.namesLastFirst)
            map.forEach { (k, v) ->
                val body = v.substringAfter(':')
                when (v.substringBefore(':')) {
                    "b" -> prefs[booleanPreferencesKey(k)] = body.toBoolean()
                    "i" -> body.toIntOrNull()?.let { prefs[intPreferencesKey(k)] = it }
                    "l" -> body.toLongOrNull()?.let { prefs[longPreferencesKey(k)] = it }
                    "s" -> prefs[stringPreferencesKey(k)] = body
                }
            }
            if (duressSession || Concealment.hiding) keepSafetySwitches(prefs, before, map.keys)
        }
    }

    /**
     * A restore while hiding: the safety switches keep their stored values, and the restored ones (or the session's,
     * where the backup had none) become what the screens show until the next lock, as with [update].
     */
    private fun keepSafetySwitches(prefs: MutablePreferences, before: AppSettings, restored: Set<String>) {
        val shown = DuressPolicy.shown(before, sessionOverlay.value)
        val after = prefs.toSettings()
        fun <T> pick(key: Preferences.Key<*>, value: T, kept: T) = if (key.name in restored) value else kept
        val next = after.copy(
            appLock = pick(K.appLock, after.appLock, shown.appLock),
            lockAfterMinutes = pick(K.lockAfter, after.lockAfterMinutes, shown.lockAfterMinutes),
            secureScreen = pick(K.secure, after.secureScreen, shown.secureScreen),
            hideVault = pick(K.hideVault, after.hideVault, shown.hideVault),
            privateVaultHistory = pick(K.privateHistory, after.privateVaultHistory, shown.privateVaultHistory),
            lockScreenCaller = pick(K.lockScreenCaller, after.lockScreenCaller, shown.lockScreenCaller),
        )
        val (toStore, overlay) = DuressPolicy.split(before, next)
        if (duressSession) sessionOverlay.value = overlay
        prefs.write(toStore)
    }

    /**
     * M8: whether private names must stay hidden now, for the private-name providers. They can be the first thing to
     * run in a cold process, before [settings] has loaded (its first value is the defaults, discreet mode off): this
     * reads the stored settings and the duress hiding itself, and fails closed (hidden) when that takes longer than
     * [timeoutMs] or fails.
     */
    suspend fun hidesPrivateNames(timeoutMs: Long = PRIVATE_NAMES_TIMEOUT_MS): Boolean =
        runCatching { withTimeoutOrNull(timeoutMs) { current().hideVault } }.getOrNull() ?: true

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val stored = prefs.toSettings()
            // M6: also while hiding outside a session (Parley locked after a duress unlock, the Quick Settings tile):
            // the user's stored safety switches are never written then; outside a session the change isn't shown either.
            if (duressSession || Concealment.hiding) {
                // The screens change what they show; the stored safety switches stay as they are (I21).
                val (toStore, overlay) = DuressPolicy.split(stored, transform(DuressPolicy.shown(stored, sessionOverlay.value)))
                if (duressSession) sessionOverlay.value = overlay
                prefs.write(toStore)
            } else {
                prefs.write(transform(stored))
            }
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
            showNamesLastFirst = NameOrder.showLastFirst(this[K.namesLastFirst], this[K.sortFirst] ?: d.sortByFirstName),
            showSimLabels = this[K.simLabels] ?: d.showSimLabels,
            defaultAccountType = this[K.accType]?.ifEmpty { null },
            defaultAccountName = this[K.accName]?.ifEmpty { null },
            quickReplies = this[K.replies]?.split(SEP)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: d.quickReplies,
            nameReply = this[K.nameReply] ?: d.nameReply,
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
            // Also before the pin in init is written: an existing user is never read as a new install's five years.
            callLogRetentionDays = RetentionDefaults.resolve(this[K.retention], existingUser = !freshInstall),
            contactRowActions = this[K.rowActions] ?: d.contactRowActions,
            mirrorRelations = this[K.mirrorRelations] ?: d.mirrorRelations,
            askBeforeDeletingTemporary = this[K.askTempDelete] ?: d.askBeforeDeletingTemporary,
            navTabs = NavTabs.decode(this[K.navTabs]),
            recentsLayout = enumOr(this[K.recentsLayout], d.recentsLayout),
            recentsStyle = enumOr(this[K.recentsStyle], d.recentsStyle),
            callBackground = enumOr(this[K.callBackground], d.callBackground),
            lockScreenCaller = enumOr(this[K.lockScreenCaller], d.lockScreenCaller),
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
        // Stored only once it differs from what "Sort by" alone gives (or was stored before), so a phone that never
        // chose it keeps following the old single setting, also when a backup's "Sort by" is restored later.
        NameOrder.toStore(this[K.namesLastFirst], s.sortByFirstName, s.showNamesLastFirst)?.let { this[K.namesLastFirst] = it }
        this[K.simLabels] = s.showSimLabels
        this[K.accType] = s.defaultAccountType.orEmpty()
        this[K.accName] = s.defaultAccountName.orEmpty()
        this[K.replies] = s.quickReplies.joinToString(SEP)
        this[K.nameReply] = s.nameReply
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
        this[K.lockScreenCaller] = s.lockScreenCaller.name
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
        val namesLastFirst = booleanPreferencesKey("names_last_first")
        val simLabels = booleanPreferencesKey("sim_labels")
        val accType = stringPreferencesKey("default_account_type")
        val accName = stringPreferencesKey("default_account_name")
        val replies = stringPreferencesKey("quick_replies")
        val nameReply = stringPreferencesKey("name_reply")
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
        val lockScreenCaller = stringPreferencesKey("lock_screen_caller")
        val showCallerPhoto = booleanPreferencesKey("show_caller_photo")
        val answerWithRtt = booleanPreferencesKey("answer_with_rtt")
        val surfaces = stringPreferencesKey("surface_layout")
        val rememberRecentsFilter = booleanPreferencesKey("remember_recents_filter")
        val recentsFilter = stringPreferencesKey("recents_filter")
    }

    companion object {
        private const val SEP = "\u001F"

        /**
         * Settings that protect the phone's content (app lock, its delay, hiding the screen, discreet mode, private call
         * history, what the lock screen shows about a caller). A backup never changes them on its own: a restore keeps
         * them waiting until the user confirms it's them (the Parley PIN when one is set).
         */
        val SECURITY_KEYS: Set<String> = setOf(
            K.appLock.name, K.lockAfter.name, K.secure.name, K.hideVault.name, K.privateHistory.name, K.lockScreenCaller.name,
        )
    }
}
