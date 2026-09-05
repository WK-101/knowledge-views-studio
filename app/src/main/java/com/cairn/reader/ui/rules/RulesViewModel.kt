package com.cairn.reader.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.data.repo.CollectionRepository
import com.cairn.reader.data.repo.RuleRepository
import com.cairn.reader.domain.rules.Rule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    collectionRepository: CollectionRepository,
) : ViewModel() {

    val rules: StateFlow<List<Rule>> =
        ruleRepository.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<CollectionWithCount>> =
        collectionRepository.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(rule: Rule) = viewModelScope.launch { ruleRepository.save(rule) }
    fun delete(id: String) = viewModelScope.launch { ruleRepository.delete(id) }
    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch { ruleRepository.setEnabled(id, enabled) }
}
