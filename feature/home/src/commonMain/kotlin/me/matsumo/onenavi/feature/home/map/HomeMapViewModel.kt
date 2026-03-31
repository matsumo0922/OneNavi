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
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.repository.RouteRepository
import me.matsumo.onenavi.core.repository.SearchRepository
import kotlin.time.Duration.Companion.milliseconds

class HomeMapViewModel(
    private val appConfig: AppConfig,
    private val searchRepository: SearchRepository,
    private val routeRepository: RouteRepository,
) : ViewModel() {

    val mapBoxToken: String get() = appConfig.mapBoxToken

    private val _query = MutableStateFlow("")

    private val _suggestions = MutableStateFlow<ImmutableList<SearchSuggestionItem>>(persistentListOf())
    val suggestions: StateFlow<ImmutableList<SearchSuggestionItem>> = _suggestions.asStateFlow()

    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults: StateFlow<ImmutableList<SearchResultItem>> = _searchResults.asStateFlow()

    private val _selectedResult = MutableStateFlow<SearchResultItem?>(null)
    val selectedResult: StateFlow<SearchResultItem?> = _selectedResult.asStateFlow()

    val histories: StateFlow<ImmutableList<SearchHistory>> = searchRepository.histories
        .map { it.toImmutableList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf(),
        )

    private val _userLatitude = MutableStateFlow<Double?>(null)
    private val _userLongitude = MutableStateFlow<Double?>(null)

    private val _routeResults = MutableStateFlow<ImmutableList<RouteItem>>(persistentListOf())
    val routeResults: StateFlow<ImmutableList<RouteItem>> = _routeResults.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    private var searchJob: Job? = null

    init {
        @OptIn(FlowPreview::class)
        _query
            .debounce(DEBOUNCE.milliseconds)
            .distinctUntilChanged()
            .onEach { query -> performSearch(query) }
            .launchIn(viewModelScope)
    }

    fun onViewEvent(event: HomeMapViewEvent) {
        when (event) {
            is HomeMapViewEvent.OnQueryChanged -> onQueryChanged(event.query)
            is HomeMapViewEvent.OnSearch -> onSearch(event.query, event.latitude, event.longitude)
            is HomeMapViewEvent.OnSearchResultSelected -> onSearchResultSelected(event.result)
            is HomeMapViewEvent.OnSuggestionSelected -> onSuggestionSelected(event.suggestion)
            is HomeMapViewEvent.OnHistorySelected -> onHistorySelected(event.history)
            is HomeMapViewEvent.OnRemoveHistory -> onRemoveHistory(event.historyId)
            HomeMapViewEvent.OnRouteSearch -> onRouteSearch()
            is HomeMapViewEvent.OnRouteSelected -> onRouteSelected(event.index)
            HomeMapViewEvent.OnDismissRoutes -> onDismissRoutes()
            HomeMapViewEvent.OnDismissSearchResult -> onDismissSearchResults()
        }
    }

    private fun onQueryChanged(query: String) {
        _query.value = query
        if (query.isBlank()) {
            _suggestions.value = persistentListOf()
        }
    }

    private fun onSuggestionSelected(suggestion: SearchSuggestionItem) {
        viewModelScope.launch {
            searchRepository.select(suggestion.id)
                .onSuccess { result ->
                    _selectedResult.value = result
                    searchRepository.addHistory(result)
                }
                .onFailure {
                    Napier.e(it) { "Failed to select suggestion. id: ${suggestion.id}" }
                }
        }
    }

    private fun onHistorySelected(history: SearchHistory) {
        viewModelScope.launch {
            searchRepository.retrieve(history.id)
                .onSuccess { result ->
                    _selectedResult.value = result
                    searchRepository.addHistory(result)
                }
                .onFailure {
                    Napier.e(it) { "Failed to retrieve history. id: ${history.id}" }
                }
        }
    }

    private fun onRemoveHistory(historyId: String) {
        viewModelScope.launch {
            searchRepository.removeHistory(historyId)
        }
    }

    private fun onSearch(query: String, latitude: Double?, longitude: Double?) {
        searchJob?.cancel()
        if (query.isBlank()) return

        searchJob = viewModelScope.launch {
            _selectedResult.value = null
            searchRepository.searchMultiple(query, latitude, longitude)
                .onSuccess { results ->
                    _searchResults.value = results.toImmutableList()
                }
                .onFailure {
                    Napier.e(it) { "Failed to search multiple. query: $query" }
                    _searchResults.value = persistentListOf()
                }
        }
    }

    private fun onSearchResultSelected(result: SearchResultItem) {
        _searchResults.value = persistentListOf()
        _selectedResult.value = result

        viewModelScope.launch {
            searchRepository.addHistory(result)
        }
    }

    fun onUserLocationUpdated(latitude: Double, longitude: Double) {
        _userLatitude.value = latitude
        _userLongitude.value = longitude
    }

   private fun onRouteSearch() {
        val destination = _selectedResult.value ?: return
        val originLat = _userLatitude.value ?: return
        val originLng = _userLongitude.value ?: return

        viewModelScope.launch {
            routeRepository.searchRoutes(
                originLatitude = originLat,
                originLongitude = originLng,
                destinationLatitude = destination.effectiveLatitude,
                destinationLongitude = destination.effectiveLongitude,
            )
                .onSuccess { routes ->
                    _routeResults.value = routes.toImmutableList()
                    _selectedRouteIndex.value = 0
                }
                .onFailure {
                    Napier.e(it) { "Failed to search routes." }
                    _routeResults.value = persistentListOf()
                }
        }
    }

    fun onRouteSelected(index: Int) {
        _selectedRouteIndex.value = index
    }

    fun onDismissRoutes() {
        _routeResults.value = persistentListOf()
        _selectedRouteIndex.value = 0
    }

    fun onDismissSearchResults() {
        _searchResults.value = persistentListOf()
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
