package com.cairn.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.prefs.AppPreferences
import com.cairn.reader.data.prefs.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Exposes app-wide preferences for theming and first-run onboarding at the navigation root. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> =
        preferencesRepository.preferences.stateIn(
            viewModelScope, SharingStarted.Eagerly, AppPreferences(),
        )

    fun markOnboardingSeen() = viewModelScope.launch { preferencesRepository.setSeenOnboarding(true) }
}
