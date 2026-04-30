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
import kotlinx.coroutines.ensureActive
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
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.core.navigation.newguidance.NewNavigationSdkManager
import me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.repository.RouteRepository
import me.matsumo.onenavi.core.repository.SearchRepository
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.RoutePreviewTopBarMode
import kotlin.time.Duration.Companion.milliseconds

class MapViewModel(
    private val searchRepository: SearchRepository,
    private val routeRepository: RouteRepository,
    private val routeManager: RouteManager,
    private val guidanceSessionManager: GuidanceSessionManager,
    /**
     * spec/24 の新案内系。本 ViewModel では現状 state 公開のみ行い、Preview/Guidance 中の
     * 実フロー (refine トリガー / startGuidance 呼び出し / polyline 描画) は UI 側で順次
     * 接続していく。
     */
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
        routeRepository = routeRepository,
        routeManager = routeManager,
        guidanceSessionManager = guidanceSessionManager,
        scope = viewModelScope,
        uiState = _uiState,
        currentScreenState = currentScreenState,
        pushScreenState = ::pushScreenState,
        updateScreenState = ::replaceCurrentScreenState,
    )

    fun onUiEvent(event: MapUiEvent) = uiEventDelegate.onUiEvent(event)

    fun pushScreenState(state: MapScreenState) {
        _screenStates.update { states ->
            states + state
        }
    }

    fun replaceCurrentScreenState(state: MapScreenState) {
        _screenStates.update { states ->
            states.dropLast(1) + state
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

    /**
     * spec/23 ベースの refine を起動する。UI で目的地確定後に並走で呼ぶ想定。
     * 既存の routeRepository ベースのフローは破壊せず、Preview 用に別系統で refined route を
     * 用意する。
     */
    fun startRouteRefine(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ) {
        viewModelScope.launch {
            newRouteManager.searchRoutes(
                origin = RoutePoint(
                    latitude = originLatitude,
                    longitude = originLongitude,
                ),
                destination = RoutePoint(
                    latitude = destinationLatitude,
                    longitude = destinationLongitude,
                ),
            )
        }
    }

    /** Preview 中のルート切替を新 manager にも伝える。 */
    fun selectNewRoute(index: Int) {
        newRouteManager.selectRoute(index)
    }

    /** Preview から Guidance に遷移するときに呼ぶ。Navigator が ready でなければ no-op。 */
    fun startNewGuidance() {
        val navigator = newNavigationSdkManager.navigator.value ?: return
        val locationProvider = newNavigationSdkManager.roadSnappedLocationProvider.value ?: return
        val previewState = newRouteManager.state.value as? RoutePreviewState.Ready ?: return
        newGuidanceManager.startGuidance(
            navigator = navigator,
            route = previewState.selectedRoute,
            locationProvider = locationProvider,
        )
    }

    /** Guidance を停止する。UI 側で「案内停止」ボタンや画面離脱時に呼ぶ。 */
    fun stopNewGuidance() {
        newGuidanceManager.stopGuidance()
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
    private val routeRepository: RouteRepository,
    private val routeManager: RouteManager,
    private val guidanceSessionManager: GuidanceSessionManager,
    private val scope: CoroutineScope,
    private val currentScreenState: StateFlow<MapScreenState>,
    private val uiState: MutableStateFlow<MapUiState>,
    private val pushScreenState: (MapScreenState) -> Unit,
    private val updateScreenState: (MapScreenState) -> Unit,
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
            is MapUiEvent.OnRouteSearch -> handleRouteSearch(event.item, event.latitude, event.longitude)
            is MapUiEvent.OnRouteIndexChanged -> handleRouteIndexChanged(event.index)
            is MapUiEvent.OnNavigationStart -> handleRouteStart(event.routeResult)
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
        val waypoints = listOf(
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

        searchRoutesFromWaypoints(waypoints.toImmutableList())
    }

    private fun handleRouteIndexChanged(index: Int) {
        val routePreviewScreenState = currentScreenState.value as? MapScreenState.RoutePreview ?: return
        val newScreenState = routePreviewScreenState.copy(selectedRouteIndex = index)

        updateScreenState(newScreenState)
    }

    private fun handleRouteStart(routeResult: RouteResult) {
        routeManager.setRoutes(listOf(routeResult.googleRoute))
        pushScreenState(
            MapScreenState.Navigating,
        )
    }

    private fun handleTopAppBarHeightChanged(height: Int) {
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

    private fun searchRoutesFromWaypoints(waypoints: ImmutableList<RouteWaypoint>) {
        if (waypoints.size < 2) return

        val originCoordinates = waypoints.first().let { it.latitude to it.longitude }
        val destinationCoordinates = waypoints.last().let { it.latitude to it.longitude }
        val intermediateWaypoints = waypoints
            .drop(1)
            .dropLast(1)
            .map { waypoint -> waypoint.latitude to waypoint.longitude }

        routeSearchJob?.cancel()
        routeSearchJob = scope.launch {
            routeRepository.searchRoutes(
                originLatitude = originCoordinates.first,
                originLongitude = originCoordinates.second,
                destinationLatitude = destinationCoordinates.first,
                destinationLongitude = destinationCoordinates.second,
                intermediateWaypoints = intermediateWaypoints,
            ).onSuccess { coreResults ->
                ensureActive()

                val featureResults = coreResults.mapNotNull { it.toFeatureRouteResult() }

                routeManager.setRoutes(featureResults.map { it.googleRoute })
                guidanceSessionManager.setNavigationState(NavigationState.RoutePreview)

                pushScreenState(
                    MapScreenState.RoutePreview(
                        waypoints = waypoints.toImmutableList(),
                        routes = featureResults.toImmutableList(),
                        selectedRouteIndex = 0,
                        topBarMode = RoutePreviewTopBarMode.Viewing,
                    ),
                )
            }.onFailure {
                Napier.e(it, TAG) { "Failed to search routes. waypoints: $waypoints" }
            }
        }
    }

    companion object {
        private const val TAG = "MapViewModel - UiEventDelegate"
        private const val DEBOUNCE = 300L
    }
}
