package me.matsumo.onenavi.feature.home.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.core.repository.RouteRepository
import me.matsumo.onenavi.core.repository.SearchRepository
import me.matsumo.onenavi.feature.home.map.state.HomeMapEffect
import me.matsumo.onenavi.feature.home.map.state.HomeMapOverlayState
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState
import me.matsumo.onenavi.feature.home.map.state.RoutePreviewTopBarMode
import me.matsumo.onenavi.feature.home.map.state.reduceScreenState
import kotlin.time.Duration.Companion.milliseconds

class HomeMapViewModel(
    private val searchRepository: SearchRepository,
    private val routeRepository: RouteRepository,
    internal val routeManager: RouteManager,
    internal val cameraManager: CameraManager,
    internal val guidanceSessionManager: GuidanceSessionManager,
) : ViewModel() {

    // ── 既存 raw state ──

    private val _query = MutableStateFlow("")

    private val _suggestions = MutableStateFlow<ImmutableList<SearchSuggestionItem>>(persistentListOf())
    val suggestions: StateFlow<ImmutableList<SearchSuggestionItem>> = _suggestions.asStateFlow()

    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())

    private val _selectedResult = MutableStateFlow<SearchResultItem?>(null)

    val histories: StateFlow<ImmutableList<SearchHistory>> = searchRepository.histories
        .map { it.toImmutableList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf(),
        )

    private val _userLatitude = MutableStateFlow<Double?>(null)
    private val _userLongitude = MutableStateFlow<Double?>(null)

    private val _routeResults = MutableStateFlow<ImmutableList<RouteResult>>(persistentListOf())

    private val _waypoints = MutableStateFlow<ImmutableList<RouteWaypoint>>(persistentListOf())

    // ── 新規追加 raw state ──

    private val _topBarMode = MutableStateFlow<RoutePreviewTopBarMode>(RoutePreviewTopBarMode.Viewing)
    private val _isRouteSearching = MutableStateFlow(false)
    private val _lastSearchQuery = MutableStateFlow("")

    val selectedRouteIndex: StateFlow<Int> = combine(
        _routeResults,
        routeManager.routes,
    ) { routeResults, navigationRoutes ->
        val primaryRouteId = navigationRoutes.firstOrNull()?.id
        routeResults.indexOfFirst { it.googleRoute.id == primaryRouteId }.takeIf { it >= 0 } ?: 0
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0,
    )

    // ── 導出: screenState（Array 版 combine を使用）──

    @Suppress("UNCHECKED_CAST")
    val screenState: StateFlow<HomeMapScreenState> = combine(
        _searchResults,
        _selectedResult,
        _routeResults,
        _waypoints,
        selectedRouteIndex,
        _topBarMode,
        _lastSearchQuery,
        guidanceSessionManager.navigationState,
        _isRouteSearching,
    ) { values ->
        reduceScreenState(
            searchResults = values[0] as ImmutableList<SearchResultItem>,
            selectedResult = values[1] as SearchResultItem?,
            routeResults = values[2] as ImmutableList<RouteResult>,
            waypoints = values[3] as ImmutableList<RouteWaypoint>,
            selectedRouteIndex = values[4] as Int,
            topBarMode = values[5] as RoutePreviewTopBarMode,
            lastSearchQuery = values[6] as String,
            navigationState = values[7] as NavigationState,
            isRouteSearching = values[8] as Boolean,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeMapScreenState.Browsing,
    )

    // ── Overlay State ──

    private val _overlayState = MutableStateFlow<HomeMapOverlayState>(HomeMapOverlayState.None)
    val overlayState: StateFlow<HomeMapOverlayState> = _overlayState.asStateFlow()

    // ── Effect Stream ──

    private val _effects = Channel<HomeMapEffect>(Channel.BUFFERED)
    val effects: Flow<HomeMapEffect> = _effects.receiveAsFlow()

    // ── raw state 公開（screenState で導出できないデータを Composable に渡すため）──

    val searchResults: StateFlow<ImmutableList<SearchResultItem>> = _searchResults.asStateFlow()
    val selectedResult: StateFlow<SearchResultItem?> = _selectedResult.asStateFlow()
    val routeResults: StateFlow<ImmutableList<RouteResult>> = _routeResults.asStateFlow()
    val waypoints: StateFlow<ImmutableList<RouteWaypoint>> = _waypoints.asStateFlow()

    private val _waypointEditResultInternal = MutableStateFlow<Pair<Int, RouteWaypoint.Place>?>(null)
    val waypointEditResult: StateFlow<Pair<Int, RouteWaypoint.Place>?> = _waypointEditResultInternal.asStateFlow()

    private var searchJob: Job? = null
    private var routeSearchJob: Job? = null

    init {
        routeManager.register()
        cameraManager.register()
        guidanceSessionManager.register()

        routeManager.routes
            .onEach { routes -> cameraManager.onRouteChanged(routes.firstOrNull()) }
            .launchIn(viewModelScope)

        @OptIn(FlowPreview::class)
        _query
            .debounce(DEBOUNCE.milliseconds)
            .distinctUntilChanged()
            .onEach { query -> performSearch(query) }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        routeManager.unregister()
        cameraManager.unregister()
        guidanceSessionManager.unregister()
    }

    fun onBackPressed() {
        when (val overlay = _overlayState.value) {
            is HomeMapOverlayState.WaypointSearch -> {
                _overlayState.value = HomeMapOverlayState.None
            }
            HomeMapOverlayState.None -> {
                when (screenState.value) {
                    is HomeMapScreenState.SearchResultsList -> {
                        _searchResults.value = persistentListOf()
                    }
                    is HomeMapScreenState.PlaceDetails -> {
                        _selectedResult.value = null
                    }
                    is HomeMapScreenState.RoutePreview -> {
                        routeSearchJob?.cancel()
                        _routeResults.value = persistentListOf()
                        _waypoints.value = persistentListOf()
                        _topBarMode.value = RoutePreviewTopBarMode.Viewing
                        _isRouteSearching.value = false
                        routeManager.clearRoutes()
                        guidanceSessionManager.setNavigationState(NavigationState.Browsing)
                        // selectedResult は維持 → reduce が PlaceDetails を返す
                        val place = _selectedResult.value
                        if (place != null) {
                            _effects.trySend(HomeMapEffect.MoveCameraToPlace(place))
                        }
                    }
                    is HomeMapScreenState.Navigating -> {
                        onNavigationStopped()
                    }
                    is HomeMapScreenState.Arrived -> {
                        onArrivalDismissed()
                    }
                    else -> { /* Browsing: 何もしない */ }
                }
            }
        }
    }

    internal fun onNavigationStarted() {
        guidanceSessionManager.startSession()
        _effects.trySend(HomeMapEffect.EnterGuidanceFollowing)
        _effects.trySend(HomeMapEffect.SetKeepScreenOn(enabled = true))
    }

    fun onNavigationStopped() {
        guidanceSessionManager.stopSession()
        guidanceSessionManager.setNavigationState(NavigationState.Browsing)
        _isRouteSearching.value = true

        clearGuidanceEffects()
        _effects.trySend(HomeMapEffect.MoveCameraToRouteOverview)

        // waypoints を保持してルート再検索（旧ルートは表示し続け、再検索完了で差し替え）
        searchRoutesFromWaypoints(_waypoints.value)
    }

    internal fun onArrivalDismissed() {
        guidanceSessionManager.stopSession()
        guidanceSessionManager.setNavigationState(NavigationState.Browsing)
        _routeResults.value = persistentListOf()
        _waypoints.value = persistentListOf()
        _selectedResult.value = null
        _topBarMode.value = RoutePreviewTopBarMode.Viewing
        _isRouteSearching.value = false
        routeManager.clearRoutes()
        clearGuidanceEffects()
    }

    internal fun onQueryChanged(query: String) {
        _query.value = query

        if (query.isBlank()) {
            _suggestions.value = persistentListOf()
        }
    }

    internal fun onSuggestionSelected(suggestion: SearchSuggestionItem) {
        viewModelScope.launch {
            searchRepository.select(suggestion.id)
                .onSuccess { result ->
                    _selectedResult.value = result
                    searchRepository.addHistory(result)
                    _effects.trySend(HomeMapEffect.MoveCameraToPlace(result))
                }
                .onFailure {
                    Napier.e(it) { "Failed to select suggestion. id: ${suggestion.id}" }
                }
        }
    }

    internal fun onHistorySelected(history: SearchHistory) {
        viewModelScope.launch {
            searchRepository.retrieve(history.id)
                .onSuccess { result ->
                    _selectedResult.value = result
                    searchRepository.addHistory(result)
                    _effects.trySend(HomeMapEffect.MoveCameraToPlace(result))
                }
                .onFailure {
                    Napier.e(it) { "Failed to retrieve history. id: ${history.id}" }
                }
        }
    }

    internal fun onRemoveHistory(historyId: String) {
        viewModelScope.launch {
            searchRepository.removeHistory(historyId)
        }
    }

    internal fun onSearch(query: String, latitude: Double?, longitude: Double?) {
        searchJob?.cancel()
        if (query.isBlank()) return

        _lastSearchQuery.value = query
        _selectedResult.value = null

        searchJob = viewModelScope.launch {
            searchRepository.searchMultiple(query, latitude, longitude)
                .onSuccess { results ->
                    val immutableResults = results.toImmutableList()
                    _searchResults.value = immutableResults
                    _effects.trySend(HomeMapEffect.MoveCameraToSearchResults(immutableResults))
                }
                .onFailure {
                    Napier.e(it) { "Failed to search multiple. query: $query" }
                    _searchResults.value = persistentListOf()
                }
        }
    }

    internal fun onSearchResultSelected(result: SearchResultItem) {
        _searchResults.value = persistentListOf()
        _selectedResult.value = result

        _effects.trySend(HomeMapEffect.MoveCameraToPlace(result))

        viewModelScope.launch {
            searchRepository.addHistory(result)
        }
    }

    fun onUserLocationUpdated(latitude: Double, longitude: Double) {
        _userLatitude.value = latitude
        _userLongitude.value = longitude
    }

    internal fun onRouteSearch() {
        val destination = _selectedResult.value ?: return
        val originLat = _userLatitude.value ?: return
        val originLng = _userLongitude.value ?: return

        val newWaypoints = persistentListOf(
            RouteWaypoint.CurrentLocation(
                latitude = originLat,
                longitude = originLng,
            ),
            RouteWaypoint.Place(
                name = destination.name,
                latitude = destination.latitude,
                longitude = destination.longitude,
            ),
        )

        _waypoints.value = newWaypoints
        _routeResults.value = persistentListOf() // 旧ルートをクリアしてから検索
        _isRouteSearching.value = true
        searchRoutesFromWaypoints(newWaypoints)
    }

    fun onRouteSelected(index: Int) {
        val routeId = _routeResults.value.getOrNull(index)?.googleRoute?.id ?: return
        routeManager.selectRoute(routeId)
        _effects.trySend(HomeMapEffect.MoveCameraToRouteOverview)
    }

    internal fun onSwapOriginDestination() {
        val current = _waypoints.value
        if (current.size != 2) return

        val swapped = persistentListOf(current[1], current[0])

        _waypoints.value = swapped
        _routeResults.value = persistentListOf()
        _isRouteSearching.value = true

        searchRoutesFromWaypoints(swapped)
    }

    internal fun onRouteWaypointsConfirmed(newWaypoints: ImmutableList<RouteWaypoint>) {
        _waypoints.value = newWaypoints
        _topBarMode.value = RoutePreviewTopBarMode.Viewing
        _routeResults.value = persistentListOf()
        _isRouteSearching.value = true

        searchRoutesFromWaypoints(newWaypoints)
    }

    private fun searchRoutesFromWaypoints(waypoints: ImmutableList<RouteWaypoint>) {
        if (waypoints.size < 2) return

        val origin = waypoints.first()
        val destination = waypoints.last()

        val originLat = when (origin) {
            is RouteWaypoint.CurrentLocation -> origin.latitude
            is RouteWaypoint.Place -> origin.latitude
        }
        val originLng = when (origin) {
            is RouteWaypoint.CurrentLocation -> origin.longitude
            is RouteWaypoint.Place -> origin.longitude
        }
        val destLat = when (destination) {
            is RouteWaypoint.CurrentLocation -> destination.latitude
            is RouteWaypoint.Place -> destination.latitude
        }
        val destLng = when (destination) {
            is RouteWaypoint.CurrentLocation -> destination.longitude
            is RouteWaypoint.Place -> destination.longitude
        }

        val intermediateWaypoints = waypoints
            .drop(1)
            .dropLast(1)
            .map { waypoint ->
                when (waypoint) {
                    is RouteWaypoint.CurrentLocation -> waypoint.latitude to waypoint.longitude
                    is RouteWaypoint.Place -> waypoint.latitude to waypoint.longitude
                }
            }

        routeSearchJob?.cancel()
        routeSearchJob = viewModelScope.launch {
            val result = routeRepository.searchRoutes(
                originLatitude = originLat,
                originLongitude = originLng,
                destinationLatitude = destLat,
                destinationLongitude = destLng,
                intermediateWaypoints = intermediateWaypoints,
            )

            // キャンセル済みのジョブが結果を適用しないようにする
            ensureActive()

            result
                .onSuccess { coreResults ->
                    val featureResults = coreResults.mapNotNull { it.toFeatureRouteResult() }
                    _topBarMode.value = RoutePreviewTopBarMode.Viewing

                    routeManager.setRoutes(featureResults.map { it.googleRoute })
                    guidanceSessionManager.setNavigationState(NavigationState.RoutePreview)
                    _routeResults.value = featureResults.toImmutableList()
                    _isRouteSearching.value = false
                    _effects.trySend(HomeMapEffect.MoveCameraToRouteOverview)
                }
                .onFailure {
                    Napier.e(it) { "Failed to search routes." }
                    _routeResults.value = persistentListOf()
                    _isRouteSearching.value = false
                }
        }
    }

    internal fun onMapLandmarkSelected(name: String?, latitude: Double, longitude: Double) {
        _searchResults.value = persistentListOf()

        if (name.isNullOrBlank()) {
            val result = createCoordinateOnlyResult(latitude, longitude)
            _selectedResult.value = result
            _effects.trySend(HomeMapEffect.MoveCameraToPlace(result))
            return
        }

        viewModelScope.launch {
            searchRepository.searchMultiple(name, latitude, longitude)
                .onSuccess { results ->
                    val closest = results.minByOrNull { result ->
                        val dLat = result.latitude - latitude
                        val dLng = result.longitude - longitude
                        dLat * dLat + dLng * dLng
                    }

                    if (closest != null) {
                        _selectedResult.value = closest
                        searchRepository.addHistory(closest)
                        _effects.trySend(HomeMapEffect.MoveCameraToPlace(closest))
                    } else {
                        val fallback = createCoordinateOnlyResult(latitude, longitude, name)
                        _selectedResult.value = fallback
                        _effects.trySend(HomeMapEffect.MoveCameraToPlace(fallback))
                    }
                }
                .onFailure {
                    Napier.e(it) { "Failed to search landmark. name: $name" }
                    val fallback = createCoordinateOnlyResult(latitude, longitude, name)
                    _selectedResult.value = fallback
                    _effects.trySend(HomeMapEffect.MoveCameraToPlace(fallback))
                }
        }
    }

    private fun createCoordinateOnlyResult(
        latitude: Double,
        longitude: Double,
        name: String? = null,
    ): SearchResultItem {
        return SearchResultItem(
            placeId = "",
            name = name ?: "${latitude.toString().take(8)}, ${longitude.toString().take(8)}",
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

    internal fun onWaypointClicked(index: Int) {
        val initialQuery = when (val waypoint = _waypoints.value.getOrNull(index)) {
            is RouteWaypoint.Place -> waypoint.name
            else -> null
        }

        _overlayState.value = HomeMapOverlayState.WaypointSearch(
            index = index,
            initialQuery = initialQuery,
        )
    }

    fun onWaypointSuggestionSelected(suggestion: SearchSuggestionItem) {
        val overlay = _overlayState.value as? HomeMapOverlayState.WaypointSearch ?: return

        viewModelScope.launch {
            searchRepository.select(suggestion.id)
                .onSuccess { result ->
                    searchRepository.addHistory(result)
                    _waypointEditResultInternal.value = overlay.index to RouteWaypoint.Place(
                        name = result.name,
                        latitude = result.latitude,
                        longitude = result.longitude,
                    )
                    _overlayState.value = HomeMapOverlayState.None
                }
                .onFailure {
                    Napier.e(it) { "Failed to select waypoint suggestion. id: ${suggestion.id}" }
                }
        }
    }

    fun onWaypointHistorySelected(history: SearchHistory) {
        val overlay = _overlayState.value as? HomeMapOverlayState.WaypointSearch ?: return

        _waypointEditResultInternal.value = overlay.index to RouteWaypoint.Place(
            name = history.name,
            latitude = history.latitude,
            longitude = history.longitude,
        )
        _overlayState.value = HomeMapOverlayState.None
    }

    fun onWaypointSearchDismissed() {
        _overlayState.value = HomeMapOverlayState.None
    }

    fun consumeWaypointEditResult() {
        _waypointEditResultInternal.value = null
    }

    fun onDismissRoutes() {
        routeSearchJob?.cancel()
        _routeResults.value = persistentListOf()
        _waypoints.value = persistentListOf()
        _topBarMode.value = RoutePreviewTopBarMode.Viewing
        _isRouteSearching.value = false

        routeManager.clearRoutes()
        guidanceSessionManager.setNavigationState(NavigationState.Browsing)

        // selectedResult は維持 → reduce が PlaceDetails を返す → カメラを地点に戻す
        val place = _selectedResult.value
        if (place != null) {
            _effects.trySend(HomeMapEffect.MoveCameraToPlace(place))
        }
    }

    private fun clearGuidanceEffects() {
        _effects.trySend(HomeMapEffect.SetKeepScreenOn(enabled = false))
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
