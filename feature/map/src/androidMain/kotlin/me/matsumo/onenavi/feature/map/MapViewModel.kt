package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.common.OpenLocationCode
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuideImageGateway
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey
import me.matsumo.onenavi.core.repository.SearchRepository
import me.matsumo.onenavi.feature.map.location.VehicleLocationDataSource
import me.matsumo.onenavi.feature.map.state.ExtNavNavigationGuideImageLoader
import me.matsumo.onenavi.feature.map.state.GuidanceVehicleLocationSelector
import me.matsumo.onenavi.feature.map.state.MapGeodesy
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapPlaceAction
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.NAVIGATION_GUIDE_IMAGE_VISIBILITY_METERS
import me.matsumo.onenavi.feature.map.state.NavigationGuideImage
import me.matsumo.onenavi.feature.map.state.NavigationGuideImageController
import me.matsumo.onenavi.feature.map.state.RoutePreviewTopBarMode
import me.matsumo.onenavi.feature.map.state.VehicleLocationState
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

class MapViewModel(
    private val searchRepository: SearchRepository,
    private val newRouteManager: NewRouteManager,
    private val newGuidanceManager: NewGuidanceManager,
    guideImageGateway: ExtNavGuideImageGateway,
    vehicleLocationDataSource: VehicleLocationDataSource,
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

    /**
     * 地図 UI が読む自車位置。
     *
     * 案内中は `GuidanceProgress` から表示位置を作るため、map 側の SDK road-snapped listener は
     * collect しない。案内中でない場合だけ SDK road-snapped / raw GPS の stream を読む。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val vehicleLocationState: StateFlow<VehicleLocationState?> = newGuidanceState
        .flatMapLatest { guidanceState ->
            when (guidanceState) {
                is GuidanceState.Guiding -> flowOf(GuidanceVehicleLocationSelector.select(guidanceState.progress))
                GuidanceState.Arrived,
                is GuidanceState.Failed,
                GuidanceState.Idle,
                is GuidanceState.Rerouting,
                -> vehicleLocationDataSource.locationUpdates()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(VEHICLE_LOCATION_SUBSCRIPTION_STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

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
        showBrowsing = ::showBrowsing,
    )
    private val guideImageController = NavigationGuideImageController(
        loader = ExtNavNavigationGuideImageLoader(guideImageGateway),
        scope = viewModelScope,
        imageChanged = ::setNavigationGuideImage,
    )

    init {
        observeNavigationGuideImageKey()
    }

    fun onUiEvent(event: MapUiEvent) = uiEventDelegate.onUiEvent(event)

    fun pushScreenState(state: MapScreenState) {
        setScreenStates(_screenStates.value + state)
    }

    fun popScreenState() {
        val states = _screenStates.value
        val nextStates = if (states.size > 1) states.dropLast(1) else states
        setScreenStates(nextStates)
    }

    fun replaceCurrentScreenState(state: MapScreenState) {
        setScreenStates(_screenStates.value.dropLast(1) + state)
    }

    private fun showBrowsing() {
        setScreenStates(listOf(MapScreenState.Browsing))
    }

    private fun setScreenStates(states: List<MapScreenState>) {
        val nextStates = states.ifEmpty { listOf(MapScreenState.Browsing) }
        val nextState = nextStates.last()

        if (nextState is MapScreenState.Browsing) {
            _uiState.update { uiState ->
                uiState.copy(
                    query = null,
                    selectedResult = null,
                )
            }
        }

        _screenStates.value = nextStates
    }

    override fun onCleared() {
        super.onCleared()
        guideImageController.clear()
        newGuidanceManager.release()
    }

    private fun observeNavigationGuideImageKey() {
        newGuidanceState
            .map { guidanceState -> guidanceState.currentGuideImageKeyOrNull() }
            .distinctUntilChanged()
            .onEach { guideImageKey -> guideImageController.onGuideImageKeyChanged(guideImageKey) }
            .launchIn(viewModelScope)
    }

    private fun setNavigationGuideImage(navigationGuideImage: NavigationGuideImage?) {
        _uiState.update { uiState ->
            uiState.copy(navigationGuideImage = navigationGuideImage)
        }
    }

    private fun GuidanceState.currentGuideImageKeyOrNull(): GuideImageKey? {
        val guiding = this as? GuidanceState.Guiding ?: return null
        val banner = guiding.presentation.banner ?: return null
        val isGuideImageVisible = banner.primary.distanceToManeuverMeters <= NAVIGATION_GUIDE_IMAGE_VISIBILITY_METERS
        if (!isGuideImageVisible) return null
        return banner.signpostImageKey
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
    private val showBrowsing: () -> Unit,
) {
    private var placeSearchJob: Job? = null
    private var routeSearchJob: Job? = null
    private var addWaypointRouteSearchJob: Job? = null
    private var navigationAlternativesRouteSearchJob: Job? = null
    private var navigationWaypointEditRouteSearchJob: Job? = null

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

        newGuidanceManager.events
            .onEach { event -> handleGuidanceEvent(event) }
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
            is MapUiEvent.OnSearchResultSelected -> handleSearchResultSelected(event.item)
            is MapUiEvent.OnMapPointOfInterestSelected -> handleMapPointOfInterestSelected(event.placeId, event.name, event.latitude, event.longitude)
            is MapUiEvent.OnMapLongPressed -> handleMapLongPressed(event.latitude, event.longitude)
            is MapUiEvent.OnRouteSearch -> handleRouteSearch(event.item, event.latitude, event.longitude)
            is MapUiEvent.OnPlaceAddWaypointClicked -> handlePlaceAddWaypointClicked(event.item)
            is MapUiEvent.OnRouteIndexChanged -> handleRouteIndexChanged(event.index)
            is MapUiEvent.OnNavigationStart -> handleNavigationStart()
            is MapUiEvent.OnNavigationStop -> handleNavigationStop()
            is MapUiEvent.OnNavigationRoutePreviewClicked -> handleNavigationRoutePreviewClicked()
            is MapUiEvent.OnNavigationRoutePreviewDismissed -> handleNavigationRoutePreviewDismissed()
            is MapUiEvent.OnNavigationWaypointEditConfirmed -> handleNavigationWaypointEditConfirmed(event.waypoints)
            is MapUiEvent.OnNavigationAlternativesClicked -> handleNavigationAlternativesClicked()
            is MapUiEvent.OnNavigationAlternativeRouteSelected -> handleNavigationAlternativeRouteSelected(event.index)
            is MapUiEvent.OnNavigationAlternativesDismissed -> handleNavigationAlternativesDismissed()
            is MapUiEvent.OnRoutePreviewDismissed -> handleRoutePreviewDismissed()
            is MapUiEvent.OnPlaceDetailsDismissed -> handlePlaceDetailsDismissed()
            is MapUiEvent.OnSwapWaypoints -> handleSwapWaypoints()
            is MapUiEvent.OnRouteWaypointsConfirmed -> handleRouteWaypointsConfirmed(event.waypoints)
            is MapUiEvent.OnWaypointEditRequested -> handleWaypointEditRequested(event.index)
            is MapUiEvent.OnAddWaypointRequested -> handleAddWaypointRequested()
            is MapUiEvent.OnAddWaypointSearch -> handleAddWaypointSearch(event.query, event.latitude, event.longitude)
            is MapUiEvent.OnAddWaypointCandidateSelected -> handleAddWaypointCandidateSelected(event.item)
            is MapUiEvent.OnAddWaypointConfirmed -> handleAddWaypointConfirmed()
            is MapUiEvent.OnAddWaypointAlternativesClicked -> handleAddWaypointAlternativesClicked()
            is MapUiEvent.OnWaypointSearchDismissed -> handleWaypointSearchDismissed()
            is MapUiEvent.OnWaypointEditResultConsumed -> handleWaypointEditResultConsumed()
            is MapUiEvent.OnTopAppBarHeightChanged -> handleTopAppBarHeightChanged(event.height)
            is MapUiEvent.OnBottomSheetPeekHeightChanged -> handleBottomSheetPeekHeightChanged(event.height)
            is MapUiEvent.OnNavigationCardHeightChanged -> handleNavigationCardHeightChanged(event.height)
        }
    }

    private fun handleQueryChanged(query: String) {
        uiState.value = uiState.value.copy(query = query)
    }

    private fun handleOnSearch(query: String, latitude: Double?, longitude: Double?) {
        val placeAction = resolvePlaceAction()

        placeSearchJob?.cancel()
        placeSearchJob = scope.launch {
            searchRepository.searchMultiple(query, latitude, longitude)
                .onSuccess { items ->
                    pushScreenState(
                        MapScreenState.SearchResultsList(
                            query = query,
                            results = items.toImmutableList(),
                            placeAction = placeAction,
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

    private fun handleSearchResultSelected(result: SearchResultItem) {
        scope.launch {
            handleResultSelected(result)
        }
    }

    private suspend fun handleResultSelected(result: SearchResultItem) {
        openPlaceDetails(
            result = result,
            addHistory = true,
            placeAction = resolvePlaceAction(),
        )
    }

    private suspend fun openPlaceDetails(
        result: SearchResultItem,
        addHistory: Boolean,
        placeAction: MapPlaceAction,
    ) {
        if (consumeWaypointSearchSelection(result, addHistory)) return

        uiState.value = uiState.value.copy(
            query = result.name,
            selectedResult = result,
        )

        if (addHistory) {
            searchRepository.addHistory(result)
        }

        pushScreenState(
            MapScreenState.PlaceDetails(
                place = result,
                placeAction = placeAction,
            ),
        )
    }

    private fun handleMapPointOfInterestSelected(
        placeId: String,
        name: String,
        latitude: Double,
        longitude: Double,
    ) {
        placeSearchJob?.cancel()
        val placeAction = resolvePlaceAction()

        if (placeId.isBlank()) {
            scope.launch {
                openPlaceDetails(
                    result = createMapPointResult(
                        placeId = null,
                        name = name,
                        latitude = latitude,
                        longitude = longitude,
                    ),
                    addHistory = false,
                    placeAction = placeAction,
                )
            }
            return
        }

        placeSearchJob = scope.launch {
            searchRepository.retrieve(placeId)
                .onSuccess { result ->
                    openPlaceDetails(
                        result = result,
                        addHistory = true,
                        placeAction = placeAction,
                    )
                }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to retrieve map place. id: $placeId" }
                    openPlaceDetails(
                        result = createMapPointResult(
                            placeId = placeId,
                            name = name,
                            latitude = latitude,
                            longitude = longitude,
                        ),
                        addHistory = false,
                        placeAction = placeAction,
                    )
                }
        }
    }

    private fun handleMapLongPressed(
        latitude: Double,
        longitude: Double,
    ) {
        placeSearchJob?.cancel()
        val placeAction = resolvePlaceAction()

        scope.launch {
            openPlaceDetails(
                result = createMapPointResult(
                    placeId = null,
                    name = formatCoordinateName(latitude, longitude),
                    latitude = latitude,
                    longitude = longitude,
                ),
                addHistory = false,
                placeAction = placeAction,
            )
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

    private fun handlePlaceAddWaypointClicked(item: SearchResultItem) {
        val placeDetails = screenStates.value.lastOrNull() as? MapScreenState.PlaceDetails ?: return

        when (val placeAction = placeDetails.placeAction) {
            MapPlaceAction.SearchRoute -> Unit
            is MapPlaceAction.AddWaypointToRoutePreview -> handlePlaceAddWaypointToRoutePreview(
                item = item,
                placeAction = placeAction,
            )
            MapPlaceAction.AddWaypointToNavigation -> handlePlaceAddWaypointToNavigation(item)
        }
    }

    private fun handlePlaceAddWaypointToRoutePreview(
        item: SearchResultItem,
        placeAction: MapPlaceAction.AddWaypointToRoutePreview,
    ) {
        if (!placeAction.canAddWaypoint) return

        val routePreview = popUntilRoutePreview() ?: return
        val waypoint = item.toRouteWaypoint()
        val waypoints = MapRouteWaypointPlanner.addWaypointToRoutePreview(
            waypoints = routePreview.waypoints,
            waypoint = waypoint,
        ) ?: return

        clearPlaceSelectionState()
        replaceRoutePreview(waypoints)
    }

    private fun handlePlaceAddWaypointToNavigation(item: SearchResultItem) {
        if (!popUntilNavigating()) return

        clearPlaceSelectionState()
        addWaypointRouteSearchJob?.cancel()
        addWaypointRouteSearchJob = scope.launch {
            searchAddWaypointRoute(item)
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
        clearNavigationOverlayState()
        newGuidanceManager.stopGuidance()
        handleNavigationRoutePreviewDismissed()

        if (screenStates.value.lastOrNull() is MapScreenState.Navigating) {
            popScreenState()
        }
    }

    private fun handleGuidanceEvent(event: GuidanceEvent) {
        when (event) {
            GuidanceEvent.DestinationReached -> handleDestinationReached()
        }
    }

    private fun handleDestinationReached() {
        clearNavigationOverlayState()
        handleNavigationRoutePreviewDismissed()
        newRouteManager.reset()
        showBrowsing()
    }

    private fun handleNavigationRoutePreviewClicked() {
        val searchContext = createNavigationRouteSearchContext() ?: return
        val editableWaypoints = (searchContext.intermediateWaypoints + searchContext.destinationWaypoint)
            .toImmutableList()
        if (editableWaypoints.isEmpty()) return

        addWaypointRouteSearchJob?.cancel()
        navigationAlternativesRouteSearchJob?.cancel()
        navigationWaypointEditRouteSearchJob?.cancel()
        uiState.value = uiState.value.copy(
            query = null,
            suggestions = persistentListOf(),
            selectedResult = null,
            isNavigationRoutePreviewing = true,
            overlayState = MapOverlayState.NavigationWaypointEditor(
                originWaypoint = searchContext.originWaypoint,
                waypoints = editableWaypoints,
                routePreviewState = RoutePreviewState.Idle,
            ),
        )
    }

    private fun handleNavigationRoutePreviewDismissed() {
        navigationWaypointEditRouteSearchJob?.cancel()
        uiState.update {
            it.copy(
                isNavigationRoutePreviewing = false,
                overlayState = if (it.overlayState is MapOverlayState.NavigationWaypointEditor) {
                    MapOverlayState.None
                } else {
                    it.overlayState
                },
            )
        }
    }

    private fun handleNavigationWaypointEditConfirmed(waypoints: ImmutableList<RouteWaypoint.Place>) {
        if (waypoints.isEmpty()) return
        navigationWaypointEditRouteSearchJob?.cancel()
        navigationWaypointEditRouteSearchJob = scope.launch {
            searchNavigationWaypointEditRoute(waypoints = waypoints)
        }
    }

    private suspend fun searchNavigationWaypointEditRoute(waypoints: ImmutableList<RouteWaypoint.Place>) {
        val overlayState = uiState.value.overlayState as? MapOverlayState.NavigationWaypointEditor ?: return
        val searchContext = createNavigationRouteSearchContext() ?: run {
            updateNavigationWaypointEditorRoutePreviewState(
                waypoints = waypoints,
                routePreviewState = RoutePreviewState.Failed(
                    IllegalStateException("Guidance route is not available"),
                ),
            )
            return
        }
        val currentRoute = currentNavigationRoute()
        val destinationWaypoint = waypoints.last()
        val intermediateWaypoints = waypoints.dropLast(1)
        val routeWaypoints = listOf(searchContext.originWaypoint) + waypoints

        uiState.value = uiState.value.copy(
            overlayState = overlayState.copy(
                waypoints = waypoints,
                routePreviewState = RoutePreviewState.Searching,
            ),
        )

        val routePreviewState = newRouteManager.searchRoutePreview(
            origin = searchContext.origin,
            destination = destinationWaypoint.toRoutePoint(),
            intermediatePoints = intermediateWaypoints.map { waypoint -> waypoint.toRoutePoint() },
            routeWaypoints = routeWaypoints,
            originDirectionDegrees = searchContext.originDirectionDegrees,
        )
        if (uiState.value.overlayState !is MapOverlayState.NavigationWaypointEditor) return

        if (routePreviewState is RoutePreviewState.Failed) {
            Napier.e(routePreviewState.error, TAG) { "Failed to search navigation waypoint edit route." }
            updateNavigationWaypointEditorRoutePreviewState(
                waypoints = waypoints,
                routePreviewState = routePreviewState,
            )
            return
        }

        val readyState = routePreviewState as? RoutePreviewState.Ready ?: return
        val selectedRoute = readyState.routeMatchingPriority(currentRoute)
        newGuidanceManager.startGuidance(route = selectedRoute)
        uiState.value = uiState.value.copy(
            query = null,
            suggestions = persistentListOf(),
            selectedResult = null,
            overlayState = MapOverlayState.None,
            navigationCardHeight = 0,
            isNavigationRoutePreviewing = false,
        )
    }

    private fun updateNavigationWaypointEditorRoutePreviewState(
        waypoints: ImmutableList<RouteWaypoint.Place>,
        routePreviewState: RoutePreviewState,
    ) {
        val overlayState = uiState.value.overlayState as? MapOverlayState.NavigationWaypointEditor ?: return
        uiState.value = uiState.value.copy(
            overlayState = overlayState.copy(
                waypoints = waypoints,
                routePreviewState = routePreviewState,
            ),
        )
    }

    private fun handleNavigationAlternativesClicked() {
        navigationWaypointEditRouteSearchJob?.cancel()
        navigationAlternativesRouteSearchJob?.cancel()
        navigationAlternativesRouteSearchJob = scope.launch {
            searchNavigationAlternativesRoute()
        }
    }

    private suspend fun searchNavigationAlternativesRoute() {
        val searchContext = createNavigationRouteSearchContext() ?: run {
            uiState.value = uiState.value.copy(
                overlayState = MapOverlayState.NavigationAlternatives(
                    routePreviewState = RoutePreviewState.Failed(
                        IllegalStateException("Guidance route is not available"),
                    ),
                ),
            )
            return
        }

        val routeWaypoints = listOf(searchContext.originWaypoint) +
            searchContext.intermediateWaypoints +
            searchContext.destinationWaypoint

        uiState.value = uiState.value.copy(
            query = null,
            suggestions = persistentListOf(),
            selectedResult = null,
            overlayState = MapOverlayState.NavigationAlternatives(
                routePreviewState = RoutePreviewState.Searching,
            ),
        )

        val routePreviewState = newRouteManager.searchRoutePreview(
            origin = searchContext.origin,
            destination = searchContext.destination,
            intermediatePoints = searchContext.intermediateWaypoints.map { waypoint -> waypoint.toRoutePoint() },
            routeWaypoints = routeWaypoints,
            originDirectionDegrees = searchContext.originDirectionDegrees,
        )
        if (uiState.value.overlayState !is MapOverlayState.NavigationAlternatives) return

        if (routePreviewState is RoutePreviewState.Failed) {
            Napier.e(routePreviewState.error, TAG) { "Failed to search navigation alternatives route." }
        }

        uiState.value = uiState.value.copy(
            overlayState = MapOverlayState.NavigationAlternatives(
                routePreviewState = routePreviewState,
            ),
        )
    }

    private fun handleNavigationAlternativesDismissed() {
        navigationAlternativesRouteSearchJob?.cancel()
        val addWaypointAlternatives = uiState.value.overlayState as? MapOverlayState.AddWaypointAlternatives

        if (addWaypointAlternatives != null) {
            uiState.value = uiState.value.copy(
                suggestions = persistentListOf(),
                overlayState = MapOverlayState.AddWaypointSelected(
                    place = addWaypointAlternatives.place,
                    routePreviewState = addWaypointAlternatives.routePreviewState,
                ),
            )
            return
        }

        uiState.value = uiState.value.copy(
            suggestions = persistentListOf(),
            overlayState = MapOverlayState.None,
        )
    }

    private fun handleNavigationAlternativeRouteSelected(index: Int) {
        val currentOverlayState = uiState.value.overlayState
        val candidateRoutePreviewState = currentOverlayState.routePreviewStateForAlternativeSelection()
        val routePreviewState = candidateRoutePreviewState as? RoutePreviewState.Ready ?: return
        val selectedRoute = routePreviewState.routes.getOrNull(index) ?: return

        newGuidanceManager.startGuidance(route = selectedRoute)
        clearNavigationOverlayState()
        handleNavigationRoutePreviewDismissed()
    }

    private fun currentNavigationRoute(): RouteDetail? {
        return when (val guidanceState = newGuidanceManager.state.value) {
            is GuidanceState.Guiding -> guidanceState.route
            is GuidanceState.Rerouting -> guidanceState.previousRoute

            GuidanceState.Arrived,
            is GuidanceState.Failed,
            GuidanceState.Idle,
            -> null
        }
    }

    private fun handleRoutePreviewDismissed() {
        newRouteManager.reset()
        popScreenState()
    }

    private fun handlePlaceDetailsDismissed() {
        popScreenState()

        clearPlaceSelectionState()
    }

    private fun clearPlaceSelectionState() {
        uiState.update { currentUiState ->
            currentUiState.copy(
                query = null,
                selectedResult = null,
            )
        }
    }

    private fun popUntilRoutePreview(): MapScreenState.RoutePreview? {
        while (screenStates.value.size > 1) {
            val currentScreenState = screenStates.value.lastOrNull()
            if (currentScreenState is MapScreenState.RoutePreview) break
            popScreenState()
        }

        return screenStates.value.lastOrNull() as? MapScreenState.RoutePreview
    }

    private fun popUntilNavigating(): Boolean {
        while (screenStates.value.size > 1) {
            val currentScreenState = screenStates.value.lastOrNull()
            if (currentScreenState is MapScreenState.Navigating) break
            popScreenState()
        }

        return screenStates.value.lastOrNull() is MapScreenState.Navigating
    }

    private fun resolvePlaceAction(): MapPlaceAction {
        for (screenState in screenStates.value.asReversed()) {
            val placeAction = screenState.placeActionOrNull()
            if (placeAction != null) return placeAction
        }

        return MapPlaceAction.SearchRoute
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
        openWaypointSearchOverlay(
            MapOverlayState.WaypointSearch(
                initialQuery = null,
                waypointIndex = index,
            ),
        )
    }

    private fun handleAddWaypointRequested() {
        openWaypointSearchOverlay(MapOverlayState.AddWaypointSearch)
    }

    private fun handleAddWaypointSearch(
        query: String,
        latitude: Double?,
        longitude: Double?,
    ) {
        if (query.isBlank()) return

        placeSearchJob?.cancel()
        placeSearchJob = scope.launch {
            searchRepository.searchMultiple(query, latitude, longitude)
                .onSuccess { items ->
                    if (uiState.value.overlayState !is MapOverlayState.AddWaypointSearch) return@onSuccess

                    uiState.value = uiState.value.copy(
                        query = query,
                        suggestions = persistentListOf(),
                        overlayState = MapOverlayState.AddWaypointSearchResults(
                            query = query,
                            results = items.toImmutableList(),
                        ),
                    )
                }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to search waypoint candidates. query: $query" }
                }
        }
    }

    private fun handleAddWaypointCandidateSelected(result: SearchResultItem) {
        addWaypointRouteSearchJob?.cancel()
        addWaypointRouteSearchJob = scope.launch {
            searchRepository.addHistory(result)
            searchAddWaypointRoute(result)
        }
    }

    private fun handleAddWaypointConfirmed() {
        val overlayState = uiState.value.overlayState as? MapOverlayState.AddWaypointSelected ?: return
        val routePreviewState = overlayState.routePreviewState as? RoutePreviewState.Ready ?: return

        newGuidanceManager.startGuidance(route = routePreviewState.selectedRoute)
        clearNavigationOverlayState()
        handleNavigationRoutePreviewDismissed()
    }

    private fun handleAddWaypointAlternativesClicked() {
        val overlayState = uiState.value.overlayState as? MapOverlayState.AddWaypointSelected ?: return

        uiState.value = uiState.value.copy(
            suggestions = persistentListOf(),
            overlayState = MapOverlayState.AddWaypointAlternatives(
                place = overlayState.place,
                routePreviewState = overlayState.routePreviewState,
            ),
        )
    }

    private suspend fun searchAddWaypointRoute(result: SearchResultItem) {
        val searchContext = createNavigationRouteSearchContext() ?: run {
            uiState.value = uiState.value.copy(
                overlayState = MapOverlayState.AddWaypointSelected(
                    place = result,
                    routePreviewState = RoutePreviewState.Failed(
                        IllegalStateException("Guidance route is not available"),
                    ),
                ),
            )
            return
        }

        uiState.value = uiState.value.copy(
            query = result.name,
            suggestions = persistentListOf(),
            overlayState = MapOverlayState.AddWaypointSelected(
                place = result,
                routePreviewState = RoutePreviewState.Searching,
            ),
        )

        val waypoint = result.toRouteWaypoint()
        val intermediateWaypoints = MapRouteWaypointPlanner.insertIntermediateWaypointByMinimalDetour(
            intermediateWaypoints = searchContext.intermediateWaypoints,
            waypoint = waypoint,
            origin = searchContext.origin,
            destination = searchContext.destination,
        )
        val routeWaypoints = listOf(searchContext.originWaypoint) +
            intermediateWaypoints +
            searchContext.destinationWaypoint
        val routePreviewState = newRouteManager.searchRoutePreview(
            origin = searchContext.origin,
            destination = searchContext.destination,
            intermediatePoints = intermediateWaypoints.map { routeWaypoint -> routeWaypoint.toRoutePoint() },
            routeWaypoints = routeWaypoints,
            originDirectionDegrees = searchContext.originDirectionDegrees,
        )
        val currentOverlayState = uiState.value.overlayState
        if (!currentOverlayState.isAddWaypointOverlay()) return

        if (routePreviewState is RoutePreviewState.Failed) {
            Napier.e(routePreviewState.error, TAG) { "Failed to search add waypoint route. placeId: ${result.placeId}" }
        }

        uiState.value = uiState.value.copy(
            overlayState = currentOverlayState.toAddWaypointRoutePreviewOverlay(
                place = result,
                routePreviewState = routePreviewState,
            ),
        )
    }

    private fun createNavigationRouteSearchContext(): NavigationRouteSearchContext? {
        return when (val guidanceState = newGuidanceManager.state.value) {
            is GuidanceState.Guiding -> guidanceState.route.toNavigationRouteSearchContext(
                origin = guidanceState.progress.snappedLocation,
                originDirectionDegrees = guidanceState.progress.bearingDegrees.toInt(),
            )

            is GuidanceState.Rerouting -> guidanceState.previousRoute.toNavigationRouteSearchContext(
                origin = guidanceState.previousProgress.snappedLocation,
                originDirectionDegrees = guidanceState.previousProgress.bearingDegrees.toInt(),
            )

            GuidanceState.Arrived,
            is GuidanceState.Failed,
            GuidanceState.Idle,
            -> null
        }
    }

    private fun openWaypointSearchOverlay(overlayState: MapOverlayState) {
        addWaypointRouteSearchJob?.cancel()
        navigationAlternativesRouteSearchJob?.cancel()
        navigationWaypointEditRouteSearchJob?.cancel()
        uiState.value = uiState.value.copy(
            query = null,
            suggestions = persistentListOf(),
            selectedResult = null,
            overlayState = overlayState,
        )
    }

    private fun handleWaypointSearchDismissed() {
        addWaypointRouteSearchJob?.cancel()
        navigationAlternativesRouteSearchJob?.cancel()
        navigationWaypointEditRouteSearchJob?.cancel()
        uiState.value = uiState.value.copy(
            suggestions = persistentListOf(),
            overlayState = MapOverlayState.None,
        )
    }

    private fun clearNavigationOverlayState() {
        placeSearchJob?.cancel()
        addWaypointRouteSearchJob?.cancel()
        navigationAlternativesRouteSearchJob?.cancel()
        navigationWaypointEditRouteSearchJob?.cancel()
        uiState.value = uiState.value.copy(
            query = null,
            suggestions = persistentListOf(),
            overlayState = MapOverlayState.None,
            navigationCardHeight = 0,
        )
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

    private fun handleNavigationCardHeightChanged(height: Int) {
        uiState.value = uiState.value.copy(navigationCardHeight = height)
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

    private suspend fun consumeWaypointSearchSelection(
        result: SearchResultItem,
        addHistory: Boolean,
    ): Boolean {
        return when (val overlayState = uiState.value.overlayState) {
            MapOverlayState.AddWaypointSearch -> {
                if (addHistory) {
                    searchRepository.addHistory(result)
                }

                if (uiState.value.overlayState.isAddWaypointOverlay()) {
                    addWaypointRouteSearchJob?.cancel()
                    addWaypointRouteSearchJob = scope.launch {
                        searchAddWaypointRoute(result)
                    }
                }
                true
            }

            is MapOverlayState.AddWaypointSearchResults -> {
                addWaypointRouteSearchJob?.cancel()
                addWaypointRouteSearchJob = scope.launch {
                    searchAddWaypointRoute(result)
                }
                true
            }

            is MapOverlayState.AddWaypointSelected,
            is MapOverlayState.AddWaypointAlternatives,
            -> true

            is MapOverlayState.NavigationAlternatives -> false
            is MapOverlayState.NavigationWaypointEditor -> false

            MapOverlayState.None -> false

            is MapOverlayState.WaypointSearch -> {
                if (addHistory) {
                    searchRepository.addHistory(result)
                }

                uiState.value = uiState.value.copy(
                    overlayState = MapOverlayState.None,
                    routeWaypointEditResult = overlayState.waypointIndex to RouteWaypoint.Place(
                        name = result.name,
                        latitude = result.latitude,
                        longitude = result.longitude,
                    ),
                )
                true
            }
        }
    }

    private fun MapOverlayState.isAddWaypointOverlay(): Boolean {
        return when (this) {
            MapOverlayState.AddWaypointSearch,
            is MapOverlayState.AddWaypointSearchResults,
            is MapOverlayState.AddWaypointSelected,
            is MapOverlayState.AddWaypointAlternatives,
            -> true

            is MapOverlayState.NavigationAlternatives,
            is MapOverlayState.NavigationWaypointEditor,
            MapOverlayState.None,
            is MapOverlayState.WaypointSearch,
            -> false
        }
    }

    private fun MapOverlayState.toAddWaypointRoutePreviewOverlay(
        place: SearchResultItem,
        routePreviewState: RoutePreviewState,
    ): MapOverlayState {
        return when (this) {
            is MapOverlayState.AddWaypointAlternatives -> MapOverlayState.AddWaypointAlternatives(
                place = place,
                routePreviewState = routePreviewState,
            )

            MapOverlayState.AddWaypointSearch,
            is MapOverlayState.AddWaypointSearchResults,
            is MapOverlayState.AddWaypointSelected,
            is MapOverlayState.NavigationAlternatives,
            is MapOverlayState.NavigationWaypointEditor,
            MapOverlayState.None,
            is MapOverlayState.WaypointSearch,
            -> MapOverlayState.AddWaypointSelected(
                place = place,
                routePreviewState = routePreviewState,
            )
        }
    }

    private fun MapOverlayState.routePreviewStateForAlternativeSelection(): RoutePreviewState? {
        return when (this) {
            is MapOverlayState.AddWaypointAlternatives -> routePreviewState
            is MapOverlayState.NavigationAlternatives -> routePreviewState
            MapOverlayState.AddWaypointSearch,
            is MapOverlayState.AddWaypointSearchResults,
            is MapOverlayState.AddWaypointSelected,
            is MapOverlayState.NavigationWaypointEditor,
            MapOverlayState.None,
            is MapOverlayState.WaypointSearch,
            -> null
        }
    }

    companion object {
        private const val TAG = "MapViewModel - UiEventDelegate"
        private const val DEBOUNCE = 300L
    }
}

private const val MAP_POINT_ID_PREFIX = "map-point:"

/** 自車位置 stream の一時的な unsubscribe を許容する猶予時間（ms）。 */
private const val VEHICLE_LOCATION_SUBSCRIPTION_STOP_TIMEOUT_MILLIS = 5_000L

private fun MapScreenState.placeActionOrNull(): MapPlaceAction? {
    return when (this) {
        is MapScreenState.PlaceDetails -> placeAction
        is MapScreenState.SearchResultsList -> placeAction
        is MapScreenState.RoutePreview -> MapPlaceAction.AddWaypointToRoutePreview(
            canAddWaypoint = waypoints.size < MAP_ROUTE_PREVIEW_MAX_WAYPOINTS,
        )

        MapScreenState.Navigating -> MapPlaceAction.AddWaypointToNavigation

        is MapScreenState.Arrived,
        MapScreenState.Browsing,
        -> null
    }
}

private fun RoutePreviewState.Ready.routeMatchingPriority(currentRoute: RouteDetail?): RouteDetail {
    val currentPriority = currentRoute?.priority
    return routes.firstOrNull { route -> route.priority == currentPriority } ?: selectedRoute
}

/**
 * 案内中の一時ルート検索に使うコンテキスト。
 *
 * @property origin 仮ルートの出発地点。
 * @property originWaypoint 表示名を保持する出発地点。
 * @property destination 現在案内中ルートの目的地。
 * @property destinationWaypoint 表示名を保持する目的地。
 * @property intermediateWaypoints 現在案内中ルートの未通過経由地。
 * @property originDirectionDegrees 出発地点の進行方向。
 */
@Immutable
private data class NavigationRouteSearchContext(
    val origin: RoutePoint,
    val originWaypoint: RouteWaypoint.CurrentLocation,
    val destination: RoutePoint,
    val destinationWaypoint: RouteWaypoint.Place,
    val intermediateWaypoints: ImmutableList<RouteWaypoint.Place>,
    val originDirectionDegrees: Int,
)

private fun RouteDetail.toNavigationRouteSearchContext(
    origin: RoutePoint,
    originDirectionDegrees: Int,
): NavigationRouteSearchContext {
    val originWaypoint = RouteWaypoint.CurrentLocation(
        latitude = origin.latitude,
        longitude = origin.longitude,
    )
    val displayIntermediateWaypoints = intermediateWaypoints
        .mapIndexed { waypointIndex, point ->
            routeWaypoints.displayPlaceForPoint(
                point = point,
                fallbackIndex = waypointIndex + 1,
            )
        }
        .toImmutableList()
    val destinationWaypoint = routeWaypoints.displayPlaceForPoint(
        point = destination,
        fallbackIndex = routeWaypoints.lastIndex,
    )
    return NavigationRouteSearchContext(
        origin = origin,
        originWaypoint = originWaypoint,
        destination = destination,
        destinationWaypoint = destinationWaypoint,
        intermediateWaypoints = displayIntermediateWaypoints,
        originDirectionDegrees = originDirectionDegrees,
    )
}

private fun List<RouteWaypoint>.displayPlaceForPoint(
    point: RoutePoint,
    fallbackIndex: Int,
): RouteWaypoint.Place {
    val fallbackWaypoint = getOrNull(fallbackIndex) as? RouteWaypoint.Place
    val matchedWaypoint = fallbackWaypoint ?: findPlaceNear(point = point)
    val displayName = matchedWaypoint?.name.orEmpty()
    return RouteWaypoint.Place(
        name = displayName,
        latitude = point.latitude,
        longitude = point.longitude,
    )
}

private fun List<RouteWaypoint>.findPlaceNear(point: RoutePoint): RouteWaypoint.Place? {
    for (waypoint in this) {
        val place = waypoint as? RouteWaypoint.Place ?: continue
        val distanceMeters = MapGeodesy.haversineMeters(place.toRoutePoint(), point)
        if (distanceMeters <= WAYPOINT_NAME_MATCH_DISTANCE_METERS) return place
    }
    return null
}

/** 経由地名を既存地点から引き継ぐため同一点とみなす距離。 */
private const val WAYPOINT_NAME_MATCH_DISTANCE_METERS: Double = 30.0

private fun SearchResultItem.toRouteWaypoint(): RouteWaypoint.Place {
    return RouteWaypoint.Place(
        name = name,
        latitude = latitude,
        longitude = longitude,
    )
}

private fun createMapPointResult(
    placeId: String?,
    name: String,
    latitude: Double,
    longitude: Double,
): SearchResultItem {
    val plusCode = OpenLocationCode.encode(latitude, longitude)

    return SearchResultItem(
        placeId = placeId ?: "$MAP_POINT_ID_PREFIX$plusCode",
        name = name.ifBlank { formatCoordinateName(latitude, longitude) },
        formattedAddress = null,
        shortFormattedAddress = null,
        latitude = latitude,
        longitude = longitude,
        viewportSouth = null,
        viewportWest = null,
        viewportNorth = null,
        viewportEast = null,
        primaryType = null,
        primaryTypeDisplayName = null,
        types = emptyList(),
        googleMapsUri = null,
        websiteUri = null,
        internationalPhoneNumber = null,
        nationalPhoneNumber = null,
        rating = null,
        userRatingCount = null,
        priceLevel = null,
        businessStatus = null,
        iconBackgroundColor = null,
        iconMaskUrl = null,
        editorialSummary = null,
        currentOpeningHours = null,
        isOpenNow = null,
    )
}

private fun formatCoordinateName(latitude: Double, longitude: Double): String {
    return String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
}
