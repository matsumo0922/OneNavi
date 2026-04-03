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
import com.mapbox.navigation.base.route.NavigationRoute
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.RouteWaypoint
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
    private val navigationManager: HomeMapNavigationManager,
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

    private val _routeResults = MutableStateFlow<ImmutableList<RouteResult>>(persistentListOf())
    val routeResults: StateFlow<ImmutableList<RouteResult>> = _routeResults.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    private val _waypoints = MutableStateFlow<ImmutableList<RouteWaypoint>>(persistentListOf())
    val waypoints: StateFlow<ImmutableList<RouteWaypoint>> = _waypoints.asStateFlow()

    private val _editingWaypointIndex = MutableStateFlow<Int?>(null)
    val editingWaypointIndex: StateFlow<Int?> = _editingWaypointIndex.asStateFlow()

    private val _waypointEditResult = MutableStateFlow<Pair<Int, RouteWaypoint.Place>?>(null)
    val waypointEditResult: StateFlow<Pair<Int, RouteWaypoint.Place>?> = _waypointEditResult.asStateFlow()

    private var searchJob: Job? = null

    init {
        navigationManager.onAttach()

        @OptIn(FlowPreview::class)
        _query
            .debounce(DEBOUNCE.milliseconds)
            .distinctUntilChanged()
            .onEach { query -> performSearch(query) }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        navigationManager.onDetach()
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
            HomeMapViewEvent.OnSwapOriginDestination -> onSwapOriginDestination()
            is HomeMapViewEvent.OnRouteWaypointsConfirmed -> onRouteWaypointsConfirmed(event.waypoints)
            is HomeMapViewEvent.OnWaypointClicked -> onWaypointClicked(event.index)
            is HomeMapViewEvent.OnMapLandmarkSelected -> onMapLandmarkSelected(event.name, event.latitude, event.longitude)
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
        searchRoutesFromWaypoints(newWaypoints)
    }

    fun onRouteSelected(index: Int) {
        _selectedRouteIndex.value = index
        navigationManager.selectRoute(index)
    }

    private fun onSwapOriginDestination() {
        val current = _waypoints.value
        if (current.size != 2) return
        val swapped = persistentListOf(current[1], current[0])
        _waypoints.value = swapped
        searchRoutesFromWaypoints(swapped)
    }

    private fun onRouteWaypointsConfirmed(newWaypoints: ImmutableList<RouteWaypoint>) {
        _waypoints.value = newWaypoints
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

        viewModelScope.launch {
            routeRepository.searchRoutes(
                originLatitude = originLat,
                originLongitude = originLng,
                destinationLatitude = destLat,
                destinationLongitude = destLng,
                intermediateWaypoints = intermediateWaypoints,
            )
                .onSuccess { routes ->
                    _routeResults.value = routes.toImmutableList()
                    _selectedRouteIndex.value = 0

                    val navigationRoutes = routes.mapNotNull { it.platformRoute as? NavigationRoute }
                    navigationManager.setRoutes(navigationRoutes)
                }
                .onFailure {
                    Napier.e(it) { "Failed to search routes." }
                    _routeResults.value = persistentListOf()
                }
        }
    }

    private fun onMapLandmarkSelected(name: String?, latitude: Double, longitude: Double) {
        _searchResults.value = persistentListOf()

        if (name.isNullOrBlank()) {
            _selectedResult.value = createCoordinateOnlyResult(latitude, longitude)
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
                    } else {
                        _selectedResult.value = createCoordinateOnlyResult(latitude, longitude, name)
                    }
                }
                .onFailure {
                    Napier.e(it) { "Failed to search landmark. name: $name" }
                    _selectedResult.value = createCoordinateOnlyResult(latitude, longitude, name)
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

    private fun onWaypointClicked(index: Int) {
        _editingWaypointIndex.value = index
    }

    fun onWaypointSuggestionSelected(suggestion: SearchSuggestionItem) {
        val index = _editingWaypointIndex.value ?: return

        viewModelScope.launch {
            searchRepository.select(suggestion.id)
                .onSuccess { result ->
                    searchRepository.addHistory(result)
                    _waypointEditResult.value = index to RouteWaypoint.Place(
                        name = result.name,
                        latitude = result.latitude,
                        longitude = result.longitude,
                    )
                    _editingWaypointIndex.value = null
                }
                .onFailure {
                    Napier.e(it) { "Failed to select waypoint suggestion. id: ${suggestion.id}" }
                }
        }
    }

    fun onWaypointHistorySelected(history: SearchHistory) {
        val index = _editingWaypointIndex.value ?: return

        _waypointEditResult.value = index to RouteWaypoint.Place(
            name = history.name,
            latitude = history.latitude,
            longitude = history.longitude,
        )
        _editingWaypointIndex.value = null
    }

    fun onWaypointSearchDismissed() {
        _editingWaypointIndex.value = null
    }

    fun consumeWaypointEditResult() {
        _waypointEditResult.value = null
    }

    fun onDismissRoutes() {
        _routeResults.value = persistentListOf()
        _selectedRouteIndex.value = 0
        _waypoints.value = persistentListOf()
        navigationManager.clearRoutes()
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
