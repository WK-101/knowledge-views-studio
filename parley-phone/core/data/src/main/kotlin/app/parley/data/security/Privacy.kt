package app.parley.data.security

import app.parley.common.catching
import app.parley.common.security.PrivacyView
import app.parley.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where every feature gets its [PrivacyView]: the settings Parley runs on, the duress state ([Concealment]) and the app
 * lock (the app supplies it with [bindAppLock]). It fails closed: before the stored settings have been read, and when
 * they can't be read in time, the view is [PrivacyView.CLOSED].
 */
class Privacy(private val settings: SettingsRepository, scope: CoroutineScope) {
    private val appLock = MutableStateFlow<StateFlow<Boolean>>(MutableStateFlow(false))

    /** The app's lock state ("Parley is locked"); the view's [PrivacyView.appLocked] also needs the app lock on. */
    fun bindAppLock(locked: StateFlow<Boolean>) {
        appLock.value = locked
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val locked: Flow<Boolean> = appLock.flatMapLatest { it }

    /** For screens and anything kept in memory: [PrivacyView.CLOSED] until the stored settings are read. */
    val flow: StateFlow<PrivacyView> = combine(settings.settings, settings.loaded, Concealment.state, locked) { s, loaded, duress, locked ->
        if (loaded) PrivacyView.of(s, duress, locked) else PrivacyView.CLOSED
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PrivacyView.CLOSED)

    /** The view kept in memory now ([flow]'s value): for code that can't wait, closed until the settings are read. */
    fun memory(): PrivacyView = flow.value

    /**
     * The view now, from the stored settings: the first thing a process woken by a call reads may be this, before
     * [flow] has a value. Closed when the settings take longer than [timeoutMs] or fail.
     */
    suspend fun now(timeoutMs: Long = TIMEOUT_MS): PrivacyView = catching {
        withTimeoutOrNull(timeoutMs) { PrivacyView.of(settings.current(), Concealment.ensureLoaded(), appLock.value.value) }
    }.getOrNull() ?: PrivacyView.CLOSED

    /** Whether private contacts are hidden now ([now]'s [PrivacyView.privateHidden]), as a flow for lists to combine. */
    val privateHidden: Flow<Boolean> get() = flow.map { it.privateHidden }.distinctUntilChanged()

    companion object {
        private const val TIMEOUT_MS = 1_500L

        /**
         * For stores that have no settings at hand: the duress state only, discreet mode taken as on ([PrivacyView.duressOnly]).
         * Right for the duress-only items (notes, safe words, ringtones of private contacts); everything private is hidden.
         */
        fun duressOnly(): PrivacyView = PrivacyView.duressOnly(Concealment.ensureLoaded())

        /**
         * Whether a duress unlock hides things, as it changes: the duress state alone, read from its own small file
         * (no settings), so a screen that only asks this isn't closed while the settings load.
         */
        val hidingFlow: Flow<Boolean>
            get() = Concealment.state.map { it.hiding }.distinctUntilChanged()

        /** Emits whenever what a duress unlock hides changes (its state, or what was written while hiding). */
        val duressChanges: Flow<Pair<Boolean, Int>>
            get() = combine(Concealment.state.map { it.hiding }.distinctUntilChanged(), Concealment.revisions) { h, r -> h to r }
    }
}
