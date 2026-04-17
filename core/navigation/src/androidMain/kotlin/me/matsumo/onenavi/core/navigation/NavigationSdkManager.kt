package me.matsumo.onenavi.core.navigation

import android.app.Activity
import android.app.Application
import android.util.DisplayMetrics
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class NavigationSdkManager(
    private val application: Application,
    private val routeManager: RouteManager,
) {

    private val packageName = application.packageName

    private val _isNavigatorReady = MutableStateFlow(false)
    val isNavigatorReady: StateFlow<Boolean> = _isNavigatorReady.asStateFlow()

    private val _initializationErrorCode = MutableStateFlow<Int?>(null)
    val initializationErrorCode: StateFlow<Int?> = _initializationErrorCode.asStateFlow()

    private val _tripProgress = MutableStateFlow<NavigationTripProgressSnapshot?>(null)
    val tripProgress: StateFlow<NavigationTripProgressSnapshot?> = _tripProgress.asStateFlow()

    private val _isOffRoute = MutableStateFlow(false)
    val isOffRoute: StateFlow<Boolean> = _isOffRoute.asStateFlow()

    private val _arrivalEvents = MutableSharedFlow<NavigationArrivalSnapshot>(extraBufferCapacity = 4)
    val arrivalEvents: SharedFlow<NavigationArrivalSnapshot> = _arrivalEvents.asSharedFlow()

    val navInfo: StateFlow<NavigationFeedSnapshot?> = TurnByTurnUpdateBus.navInfo

    private val _roadSnappedLocationProvider = MutableStateFlow<RoadSnappedLocationProvider?>(null)
    val roadSnappedLocationProvider: StateFlow<RoadSnappedLocationProvider?> =
        _roadSnappedLocationProvider.asStateFlow()

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

    fun initialize(activity: Activity) {
        if (navigator != null || navigatorInitializing) return

        if (!NavigationApi.areTermsAccepted(activity.application)) {
            navigatorInitializing = true
            Napier.i(tag = TAG) { "Navigation SDK terms were not accepted. Showing terms dialog." }

            NavigationApi.showTermsAndConditionsDialog(activity, COMPANY_NAME) { accepted ->
                navigatorInitializing = false
                if (accepted) {
                    Napier.i(tag = TAG) { "Navigation SDK terms were accepted." }
                    initialize(activity)
                } else {
                    _initializationErrorCode.value = TERMS_NOT_ACCEPTED_ERROR_CODE
                    Napier.e(tag = TAG) { "Navigation SDK terms were not accepted." }
                }
            }
            return
        }

        navigatorInitializing = true
        NavigationApi.getNavigator(
            activity,
            object : NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    navigatorInitializing = false
                    attachNavigator(
                        navigator = navigator,
                        displayMetrics = activity.resources.displayMetrics,
                    )
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
        val navigator = awaitNavigatorReady()
            ?: error("Navigator is not ready.")

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

    private suspend fun awaitNavigatorReady(): Navigator? {
        navigator?.let { return it }
        val isReady = withTimeoutOrNull(NAVIGATOR_READY_TIMEOUT_MS.milliseconds) { isNavigatorReady.first { it } } != null
        return navigator.takeIf { isReady }
    }

    private fun attachNavigator(
        navigator: Navigator,
        displayMetrics: DisplayMetrics,
    ) {
        this.navigator = navigator
        _isNavigatorReady.value = true
        _initializationErrorCode.value = null
        _roadSnappedLocationProvider.value = NavigationApi.getRoadSnappedLocationProvider(application)

        navigator.addArrivalListener(arrivalListener)
        navigator.addRouteChangedListener(routeChangedListener)
        navigator.addReroutingListener(reroutingListener)
        navigator.addRemainingTimeOrDistanceChangedListener(
            PROGRESS_TIME_THRESHOLD_SECONDS,
            PROGRESS_DISTANCE_THRESHOLD_METERS,
            remainingListener,
        )
        val registered = navigator.registerServiceForNavUpdates(
            packageName,
            SERVICE_CLASS_NAME,
            NavigationUpdatesOptions.builder()
                .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.NONE)
                .setNumNextStepsToPreview(NUM_NEXT_STEPS_TO_PREVIEW)
                .setDisplayMetrics(displayMetrics)
                .build(),
        )
        if (!registered) {
            Napier.e(tag = TAG) { "Failed to register NavigationUpdatesService for turn-by-turn feed." }
        }
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
        val currentRoutes = routeManager.routes.value
        val primaryRoute = activeRoute ?: currentRoutes.firstOrNull() ?: return
        val geometry = navigator.routeSegments
            .flatMap { segment -> segment.latLngs.map { point -> point.toRoutePoint() } }
            .dedupeAdjacent()
            .toImmutableList()

        if (geometry.isEmpty()) return

        val updatedPrimaryRoute = primaryRoute.copy(geometry = geometry)
        routeManager.setRoutes(
            buildList {
                add(updatedPrimaryRoute)
                currentRoutes.drop(1).forEach(::add)
            },
        )
        activeRoute = updatedPrimaryRoute
    }

    private fun updateTripProgress() {
        val current = navigator?.currentTimeAndDistance ?: return

        _tripProgress.value = NavigationTripProgressSnapshot(
            timeRemainingSeconds = current.seconds,
            distanceRemainingMeters = current.meters,
        )
    }

    private fun handleArrival(event: ArrivalEvent) {
        val snapshot = NavigationArrivalSnapshot(
            waypointTitle = event.waypoint.title,
            isFinalDestination = event.isFinalDestination,
        )
        _arrivalEvents.tryEmit(snapshot)
    }

    private suspend fun <T> ListenableResultFuture<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
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
        private const val NAVIGATOR_READY_TIMEOUT_MS = 5_000L
        private const val COMPANY_NAME = "OneNavi"
        private const val TERMS_NOT_ACCEPTED_ERROR_CODE = -1
    }
}
