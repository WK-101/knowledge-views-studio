package com.cairn.reader.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reusable multi-select state for list screens: a live set of picked ids plus the
 * toggle / select-all / clear / consume operations every bulk-action surface needs.
 *
 * Extracted so the list ViewModels share one implementation instead of copy-pasting the
 * same handful of methods over a private `MutableStateFlow<Set<String>>` each. Generic in
 * the id type so it serves both item-id and source-id selections.
 */
class MultiSelectStore<T> {
    private val _selected = MutableStateFlow<Set<T>>(emptySet())
    val selected: StateFlow<Set<T>> = _selected.asStateFlow()

    /** Add [id] to the selection, or remove it if already picked. */
    fun toggle(id: T) = _selected.update { if (id in it) it - id else it + id }

    /** Replace the selection with exactly [ids] (used by "select all currently shown"). */
    fun selectAll(ids: Collection<T>) { _selected.value = ids.toSet() }

    fun clear() { _selected.value = emptySet() }

    /** Snapshot the current selection and clear it in one step, for a consuming bulk action. */
    fun consume(): Set<T> = _selected.value.also { _selected.value = emptySet() }
}
