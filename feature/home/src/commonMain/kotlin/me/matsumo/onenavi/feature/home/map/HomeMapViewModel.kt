package me.matsumo.onenavi.feature.home.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.repository.SearchRepository
import kotlin.time.Duration.Companion.milliseconds

class HomeMapViewModel(
    private val appConfig: AppConfig,
    private val searchRepository: SearchRepository,
) : ViewModel() {

    val mapBoxToken: String get() = appConfig.mapBoxToken

    private val _query = MutableStateFlow("")

    private val _suggestions = MutableStateFlow<ImmutableList<SearchSuggestionItem>>(persistentListOf())
    val suggestions: StateFlow<ImmutableList<SearchSuggestionItem>> = _suggestions.asStateFlow()

    private val _selectedResult = MutableStateFlow<SearchResultItem?>(null)
    val selectedResult: StateFlow<SearchResultItem?> = _selectedResult.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val histories: StateFlow<ImmutableList<SearchHistory>> = searchRepository.histories
        .map { it.toImmutableList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf(),
        )

    private var searchJob: Job? = null

    init {
        @OptIn(FlowPreview::class)
        _query
            .debounce(DEBOUNCE.milliseconds)
            .distinctUntilChanged()
            .onEach { query -> performSearch(query) }
            .launchIn(viewModelScope)
    }

    fun onQueryChanged(query: String) {
        _query.value = query
        if (query.isBlank()) {
            _suggestions.value = persistentListOf()
        }
    }

    fun onSuggestionSelected(suggestion: SearchSuggestionItem) {
        viewModelScope.launch {
            _isSearching.value = true
            searchRepository.select(suggestion.id)
                .onSuccess { result ->
                    _selectedResult.value = result
                    searchRepository.addHistory(result)
                }
            _isSearching.value = false
        }
    }

    fun onHistorySelected(history: SearchHistory) {
        _selectedResult.value = SearchResultItem(
            id = history.id,
            name = history.name,
            address = history.address,
            latitude = history.latitude,
            longitude = history.longitude,
            categories = emptyList(),
        )
    }

    fun onRemoveHistory(historyId: String) {
        viewModelScope.launch {
            searchRepository.removeHistory(historyId)
        }
    }

    fun onDismissResult() {
        _selectedResult.value = null
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) return

        searchJob = viewModelScope.launch {
            searchRepository.getSuggestions(query)
                .onSuccess { items ->
                    _suggestions.value = items.toImmutableList()
                }
                .onFailure {
                    Napier.e(it) { "Failed to search. query: $query" }
                    _suggestions.value = persistentListOf()
                }
        }
    }

    companion object {
        private const val DEBOUNCE = 300L
    }
}
