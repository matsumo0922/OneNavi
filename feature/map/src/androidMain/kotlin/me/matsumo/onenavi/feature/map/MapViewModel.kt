package me.matsumo.onenavi.feature.map

import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.repository.RouteRepository
import me.matsumo.onenavi.core.repository.SearchRepository
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import kotlin.time.Duration.Companion.milliseconds

class MapViewModel(
    private val searchRepository: SearchRepository,
    private val routeRepository: RouteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    private val _screenStates = MutableStateFlow<List<MapScreenState>>(listOf(MapScreenState.Browsing))

    private val uiEventDelegate = UiEventDelegate(
        searchRepository = searchRepository,
        routeRepository = routeRepository,
        scope = viewModelScope,
        uiState = _uiState,
        updateScreenState = {
            Napier.d(tag = TAG) { "Update screen state: $it" }

            _screenStates.update { states ->
                states + it
            }
        }
    )

    val uiState = _uiState.asStateFlow()
    val currentScreenState: StateFlow<MapScreenState> = _screenStates
        .map { it.last() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _screenStates.value.last()
        )

    val hasScreenStateStack: StateFlow<Boolean> = _screenStates
        .map { it.size > 1 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _screenStates.value.size > 1
        )

    fun onUiEvent(event: MapUiEvent) = uiEventDelegate.onUiEvent(event)

    fun onBackPressed() {
        _screenStates.update { states ->
            states.dropLast(1)
        }
    }

    companion object {
        private const val TAG = "MapViewModel"
    }
}

private class UiEventDelegate(
    private val searchRepository: SearchRepository,
    private val routeRepository: RouteRepository,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MapUiState>,
    private val updateScreenState: (MapScreenState) -> Unit,
) {
    private var placeSearchJob: Job? = null

    init {
        scope.launch {
            searchRepository.histories.collect { histories ->
                uiState.value = uiState.value.copy(histories = histories.toImmutableList())
            }
        }

        @OptIn(FlowPreview::class)
        uiState
            .map { it.query }
            .debounce(DEBOUNCE.milliseconds)
            .distinctUntilChanged()
            .onEach { query -> performSearch(query) }
            .launchIn(scope)
    }

    fun onUiEvent(event: MapUiEvent) {
        when (event) {
            is MapUiEvent.OnQueryChanged -> handleQueryChanged(event.query)
            is MapUiEvent.OnQueryCleared -> handleQueryChanged("")
            is MapUiEvent.OnSearch -> handleOnSearch(event.query, event.latitude, event.longitude)
            is MapUiEvent.OnSuggestionSelected -> handleSuggestionSelected(event.suggestion)
            is MapUiEvent.OnHistorySelected -> handleHistorySelected(event.history)
            is MapUiEvent.OnRemoveHistory -> handleRemoveHistory(event.id)
            is MapUiEvent.OnRouteSearch -> handleRouteSearch(event.item)
            is MapUiEvent.OnTopAppBarHeightChanged -> handleTopAppBarHeightChanged(event.height)
            is MapUiEvent.OnBottomSheetPeekHeightChanged -> handleBottomSheetPeekHeightChanged(event.height)
        }
    }

    private fun handleQueryChanged(query: String) {
        uiState.value = uiState.value.copy(query = query)
    }

    private fun handleOnSearch(query: String, latitude: Double?, longitude: Double?) {
        placeSearchJob?.cancel()
        placeSearchJob = scope.launch {
            searchRepository.searchMultiple(query, latitude, longitude)
                .onSuccess { items ->
                    updateScreenState(
                        MapScreenState.SearchResultsList(
                            query = query,
                            results = items.toImmutableList(),
                        )
                    )
                }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to search. query: $query" }
                }
        }
    }

    private fun handleSuggestionSelected(suggestion: SearchSuggestionItem) {
        scope.launch {
            searchRepository.select(suggestion.id)
                .onSuccess { result ->
                    uiState.value = uiState.value.copy(
                        query = result.name,
                        selectedResult = result,
                    )

                    searchRepository.addHistory(result)
                    updateScreenState(
                        MapScreenState.PlaceDetails(
                            place = result,
                        )
                    )
                }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to select suggestion. id: ${suggestion.id}" }
                }
        }
    }

    private fun handleHistorySelected(history: SearchHistory) {
        scope.launch {
            searchRepository.retrieve(history.id)
                .onSuccess { result ->
                    uiState.value = uiState.value.copy(
                        query = result.name,
                        selectedResult = result,
                    )

                    searchRepository.addHistory(result)
                    updateScreenState(
                        MapScreenState.PlaceDetails(
                            place = result,
                        )
                    )
                }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to retrieve history. id: ${history.id}" }
                }
        }
    }

    private fun handleRemoveHistory(historyId: String) {
        scope.launch {
            searchRepository.removeHistory(historyId)
        }
    }

    private fun handleRouteSearch(item: SearchResultItem) {

    }

    private fun handleTopAppBarHeightChanged(height: Float) {
        uiState.value = uiState.value.copy(topAppBarHeight = height)
    }

    private fun handleBottomSheetPeekHeightChanged(height: Dp) {
        uiState.value = uiState.value.copy(bottomSheetPeekHeight = height)
    }

    private fun performSearch(query: String?) {
        placeSearchJob?.cancel()
        placeSearchJob = scope.launch {
            if (query.isNullOrBlank() || query.length < 3) return@launch

            searchRepository.getSuggestions(query)
                .onSuccess { items ->
                    uiState.value = uiState.value.copy(suggestions = items.toImmutableList())
                }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to search. query: $query" }
                    uiState.value = uiState.value.copy(suggestions = persistentListOf())
                }
        }
    }

    companion object {
        private const val TAG = "MapViewModel - UiEventDelegate"
        private const val DEBOUNCE = 300L
    }
}