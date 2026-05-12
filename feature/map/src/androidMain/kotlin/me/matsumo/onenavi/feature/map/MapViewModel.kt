package me.matsumo.onenavi.feature.map

import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
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
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.repository.SearchRepository
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.RoutePreviewTopBarMode
import kotlin.time.Duration.Companion.milliseconds

class MapViewModel(
    private val searchRepository: SearchRepository,
    private val newRouteManager: NewRouteManager,
    private val newGuidanceManager: NewGuidanceManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    private val _screenStates = MutableStateFlow<List<MapScreenState>>(listOf(MapScreenState.Browsing))

    val uiState = _uiState.asStateFlow()
    val currentScreenState: StateFlow<MapScreenState> = _screenStates
        .map { it.last() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _screenStates.value.last(),
        )

    val hasScreenStateStack: StateFlow<Boolean> = _screenStates
        .map { it.size > 1 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _screenStates.value.size > 1,
        )

    /** Preview 期のルート候補を提供する ([RoutePreviewState])。 */
    val newRoutePreviewState: StateFlow<RoutePreviewState> = newRouteManager.state

    /** Guidance 期の state machine を提供する ([GuidanceState])。 */
    val newGuidanceState: StateFlow<GuidanceState> = newGuidanceManager.state

    private val uiEventDelegate = UiEventDelegate(
        searchRepository = searchRepository,
        newRouteManager = newRouteManager,
        newGuidanceManager = newGuidanceManager,
        scope = viewModelScope,
        uiState = _uiState,
        screenStates = _screenStates,
        pushScreenState = ::pushScreenState,
        popScreenState = ::popScreenState,
        replaceCurrentScreenState = ::replaceCurrentScreenState,
    )

    fun onUiEvent(event: MapUiEvent) = uiEventDelegate.onUiEvent(event)

    fun pushScreenState(state: MapScreenState) {
        _screenStates.update { states ->
            states + state
        }
    }

    fun popScreenState() {
        _screenStates.update { states ->
            states.dropLast(1)
        }
    }

    fun replaceCurrentScreenState(state: MapScreenState) {
        _screenStates.update { states ->
            states.dropLast(1) + state
        }
    }

    override fun onCleared() {
        super.onCleared()
        newGuidanceManager.release()
    }

    companion object {
        private const val TAG = "MapViewModel"
    }
}

private class UiEventDelegate(
    private val searchRepository: SearchRepository,
    private val newRouteManager: NewRouteManager,
    private val newGuidanceManager: NewGuidanceManager,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MapUiState>,
    private val screenStates: StateFlow<List<MapScreenState>>,
    private val pushScreenState: (MapScreenState) -> Unit,
    private val popScreenState: () -> Unit,
    private val replaceCurrentScreenState: (MapScreenState) -> Unit,
) {
    private var placeSearchJob: Job? = null
    private var routeSearchJob: Job? = null

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
            .onEach { query -> performSearchSuggestions(query) }
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
            is MapUiEvent.OnRouteSearch -> handleRouteSearch(event.item, event.latitude, event.longitude)
            is MapUiEvent.OnRouteIndexChanged -> handleRouteIndexChanged(event.index)
            is MapUiEvent.OnNavigationStart -> handleNavigationStart()
            is MapUiEvent.OnNavigationStop -> handleNavigationStop()
            is MapUiEvent.OnRoutePreviewDismissed -> handleRoutePreviewDismissed()
            is MapUiEvent.OnSwapWaypoints -> handleSwapWaypoints()
            is MapUiEvent.OnRouteWaypointsConfirmed -> handleRouteWaypointsConfirmed(event.waypoints)
            is MapUiEvent.OnWaypointEditRequested -> handleWaypointEditRequested(event.index)
            is MapUiEvent.OnWaypointSearchDismissed -> handleWaypointSearchDismissed()
            is MapUiEvent.OnWaypointEditResultConsumed -> handleWaypointEditResultConsumed()
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
                    pushScreenState(
                        MapScreenState.SearchResultsList(
                            query = query,
                            results = items.toImmutableList(),
                        ),
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
                .onSuccess { result -> handleResultSelected(result) }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to select suggestion. id: ${suggestion.id}" }
                }
        }
    }

    private fun handleHistorySelected(history: SearchHistory) {
        scope.launch {
            searchRepository.retrieve(history.id)
                .onSuccess { result -> handleResultSelected(result) }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to retrieve history. id: ${history.id}" }
                }
        }
    }

    private suspend fun handleResultSelected(result: SearchResultItem) {
        if (consumeWaypointEdit(result)) return

        uiState.value = uiState.value.copy(
            query = result.name,
            selectedResult = result,
        )
        searchRepository.addHistory(result)
        pushScreenState(
            MapScreenState.PlaceDetails(
                place = result,
            ),
        )
    }

    private fun handleRemoveHistory(historyId: String) {
        scope.launch {
            searchRepository.removeHistory(historyId)
        }
    }

    private fun handleRouteSearch(item: SearchResultItem, latitude: Double?, longitude: Double?) {
        val waypoints = persistentListOf(
            RouteWaypoint.CurrentLocation(
                latitude = latitude ?: 0.0,
                longitude = longitude ?: 0.0,
            ),
            RouteWaypoint.Place(
                name = item.name,
                latitude = item.latitude,
                longitude = item.longitude,
            ),
        )

        pushScreenState(
            MapScreenState.RoutePreview(
                waypoints = waypoints,
                topBarMode = RoutePreviewTopBarMode.Viewing,
            ),
        )

        routeSearchJob?.cancel()
        routeSearchJob = scope.launch {
            newRouteManager.searchRoutes(waypoints = waypoints)
        }
    }

    private fun handleRouteIndexChanged(index: Int) {
        newRouteManager.selectRoute(index)
    }

    private fun handleNavigationStart() {
        val previewState = newRouteManager.state.value as? RoutePreviewState.Ready ?: return

        pushScreenState(MapScreenState.Navigating)
        newGuidanceManager.startGuidance(route = previewState.selectedRoute)
    }

    private fun handleNavigationStop() {
        newGuidanceManager.stopGuidance()
    }

    private fun handleRoutePreviewDismissed() {
        newRouteManager.reset()
        popScreenState()
    }

    private fun handleSwapWaypoints() {
        val routePreview = screenStates.value.lastOrNull() as? MapScreenState.RoutePreview ?: return
        replaceRoutePreview(routePreview.waypoints.reversed().toImmutableList())
    }

    private fun handleRouteWaypointsConfirmed(waypoints: ImmutableList<RouteWaypoint>) {
        if (waypoints.size < 2) return
        replaceRoutePreview(waypoints)
    }

    private fun replaceRoutePreview(waypoints: ImmutableList<RouteWaypoint>) {
        if (screenStates.value.lastOrNull() !is MapScreenState.RoutePreview) return

        replaceCurrentScreenState(
            MapScreenState.RoutePreview(
                waypoints = waypoints,
                topBarMode = RoutePreviewTopBarMode.Viewing,
            ),
        )

        routeSearchJob?.cancel()
        routeSearchJob = scope.launch {
            newRouteManager.searchRoutes(waypoints = waypoints)
        }
    }

    private fun handleWaypointEditRequested(index: Int) {
        uiState.value = uiState.value.copy(
            query = null,
            suggestions = persistentListOf(),
            selectedResult = null,
            overlayState = MapOverlayState.WaypointSearch(
                initialQuery = null,
                waypointIndex = index,
            ),
        )
    }

    private fun handleWaypointSearchDismissed() {
        uiState.value = uiState.value.copy(overlayState = MapOverlayState.None)
    }

    private fun handleWaypointEditResultConsumed() {
        uiState.value = uiState.value.copy(routeWaypointEditResult = null)
    }

    private fun handleTopAppBarHeightChanged(height: Int) {
        uiState.value = uiState.value.copy(topAppBarHeight = height)
    }

    private fun handleBottomSheetPeekHeightChanged(height: Dp) {
        uiState.value = uiState.value.copy(bottomSheetPeekHeight = height)
    }

    private fun performSearchSuggestions(query: String?) {
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

    private fun consumeWaypointEdit(result: SearchResultItem): Boolean {
        val waypointSearch = uiState.value.overlayState as? MapOverlayState.WaypointSearch ?: return false

        uiState.value = uiState.value.copy(
            overlayState = MapOverlayState.None,
            routeWaypointEditResult = waypointSearch.waypointIndex to RouteWaypoint.Place(
                name = result.name,
                latitude = result.latitude,
                longitude = result.longitude,
            ),
        )
        return true
    }

    companion object {
        private const val TAG = "MapViewModel - UiEventDelegate"
        private const val DEBOUNCE = 300L
    }
}
