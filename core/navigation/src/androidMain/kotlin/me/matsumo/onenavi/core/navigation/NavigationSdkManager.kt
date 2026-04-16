package me.matsumo.onenavi.core.navigation

import android.app.Activity
import android.app.Application
import android.location.Location
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.ArrivalEvent
import com.google.android.libraries.navigation.CustomRoutesOptions
import com.google.android.libraries.navigation.ListenableResultFuture
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationApi.NavigatorListener
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.Waypoint
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NavigationSdkManager(
    application: Application,
    private val routeManager: RouteManager,
) {

    private val packageName = application.packageName
    private val roadSnappedLocationProvider = NavigationApi.getRoadSnappedLocationProvider(application)

    private val _isNavigatorReady = MutableStateFlow(false)
    val isNavigatorReady: StateFlow<Boolean> = _isNavigatorReady.asStateFlow()

    private val _initializationErrorCode = MutableStateFlow<Int?>(null)
    val initializationErrorCode: StateFlow<Int?> = _initializationErrorCode.asStateFlow()

    private val _roadSnappedLocation = MutableStateFlow<Location?>(null)
    val roadSnappedLocation: StateFlow<Location?> = _roadSnappedLocation.asStateFlow()

    private val _tripProgress = MutableStateFlow<NavigationTripProgressSnapshot?>(null)
    val tripProgress: StateFlow<NavigationTripProgressSnapshot?> = _tripProgress.asStateFlow()

    private val _isOffRoute = MutableStateFlow(false)
    val isOffRoute: StateFlow<Boolean> = _isOffRoute.asStateFlow()

    private val _arrivalEvents = MutableSharedFlow<NavigationArrivalSnapshot>(extraBufferCapacity = 4)
    val arrivalEvents: SharedFlow<NavigationArrivalSnapshot> = _arrivalEvents.asSharedFlow()

    val navInfo: StateFlow<NavigationFeedSnapshot?> = TurnByTurnUpdateBus.navInfo

    private var navigator: Navigator? = null
    private var navigatorInitializing = false
    private var activeRoute: GoogleRoute? = null

    private val arrivalListener = Navigator.ArrivalListener { event ->
        handleArrival(event)
    }
    private val routeChangedListener = Navigator.RouteChangedListener {
        refreshRouteGeometry()
        if (_isOffRoute.value) {
            _isOffRoute.value = false
        }
        updateTripProgress()
    }
    private val reroutingListener = Navigator.ReroutingListener {
        _isOffRoute.value = true
    }
    private val remainingListener = Navigator.RemainingTimeOrDistanceChangedListener {
        updateTripProgress()
    }
    private val roadSnappedLocationListener = object : RoadSnappedLocationProvider.LocationListener {
        override fun onLocationChanged(location: Location) {
            _roadSnappedLocation.value = location
        }

        override fun onRawLocationUpdate(location: Location) {
            // Fused の raw location は CameraManager 側でも受けているので使わない。
        }
    }

    fun initialize(activity: Activity) {
        if (navigator != null || navigatorInitializing) return

        navigatorInitializing = true
        NavigationApi.getNavigator(
            activity,
            object : NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    navigatorInitializing = false
                    attachNavigator(navigator)
                }

                override fun onError(errorCode: Int) {
                    navigatorInitializing = false
                    _initializationErrorCode.value = errorCode
                    Napier.e(tag = TAG) { "Navigator init failed. errorCode=$errorCode" }
                }
            },
        )
    }

    suspend fun startNavigation(route: GoogleRoute): Result<Unit> = runCatching {
        val navigator = requireNotNull(navigator) { "Navigator is not ready." }
        activeRoute = route

        val waypoints = buildWaypoints(route)
        val routeStatus = buildCustomRoutesOptions(route)
            ?.let { customRoutesOptions ->
                navigator.setDestinations(waypoints, customRoutesOptions).awaitResult()
            }
            ?: navigator.setDestinations(waypoints).awaitResult()

        check(routeStatus == Navigator.RouteStatus.OK) {
            "Failed to set destinations. status=$routeStatus"
        }

        navigator.setAudioGuidance(Navigator.AudioGuidance.SILENT)
        navigator.startGuidance()
        updateTripProgress()
        refreshRouteGeometry()
    }

    fun stopNavigation() {
        navigator?.stopGuidance()
        navigator?.clearDestinations()
        _tripProgress.value = null
        _isOffRoute.value = false
        activeRoute = null
        TurnByTurnUpdateBus.clear()
    }

    fun continueToNextDestination() {
        navigator?.continueToNextDestination()
        updateTripProgress()
    }

    fun cleanup() {
        roadSnappedLocationProvider.removeLocationListener(roadSnappedLocationListener)
        navigator?.let { navigator ->
            navigator.removeArrivalListener(arrivalListener)
            navigator.removeRouteChangedListener(routeChangedListener)
            navigator.removeReroutingListener(reroutingListener)
            navigator.removeRemainingTimeOrDistanceChangedListener(remainingListener)
            navigator.unregisterServiceForNavUpdates()
            navigator.cleanup()
        }
        navigator = null
        _isNavigatorReady.value = false
        _tripProgress.value = null
        _isOffRoute.value = false
        _roadSnappedLocation.value = null
        TurnByTurnUpdateBus.clear()
    }

    private fun attachNavigator(navigator: Navigator) {
        this.navigator = navigator
        _isNavigatorReady.value = true
        _initializationErrorCode.value = null

        navigator.addArrivalListener(arrivalListener)
        navigator.addRouteChangedListener(routeChangedListener)
        navigator.addReroutingListener(reroutingListener)
        navigator.addRemainingTimeOrDistanceChangedListener(
            PROGRESS_TIME_THRESHOLD_SECONDS,
            PROGRESS_DISTANCE_THRESHOLD_METERS,
            remainingListener,
        )
        navigator.registerServiceForNavUpdates(
            packageName,
            SERVICE_CLASS_NAME,
            NavigationUpdatesOptions.builder()
                .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.NONE)
                .setNumNextStepsToPreview(NUM_NEXT_STEPS_TO_PREVIEW)
                .build(),
        )
        roadSnappedLocationProvider.addLocationListener(roadSnappedLocationListener)
    }

    private fun buildCustomRoutesOptions(route: GoogleRoute): CustomRoutesOptions? {
        val routeToken = route.routeToken ?: return null
        return CustomRoutesOptions.builder()
            .setRouteToken(routeToken)
            .setTravelMode(CustomRoutesOptions.TravelMode.DRIVING)
            .build()
    }

    private fun buildWaypoints(route: GoogleRoute): List<Waypoint> {
        val intermediateWaypoints = route.intermediateWaypoints.mapIndexed { index, waypoint ->
            waypoint.toWaypoint(
                title = "経由地${index + 1}",
                vehicleStopover = true,
            )
        }
        return buildList {
            addAll(intermediateWaypoints)
            add(
                route.destination.toWaypoint(
                    title = "目的地",
                    vehicleStopover = true,
                ),
            )
        }
    }

    private fun refreshRouteGeometry() {
        val navigator = navigator ?: return
        val seedRoute = activeRoute ?: routeManager.routes.value.firstOrNull() ?: return
        val geometry = navigator.getRouteSegments()
            .flatMap { segment -> segment.getLatLngs().map { point -> point.toRoutePoint() } }
            .dedupeAdjacent()
            .toImmutableList()

        if (geometry.isEmpty()) return

        val updatedRoute = seedRoute.copy(
            geometry = geometry,
            distanceMeters = _tripProgress.value?.distanceRemainingMeters?.toDouble() ?: seedRoute.distanceMeters,
            durationSeconds = _tripProgress.value?.timeRemainingSeconds?.toDouble() ?: seedRoute.durationSeconds,
        )
        routeManager.setRoutes(listOf(updatedRoute))
        activeRoute = updatedRoute
    }

    private fun updateTripProgress() {
        val current = navigator?.getCurrentTimeAndDistance() ?: return
        _tripProgress.value = NavigationTripProgressSnapshot(
            timeRemainingSeconds = current.getSeconds(),
            distanceRemainingMeters = current.getMeters(),
        )
    }

    private fun handleArrival(event: ArrivalEvent) {
        val snapshot = NavigationArrivalSnapshot(
            waypointTitle = event.getWaypoint().getTitle(),
            isFinalDestination = event.isFinalDestination(),
        )
        _arrivalEvents.tryEmit(snapshot)
    }

    private suspend fun <T> ListenableResultFuture<T>.awaitResult(): T = suspendCoroutine { continuation ->
        setOnResultListener { result ->
            continuation.resume(result)
        }
    }

    private fun RoutePoint.toWaypoint(
        title: String,
        vehicleStopover: Boolean,
    ): Waypoint {
        return Waypoint.builder()
            .setLatLng(latitude, longitude)
            .setTitle(title)
            .setVehicleStopover(vehicleStopover)
            .build()
    }

    private fun LatLng.toRoutePoint(): RoutePoint {
        return RoutePoint(latitude = latitude, longitude = longitude)
    }

    private fun List<RoutePoint>.dedupeAdjacent(): List<RoutePoint> {
        if (isEmpty()) return emptyList()

        return buildList {
            var previous: RoutePoint? = null
            for (point in this@dedupeAdjacent) {
                if (point != previous) {
                    add(point)
                }
                previous = point
            }
        }
    }

    companion object {
        private const val TAG = "NavigationSdkManager"
        private const val SERVICE_CLASS_NAME = "me.matsumo.onenavi.core.navigation.NavigationUpdatesService"
        private const val NUM_NEXT_STEPS_TO_PREVIEW = 3
        private const val PROGRESS_TIME_THRESHOLD_SECONDS = 5
        private const val PROGRESS_DISTANCE_THRESHOLD_METERS = 50
    }
}
