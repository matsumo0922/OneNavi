package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.matsumo.onenavi.core.common.OpenLocationCode
import me.matsumo.onenavi.core.common.car.AssistantNavCoordinate
import me.matsumo.onenavi.core.common.car.PhoneDestinationSearchLauncher
import me.matsumo.onenavi.core.datasource.location.CurrentLocationDataSource
import me.matsumo.onenavi.core.datasource.location.VehicleSpeedState
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SavedPlace
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuideImageGateway
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager
import me.matsumo.onenavi.core.navigation.newguidance.model.GpsSignalState
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugSnapshot
import me.matsumo.onenavi.core.repository.SavedPlaceRepository
import me.matsumo.onenavi.core.repository.SearchRepository
import me.matsumo.onenavi.feature.map.location.VehicleLocationDataSource
import me.matsumo.onenavi.feature.map.state.ExtNavNavigationGuideImageLoader
import me.matsumo.onenavi.feature.map.state.GuidanceVehicleLocationSelector
import me.matsumo.onenavi.feature.map.state.MapGeodesy
import me.matsumo.onenavi.feature.map.state.MapGuidanceScreenStateRestorer
import me.matsumo.onenavi.feature.map.state.MapOverlayState
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
    private val savedPlaceRepository: SavedPlaceRepository,
    private val newRouteManager: NewRouteManager,
    private val newGuidanceManager: NewGuidanceManager,
    phoneDestinationSearchLauncher: PhoneDestinationSearchLauncher,
    guideImageGateway: ExtNavGuideImageGateway,
    vehicleLocationDataSource: VehicleLocationDataSource,
    currentLocationDataSource: CurrentLocationDataSource,
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

    /** Guidance 期の GPS signal state を提供する ([GpsSignalState])。 */
    val gpsSignalState: StateFlow<GpsSignalState> = newGuidanceManager.gpsSignalState

    /** core 層で推定した表示用の自車速度。 */
    val vehicleSpeedState: StateFlow<VehicleSpeedState> = currentLocationDataSource.vehicleSpeedState

    /** TTS 発話予定のデバッグスナップショット。 */
    val ttsDebugSnapshot: StateFlow<VoiceAnnouncementDebugSnapshot?> = newGuidanceManager.voiceDebugSnapshot

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
                is GuidanceState.Preparing -> flowOf(GuidanceVehicleLocationSelector.select(guidanceState.initialProgress))
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
        savedPlaceRepository = savedPlaceRepository,
        newRouteManager = newRouteManager,
        newGuidanceManager = newGuidanceManager,
        phoneDestinationSearchLauncher = phoneDestinationSearchLauncher,
        currentLocationDataSource = currentLocationDataSource,
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
    private val bookmarkStateCoordinator = MapBookmarkStateCoordinator(
        savedPlaceRepository = savedPlaceRepository,
        uiState = _uiState,
        scope = viewModelScope,
    )

    init {
        bookmarkStateCoordinator.start()
        observeNavigationGuideImageKey()
        observeGuidanceScreenState()
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
    }

    private fun observeGuidanceScreenState() {
        newGuidanceState
            .onEach(::restoreScreenStateForGuidance)
            .launchIn(viewModelScope)
    }

    private fun restoreScreenStateForGuidance(guidanceState: GuidanceState) {
        val restoredStates = MapGuidanceScreenStateRestorer.restore(
            states = _screenStates.value,
            guidanceState = guidanceState,
        )

        if (restoredStates == _screenStates.value) {
            return
        }

        setScreenStates(restoredStates)
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
    private val savedPlaceRepository: SavedPlaceRepository,
    private val newRouteManager: NewRouteManager,
    private val newGuidanceManager: NewGuidanceManager,
    private val phoneDestinationSearchLauncher: PhoneDestinationSearchLauncher,
    private val currentLocationDataSource: CurrentLocationDataSource,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MapUiState>,
    private val screenStates: StateFlow<List<MapScreenState>>,
    private val pushScreenState: (MapScreenState) -> Unit,
    private val popScreenState: () -> Unit,
    private val replaceCurrentScreenState: (MapScreenState) -> Unit,
    private val showBrowsing: () -> Unit,
) {
    private val bookmarkActionController = MapBookmarkActionController(
        savedPlaceRepository = savedPlaceRepository,
        searchRepository = searchRepository,
        openPlaceDetails = ::openBookmarkedPlaceDetails,
    )
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
            is MapUiEvent.OnAssistantNavigateTo -> handleAssistantNavigateTo(event.query, event.coordinate)
            is MapUiEvent.OnAssistantPreviewRoute -> handleAssistantPreviewRoute(event.query, event.coordinate)
            is MapUiEvent.OnAssistantAddStop -> handleAssistantAddStop(event.query, event.coordinate)
            is MapUiEvent.OnPlaceAddWaypointClicked -> handlePlaceAddWaypointClicked(event.item)
            is MapUiEvent.OnPlaceBookmarkClicked -> handlePlaceBookmarkClicked(event.item)
            is MapUiEvent.OnBookmarkMarkerClicked -> handleBookmarkMarkerClicked(event.place)
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
            is MapUiEvent.OnOverlaySheetDismissed -> handleOverlaySheetDismissed()
            is MapUiEvent.OnSwapWaypoints -> handleSwapWaypoints()
            is MapUiEvent.OnRouteWaypointsConfirmed -> handleRouteWaypointsConfirmed(event.waypoints)
            is MapUiEvent.OnWaypointEditRequested -> handleWaypointEditRequested(event.index)
            is MapUiEvent.OnWaypointSearch -> handleWaypointSearch(event.query, event.latitude, event.longitude)
            is MapUiEvent.OnAddWaypointRequested -> handleAddWaypointRequested()
            is MapUiEvent.OnAddWaypointSearch -> handleAddWaypointSearch(event.query, event.latitude, event.longitude)
            is MapUiEvent.OnAddWaypointCandidateSelected -> handleAddWaypointCandidateSelected(event.item)
            is MapUiEvent.OnAddWaypointConfirmed -> handleAddWaypointConfirmed()
            is MapUiEvent.OnAddWaypointAlternativesClicked -> handleAddWaypointAlternativesClicked()
            MapUiEvent.OnPhoneDestinationSearchClicked -> handlePhoneDestinationSearchClicked()
            MapUiEvent.OnPhoneDestinationSearchRequested -> handlePhoneDestinationSearchRequested()
            MapUiEvent.OnPhoneAddWaypointSearchClicked -> handlePhoneAddWaypointSearchClicked()
            MapUiEvent.OnPhoneAddWaypointSearchRequested -> handlePhoneAddWaypointSearchRequested()
            MapUiEvent.OnSharedGuidanceStarted -> handleSharedGuidanceStarted()
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
        placeSearchJob?.cancel()
        placeSearchJob = scope.launch {
            searchRepository.searchMultiple(query, latitude, longitude)
                .onSuccess { items ->
                    val results = items.toImmutableList()
                    if (screenStates.value.lastOrNull().supportsRouteContextOverlay()) {
                        uiState.value = uiState.value.copy(
                            query = query,
                            suggestions = persistentListOf(),
                            overlayState = MapOverlayState.SearchResults(
                                query = query,
                                results = results,
                            ),
                        )
                    } else {
                        pushScreenState(
                            MapScreenState.SearchResultsList(
                                query = query,
                                results = results,
                            ),
                        )
                    }
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
        )
    }

    private suspend fun openBookmarkedPlaceDetails(result: SearchResultItem) {
        openPlaceDetails(
            result = result,
            addHistory = false,
        )
    }

    private suspend fun openPlaceDetails(
        result: SearchResultItem,
        addHistory: Boolean,
    ) {
        if (consumeWaypointSearchSelection(result, addHistory)) return

        if (screenStates.value.lastOrNull().supportsRouteContextOverlay()) {
            if (addHistory) {
                searchRepository.addHistory(result)
            }
            uiState.value = uiState.value.copy(
                query = result.name,
                suggestions = persistentListOf(),
                overlayState = MapOverlayState.PlaceDetails(place = result),
            )
            return
        }

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
                    )
                }
        }
    }

    private fun handleMapLongPressed(
        latitude: Double,
        longitude: Double,
    ) {
        placeSearchJob?.cancel()

        scope.launch {
            openPlaceDetails(
                result = createMapPointResult(
                    placeId = null,
                    name = formatCoordinateName(latitude, longitude),
                    latitude = latitude,
                    longitude = longitude,
                ),
                addHistory = false,
            )
        }
    }

    private fun handlePlaceBookmarkClicked(item: SearchResultItem) {
        scope.launch {
            bookmarkActionController.togglePlaceBookmark(item)
        }
    }

    private fun handleBookmarkMarkerClicked(place: SavedPlace) {
        scope.launch {
            bookmarkActionController.openBookmarkPlaceDetails(place)
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

    private fun handleAssistantNavigateTo(query: String?, coordinate: AssistantNavCoordinate?) {
        routeSearchJob?.cancel()
        routeSearchJob = scope.launch {
            val originWaypoint = resolveOrigin() ?: run {
                Napier.w(tag = TAG) { "Assistant navigate ignored because current location is unavailable." }
                return@launch
            }
            val destination = resolveDestination(query, coordinate, originWaypoint) ?: return@launch
            val waypoints = persistentListOf(originWaypoint, destination.toRouteWaypoint())
            val routePreviewState = newRouteManager.searchRoutes(waypoints = waypoints)
            val readyState = routePreviewState.toAssistantReadyState("Assistant navigate") ?: return@launch

            ensureActive()
            newGuidanceManager.startGuidance(route = readyState.selectedRoute)
            replaceWithNavigatingScreen()
        }
    }

    private fun handleAssistantPreviewRoute(query: String?, coordinate: AssistantNavCoordinate?) {
        if (newGuidanceManager.state.value.isActiveGuidance()) {
            Napier.i(tag = TAG) { "Assistant preview ignored during active guidance." }
            return
        }

        routeSearchJob?.cancel()
        routeSearchJob = scope.launch {
            val originWaypoint = resolveOrigin() ?: run {
                Napier.w(tag = TAG) { "Assistant preview ignored because current location is unavailable." }
                return@launch
            }
            val destination = resolveDestination(query, coordinate, originWaypoint) ?: return@launch
            val waypoints = persistentListOf(originWaypoint, destination.toRouteWaypoint())

            pushScreenState(
                MapScreenState.RoutePreview(
                    waypoints = waypoints,
                    topBarMode = RoutePreviewTopBarMode.Viewing,
                ),
            )
            newRouteManager.searchRoutes(waypoints = waypoints).toAssistantReadyState("Assistant preview")
        }
    }

    private fun handleAssistantAddStop(query: String?, coordinate: AssistantNavCoordinate?) {
        if (!newGuidanceManager.state.value.isActiveGuidance()) {
            handleAssistantNavigateTo(query, coordinate)
            return
        }

        addWaypointRouteSearchJob?.cancel()
        addWaypointRouteSearchJob = scope.launch {
            val searchContext = createNavigationRouteSearchContext() ?: run {
                handleAssistantNavigateTo(query, coordinate)
                return@launch
            }
            val destination = resolveDestination(query, coordinate, searchContext.originWaypoint) ?: return@launch

            searchRepository.addHistory(destination)
            // Assistant の「次の目的地」より、既存 UI と同じ最小迂回挿入を優先する。
            searchAddWaypointRoute(destination)
        }
    }

    private fun handlePlaceAddWaypointClicked(item: SearchResultItem) {
        if (uiState.value.overlayState !is MapOverlayState.PlaceDetails) return

        when (val screenState = screenStates.value.lastOrNull()) {
            is MapScreenState.RoutePreview -> handlePlaceAddWaypointToRoutePreview(
                item = item,
                routePreview = screenState,
            )

            MapScreenState.Navigating -> handlePlaceAddWaypointToNavigation(item)

            is MapScreenState.Arrived,
            MapScreenState.Browsing,
            is MapScreenState.PlaceDetails,
            is MapScreenState.SearchResultsList,
            null,
            -> Unit
        }
    }

    private fun handlePlaceAddWaypointToRoutePreview(
        item: SearchResultItem,
        routePreview: MapScreenState.RoutePreview,
    ) {
        val waypoint = item.toRouteWaypoint()
        val waypoints = MapRouteWaypointPlanner.addWaypointToRoutePreview(
            waypoints = routePreview.waypoints,
            waypoint = waypoint,
        ) ?: return

        clearOverlaySheetState()
        replaceRoutePreview(waypoints)
    }

    private fun handlePlaceAddWaypointToNavigation(item: SearchResultItem) {
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
        newGuidanceManager.stopGuidance()
    }

    private fun handleGuidanceEvent(event: GuidanceEvent) {
        when (event) {
            GuidanceEvent.DestinationReached -> handleGuidanceStopped()
            GuidanceEvent.Stopped -> handleGuidanceStopped()
        }
    }

    private fun handleGuidanceStopped() {
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
            is GuidanceState.Preparing -> guidanceState.route
            is GuidanceState.Rerouting -> guidanceState.previousRoute

            GuidanceState.Arrived,
            is GuidanceState.Failed,
            GuidanceState.Idle,
            -> null
        }
    }

    private fun handleRoutePreviewDismissed() {
        clearOverlaySheetState(shouldPreserveRoutePreviewPeekHeight = false)
        newRouteManager.reset()
        popScreenState()
    }

    private fun handlePlaceDetailsDismissed() {
        popScreenState()

        clearPlaceSelectionState()
    }

    private fun handleOverlaySheetDismissed() {
        clearOverlaySheetState()
    }

    private fun clearPlaceSelectionState() {
        uiState.update { currentUiState ->
            currentUiState.copy(
                query = null,
                selectedResult = null,
            )
        }
    }

    private fun clearOverlaySheetState(
        shouldPreserveRoutePreviewPeekHeight: Boolean = true,
    ) {
        uiState.update { currentUiState ->
            val overlayState = when (currentUiState.overlayState) {
                is MapOverlayState.PlaceDetails,
                is MapOverlayState.SearchResults,
                -> MapOverlayState.None

                MapOverlayState.AddWaypointSearch,
                is MapOverlayState.AddWaypointSearchResults,
                is MapOverlayState.AddWaypointSelected,
                is MapOverlayState.AddWaypointAlternatives,
                is MapOverlayState.NavigationAlternatives,
                is MapOverlayState.NavigationWaypointEditor,
                MapOverlayState.None,
                is MapOverlayState.WaypointSearch,
                -> currentUiState.overlayState
            }
            val isRoutePreviewScreen = screenStates.value.lastOrNull() is MapScreenState.RoutePreview
            val shouldPreservePeekHeight = shouldPreserveRoutePreviewPeekHeight && isRoutePreviewScreen
            val bottomSheetPeekHeight = if (shouldPreservePeekHeight) {
                currentUiState.bottomSheetPeekHeight
            } else {
                0.dp
            }

            currentUiState.copy(
                query = null,
                suggestions = persistentListOf(),
                overlayState = overlayState,
                bottomSheetPeekHeight = bottomSheetPeekHeight,
            )
        }
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

    private fun handleWaypointSearch(
        query: String,
        latitude: Double?,
        longitude: Double?,
    ) {
        if (query.isBlank()) return

        placeSearchJob?.cancel()
        placeSearchJob = scope.launch {
            searchRepository.searchMultiple(query, latitude, longitude)
                .onSuccess { items ->
                    val waypointSearch = uiState.value.overlayState as? MapOverlayState.WaypointSearch
                        ?: return@onSuccess

                    uiState.value = uiState.value.copy(
                        query = query,
                        suggestions = persistentListOf(),
                        overlayState = MapOverlayState.SearchResults(
                            query = query,
                            results = items.toImmutableList(),
                            waypointIndex = waypointSearch.waypointIndex,
                        ),
                    )
                }
                .onFailure {
                    Napier.e(it, TAG) { "Failed to search waypoint candidates. query: $query" }
                }
        }
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

    private fun handlePhoneDestinationSearchClicked() {
        phoneDestinationSearchLauncher.launchDestinationSearch()
            .onFailure { error ->
                Napier.w(tag = TAG, throwable = error) { "Failed to launch phone destination search." }
            }
    }

    private fun handlePhoneAddWaypointSearchClicked() {
        phoneDestinationSearchLauncher.launchAddWaypointSearch()
            .onFailure { error ->
                Napier.w(tag = TAG, throwable = error) { "Failed to launch phone add waypoint search." }
            }
    }

    private fun handlePhoneDestinationSearchRequested() {
        if (screenStates.value.lastOrNull() is MapScreenState.Navigating) {
            return
        }

        routeSearchJob?.cancel()
        newRouteManager.reset()
        uiState.value = uiState.value.copy(
            query = null,
            suggestions = persistentListOf(),
            selectedResult = null,
            overlayState = MapOverlayState.None,
            bottomSheetPeekHeight = 0.dp,
        )
        showBrowsing()
    }

    private fun handlePhoneAddWaypointSearchRequested() {
        if (createNavigationRouteSearchContext() == null) {
            return
        }

        showSharedGuidanceScreen()
        openWaypointSearchOverlay(MapOverlayState.AddWaypointSearch)
    }

    private fun handleSharedGuidanceStarted() {
        showSharedGuidanceScreen()
    }

    private fun showSharedGuidanceScreen() {
        if (screenStates.value.lastOrNull() is MapScreenState.Navigating) {
            return
        }

        clearNavigationOverlayState()
        showBrowsing()
        pushScreenState(MapScreenState.Navigating)
    }

    private fun replaceWithNavigatingScreen() {
        clearNavigationOverlayState()
        showBrowsing()
        pushScreenState(MapScreenState.Navigating)
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

            is GuidanceState.Preparing -> guidanceState.route.toNavigationRouteSearchContext(
                origin = guidanceState.initialProgress.snappedLocation,
                originDirectionDegrees = guidanceState.initialProgress.bearingDegrees.toInt(),
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

    private suspend fun resolveOrigin(): RouteWaypoint.CurrentLocation? {
        val searchContext = createNavigationRouteSearchContext()
        if (searchContext != null) {
            return searchContext.originWaypoint
        }

        val location = currentLocationDataSource.lastKnown()
            ?: withTimeoutOrNull(FIRST_FIX_TIMEOUT_MILLIS) {
                currentLocationDataSource.locationUpdates().first()
            }

        return location?.let { userLocation ->
            RouteWaypoint.CurrentLocation(
                latitude = userLocation.latitude,
                longitude = userLocation.longitude,
            )
        }
    }

    private suspend fun resolveDestination(
        query: String?,
        coordinate: AssistantNavCoordinate?,
        originWaypoint: RouteWaypoint.CurrentLocation,
    ): SearchResultItem? {
        if (coordinate != null) {
            return createMapPointResult(
                placeId = null,
                name = query.orEmpty(),
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
            )
        }

        val destinationQuery = query?.trim().orEmpty()
        if (destinationQuery.isBlank()) {
            Napier.w(tag = TAG) { "Assistant destination ignored because query and coordinate are empty." }
            return null
        }

        return searchRepository.searchMultiple(
            query = destinationQuery,
            latitude = originWaypoint.latitude,
            longitude = originWaypoint.longitude,
        ).fold(
            onSuccess = { results ->
                results.firstOrNull().also { result ->
                    if (result == null) {
                        Napier.w(tag = TAG) { "Assistant destination not found. query=$destinationQuery" }
                    }
                }
            },
            onFailure = { error ->
                Napier.w(tag = TAG, throwable = error) { "Assistant destination search failed. query=$destinationQuery" }
                null
            },
        )
    }

    private fun RoutePreviewState?.toAssistantReadyState(actionName: String): RoutePreviewState.Ready? {
        return when (this) {
            null -> null
            is RoutePreviewState.Ready -> this
            is RoutePreviewState.Failed -> {
                Napier.w(tag = TAG, throwable = error) { "$actionName route search failed." }
                null
            }

            RoutePreviewState.Idle,
            RoutePreviewState.Searching,
            -> {
                Napier.w(tag = TAG) { "$actionName route search finished without ready state." }
                null
            }
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
            is MapOverlayState.PlaceDetails -> false
            is MapOverlayState.SearchResults -> {
                val waypointIndex = overlayState.waypointIndex
                if (waypointIndex == null) {
                    false
                } else {
                    if (addHistory) {
                        searchRepository.addHistory(result)
                    }

                    uiState.value = uiState.value.copy(
                        overlayState = MapOverlayState.None,
                        routeWaypointEditResult = waypointIndex to RouteWaypoint.Place(
                            name = result.name,
                            latitude = result.latitude,
                            longitude = result.longitude,
                        ),
                    )
                    true
                }
            }

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
            is MapOverlayState.PlaceDetails,
            is MapOverlayState.SearchResults,
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
            is MapOverlayState.PlaceDetails,
            is MapOverlayState.SearchResults,
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
            is MapOverlayState.PlaceDetails,
            is MapOverlayState.SearchResults,
            MapOverlayState.None,
            is MapOverlayState.WaypointSearch,
            -> null
        }
    }

    companion object {
        /** ログ出力用タグ。 */
        private const val TAG = "MapViewModel - UiEventDelegate"

        /** 検索候補入力の debounce 時間。 */
        private const val DEBOUNCE = 300L
    }
}

private const val MAP_POINT_ID_PREFIX = "map-point:"

/** 自車位置 stream の一時的な unsubscribe を許容する猶予時間（ms）。 */
private const val VEHICLE_LOCATION_SUBSCRIPTION_STOP_TIMEOUT_MILLIS = 5_000L

/** アシスタント cold start 時に初回位置を待つ上限時間（ms）。 */
private const val FIRST_FIX_TIMEOUT_MILLIS = 5_000L

private fun MapScreenState?.supportsRouteContextOverlay(): Boolean {
    return when (this) {
        is MapScreenState.RoutePreview,
        MapScreenState.Navigating,
        -> true

        is MapScreenState.Arrived,
        MapScreenState.Browsing,
        is MapScreenState.PlaceDetails,
        is MapScreenState.SearchResultsList,
        null,
        -> false
    }
}

private fun RoutePreviewState.Ready.routeMatchingPriority(currentRoute: RouteDetail?): RouteDetail {
    val currentPriority = currentRoute?.priority
    return routes.firstOrNull { route -> route.priority == currentPriority } ?: selectedRoute
}

private fun GuidanceState.isActiveGuidance(): Boolean {
    return when (this) {
        is GuidanceState.Guiding,
        is GuidanceState.Preparing,
        is GuidanceState.Rerouting,
        -> true

        GuidanceState.Arrived,
        is GuidanceState.Failed,
        GuidanceState.Idle,
        -> false
    }
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
