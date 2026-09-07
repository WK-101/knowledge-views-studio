package com.obliviate.app.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obliviate.app.core.clean.JunkCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CleanViewModel(app: Application) : AndroidViewModel(app) {

    var cacheBytes by mutableLongStateOf(0L)
        private set
    var internal by mutableStateOf(JunkCleaner.StorageStat(0, 0))
        private set
    var shared by mutableStateOf(JunkCleaner.StorageStat(0, 0))
        private set
    var lastFreed by mutableStateOf<Long?>(null)
        private set

    var scanning by mutableStateOf(false)
        private set
    var scanCount by mutableIntStateOf(0)
        private set
    var junk by mutableStateOf<List<JunkCleaner.JunkItem>>(emptyList())
        private set

    init {
        refresh()
    }

    fun refresh() {
        // StatFs is a cheap syscall; the cache walk can touch many files, so keep it off main.
        internal = JunkCleaner.internalStat()
        shared = JunkCleaner.sharedStat()
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            cacheBytes = withContext(Dispatchers.IO) { JunkCleaner.cacheSize(ctx) }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val freed = withContext(Dispatchers.IO) { JunkCleaner.clearCache(ctx) }
            lastFreed = freed
            refresh()
        }
    }

    fun scanJunk() {
        if (scanning) return
        scanning = true
        junk = emptyList()
        scanCount = 0
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                JunkCleaner.scanJunk { c -> scanCount = c }
            }
            junk = found
            scanning = false
        }
    }

    fun deleteJunk() {
        val toDelete = junk
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { JunkCleaner.deleteJunk(toDelete) }
            lastFreed = freed
            junk = emptyList()
            refresh()
        }
    }
}
