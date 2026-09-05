package com.cairn.reader.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.repo.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SelectorPickerViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Create a taught-by-example feed; [onResult] gets a success flag + message for a snackbar/toast. */
    fun create(url: String, selector: String, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        _busy.value = true
        val result = feedRepository.followViaSelector(url, selector)
        _busy.value = false
        result.fold(
            onSuccess = { onResult(true, "Feed created from your selection") },
            onFailure = { onResult(false, it.message ?: "Couldn't build a feed from that") },
        )
    }
}
