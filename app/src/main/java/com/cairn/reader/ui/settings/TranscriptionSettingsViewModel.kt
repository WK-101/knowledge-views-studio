package com.cairn.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.domain.transcript.SpeechModelManager
import com.cairn.reader.domain.transcript.SpeechToTextEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the Settings → Transcription section: reports whether the on-device speech engine can run,
 *  lists the speech models, and downloads/deletes them (the download itself always works; running
 *  the model needs the native speech pack, which [supported] reflects honestly). */
@HiltViewModel
class TranscriptionSettingsViewModel @Inject constructor(
    private val modelManager: SpeechModelManager,
    engine: SpeechToTextEngine,
) : ViewModel() {

    data class ModelRow(val id: String, val label: String, val approxMb: Int, val installed: Boolean)

    val supported: Boolean = engine.isSupported()

    private val _rows = MutableStateFlow(snapshot())
    val rows: StateFlow<List<ModelRow>> = _rows.asStateFlow()

    private val _installedBytes = MutableStateFlow(modelManager.installedBytes())
    val installedBytes: StateFlow<Long> = _installedBytes.asStateFlow()

    /** (modelId, 0f..1f) while a download runs, else null. */
    private val _downloading = MutableStateFlow<Pair<String, Float>?>(null)
    val downloading: StateFlow<Pair<String, Float>?> = _downloading.asStateFlow()

    private fun snapshot(): List<ModelRow> = modelManager.catalog.map {
        ModelRow(it.id, it.label, it.approxMb, modelManager.isInstalled(it.id))
    }

    private fun refresh() {
        _rows.value = snapshot()
        _installedBytes.value = modelManager.installedBytes()
    }

    fun download(id: String) {
        if (_downloading.value != null) return
        viewModelScope.launch {
            _downloading.value = id to 0f
            runCatching { modelManager.download(id) { f -> _downloading.value = id to f } }
            _downloading.value = null
            refresh()
        }
    }

    fun delete(id: String) {
        modelManager.delete(id)
        refresh()
    }
}
