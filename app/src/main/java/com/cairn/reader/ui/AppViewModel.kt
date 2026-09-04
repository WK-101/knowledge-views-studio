package com.cairn.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.prefs.AppPreferences
import com.cairn.reader.data.prefs.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Exposes app-wide preferences for theming at the navigation root. */
@HiltViewModel
class AppViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> =
        preferencesRepository.preferences.stateIn(
            viewModelScope, SharingStarted.Eagerly, AppPreferences(),
        )
}
