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
import kotlinx.coroutines.flow.combine
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
import me.matsumo.onenavi.core.navigation.newguidance.NewNavigationSdkManager
import me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.repository.SearchRepository
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.RoutePreviewTopBarMode
import kotlin.time.Duration.Companion.milliseconds

class MapViewModel(
    private val searchRepository: SearchRepository,
    private val newRouteManager: NewRouteManager,
    private val newGuidanceManager: NewGuidanceManager,
    private val newNavigationSdkManager: NewNavigationSdkManager,
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

    /** spec/24 §3.3 [RoutePreviewState]。Preview 期に refined route 候補を提供する。 */
    val newRoutePreviewState: StateFlow<RoutePreviewState> = newRouteManager.state

    /** spec/24 §8 [GuidanceState]。Guidance 期の state machine を提供する。 */
    val newGuidanceState: StateFlow<GuidanceState> = newGuidanceManager.state

    /** Navigator が ready かどうか (terms accept / 初期化エラーは internal で見る)。 */
    val newNavigatorReady: StateFlow<Boolean> = newNavigationSdkManager.navigator
        .map { it != null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    private val uiEventDelegate = UiEventDelegate(
        searchRepository = searchRepository,
        newRouteManager = newRouteManager,
        newGuidanceManager = newGuidanceManager,
        newNavigationSdkManager = newNavigationSdkManager,
        scope = viewModelScope,
        uiState = _uiState,
        pushScreenState = ::pushScreenState,
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

    /** Navigation SDK terms 確認 + Navigator 初期化。Activity 取得後に Compose から呼ぶ。 */
    fun initializeNewSdk(activity: android.app.Activity) {
        newNavigationSdkManager.initialize(activity)
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
    private val newNavigationSdkManager: NewNavigationSdkManager,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MapUiState>,
    private val pushScreenState: (MapScreenState) -> Unit,
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

        // spec/24 §4.3: Preview 期に selectedRoute が決まるたび Navigator に chunk[0] を投入する。
        // navigator がまだ来てない時は state を保持しておき、navigator 取得後に最初の Ready で発火する。
        combine(
            newRouteManager.state,
            newNavigationSdkManager.navigator,
        ) { state, navigator ->
            (state as? RoutePreviewState.Ready)?.selectedRoute?.let { route ->
                navigator?.let { route to it }
            }
        }
            .distinctUntilChanged()
            .onEach { pair ->
                if (pair != null) {
                    val (route, navigator) = pair
                    newGuidanceManager.previewRoute(navigator = navigator, route = route)
                }
            }
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
            is MapUiEvent.OnNavigationStart -> handleRouteStart()
            is MapUiEvent.OnNavigationStop -> handleNavigationStop()
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
                .onSuccess { result ->
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
                    pushScreenState(
                        MapScreenState.PlaceDetails(
                            place = result,
                        ),
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

    private fun handleRouteStart() {
        pushScreenState(
            MapScreenState.Navigating,
        )

        val navigator = newNavigationSdkManager.navigator.value ?: return
        val locationProvider = newNavigationSdkManager.roadSnappedLocationProvider.value ?: return
        val previewState = newRouteManager.state.value as? RoutePreviewState.Ready ?: return
        newGuidanceManager.startGuidance(
            navigator = navigator,
            route = previewState.selectedRoute,
            locationProvider = locationProvider,
        )
    }

    private fun handleNavigationStop() {
        newGuidanceManager.stopGuidance()
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

    companion object {
        private const val TAG = "MapViewModel - UiEventDelegate"
        private const val DEBOUNCE = 300L
    }
}
