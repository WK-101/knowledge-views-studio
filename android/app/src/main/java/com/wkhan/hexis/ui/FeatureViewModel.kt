package com.wkhan.hexis.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Re-audit #16 — the shared base for the per-feature collaborators split out of AppViewModel
 * (TimeTracking / Notes / Habits / GoalsRoutines). Each one had independently re-declared the *same* reactive
 * infra — the active-workspace signal, the synchronous active-workspace read used to stamp new rows, the
 * `.state()` publisher (offload upstream to Default + a lazy WhileSubscribed StateFlow), and `.scopedBy {}`
 * (workspace-filter a whole-table flow) — so changing how collaborators publish state meant editing four
 * copies. Lifting them here makes the pattern single-sourced and every collaborator's read-model behaviour
 * identical by construction (the exact bodies are unchanged from the copies they replace).
 *
 * It is a plain base class, not a ViewModel: AppViewModel constructs one of each collaborator with its own
 * `viewModelScope`, so there is no second lifecycle to manage.
 */
abstract class FeatureViewModel(protected val app: AppViewModel, protected val scope: CoroutineScope) {
    /** The active workspace id as a flow — the scoping key every per-workspace read model funnels through. */
    protected val activeWs: Flow<String> = app.settings.map { it.activeWorkspaceId }

    /** The active workspace id read synchronously — used to STAMP new rows (mirrors AppViewModel's helper). */
    protected fun activeWorkspace(): String = app.settings.value.activeWorkspaceId

    /** Publish a derived flow as a StateFlow: upstream on Default, lazy with a 5s keep-alive past the last collector. */
    protected fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)

    /** Keep only the rows whose [wsOf] equals the active workspace — the one workspace-isolation helper. */
    protected fun <T> Flow<List<T>>.scopedBy(wsOf: (T) -> String): StateFlow<List<T>> =
        combine(this, activeWs) { list, w -> list.filter { wsOf(it) == w } }.state(emptyList())
}
