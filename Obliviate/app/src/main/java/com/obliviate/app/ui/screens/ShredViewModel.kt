package com.obliviate.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obliviate.app.core.wipe.ShredEngine
import com.obliviate.app.core.wipe.WipeMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShredViewModel(app: Application) : AndroidViewModel(app) {

    var items by mutableStateOf<List<ShredEngine.ShredItem>>(emptyList())
        private set
    var methodIdx by mutableIntStateOf(WipeMethod.RANDOM.ordinal)
    var running by mutableStateOf(false)
        private set
    var progressText by mutableStateOf("")
        private set
    var results by mutableStateOf<List<ShredEngine.ShredResult>>(emptyList())
        private set

    fun setFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        results = emptyList()
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            items = withContext(Dispatchers.IO) { uris.map { ShredEngine.describe(ctx, it) } }
        }
    }

    fun run() {
        if (running || items.isEmpty()) return
        running = true
        results = emptyList()
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val method = WipeMethod.entries[methodIdx]
            val r = withContext(Dispatchers.IO) {
                ShredEngine.shred(ctx, items, method) { index, total, name ->
                    progressText = "Shredding ${index + 1}/$total — $name"
                }
            }
            results = r
            running = false
            items = emptyList()
            progressText = ""
        }
    }

    fun clear() {
        items = emptyList()
        results = emptyList()
        progressText = ""
    }
}
