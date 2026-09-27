package app.parley.ui.common

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import app.parley.data.AccountRef

/** Savers for form state that a bundle can't hold as it is (used with `rememberSaveable(stateSaver = …)`). */

/** A set of strings (selected labels, picked keys), as a list. */
val StringSetSaver: Saver<Set<String>, Any> = listSaver(save = { it.toList() }, restore = { it.toSet() })

/** An account choice by type and name; null stays null. */
val AccountRefSaver: Saver<AccountRef?, Any> = listSaver(
    save = { a -> if (a == null) emptyList() else listOf(a.type, a.name) },
    restore = { v -> if (v.isEmpty()) null else AccountRef(v[0], v[1]) },
)

/** Ticks of a list (which items are selected), as a boolean array. */
val BooleanListSaver: Saver<List<Boolean>, Any> = Saver(save = { it.toBooleanArray() }, restore = { (it as BooleanArray).toList() })
